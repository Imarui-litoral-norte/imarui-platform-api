param([string]$RuntimeDirectory=(Join-Path $PSScriptRoot '../../.runtime/iiam'), [string]$BaseUri='https://localhost:4105')
$ErrorActionPreference='Stop'
$private=[IO.Path]::GetFullPath($RuntimeDirectory)
$certificate=Join-Path $private 'localhost.crt'
$cookieJar=Join-Path $private 'operator-cookies.txt'
$bearer=$null; $csrf=$null
function Request([string]$Method,[string]$Path,$Body=$null,[string]$Proof=$null,[switch]$AllowNotFound) {
    $curlConfig=Join-Path $private 'http-request.conf'
    $responseFile=Join-Path $private 'http-response.json'
    $bodyFile=Join-Path $private 'http-body.json'
    $lines=@('header = "Content-Type: application/json"')
    if($Proof){$lines+='header = "Authorization: Service '+$Proof+'"'}
    elseif($bearer){$lines+='header = "Authorization: Bearer '+$bearer+'"'}
    if($csrf){$lines+='header = "X-XSRF-TOKEN: '+$csrf+'"'}
    if($null -ne $Body){
        $json=if($Body -is [string]){$Body}else{$Body | ConvertTo-Json -Depth 15 -Compress}
        [IO.File]::WriteAllText($bodyFile,$json)
        $lines+='data-binary = "@'+$bodyFile.Replace('\','/')+'"'
    }
    [IO.File]::WriteAllLines($curlConfig,$lines)
    $status=& curl.exe --silent --show-error --cacert $certificate --cookie $cookieJar --cookie-jar $cookieJar --request $Method --config $curlConfig --output $responseFile --write-out '%{http_code}' ($BaseUri+$Path)
    if($LASTEXITCODE -ne 0){throw 'IIAM private HTTP transport failed'}
    if($status -eq '404' -and $AllowNotFound){return $null}
    if([int]$status -ge 400){throw "IIAM operation $Method $Path returned $status; inspect the private response file"}
    $text=[IO.File]::ReadAllText($responseFile)
    if($text){return ($text | ConvertFrom-Json -AsHashtable)}
}
function Reason { return @{reason='IIAMS-154 explicit DEV integration provisioning';correlationId=[guid]::NewGuid().ToString()} }
function SaveState { [IO.File]::WriteAllText((Join-Path $private 'core-provisioning-state.json'),($state | ConvertTo-Json -Depth 8)) }
function Base64Url([byte[]]$Bytes){return [Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+','-').Replace('/','_')}
function ServiceProof([string]$Path,[string]$Body,[string]$Correlation) {
    $now=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $header=@{alg='RS256';typ='iiam-service+jwt';kid=$credential.id}|ConvertTo-Json -Compress
    $claims=@{iss='logimarui-core';sub='logimarui-core';aud=@('imarui-iam:services');iat=$now;exp=$now+45;jti=[guid]::NewGuid().ToString();htm='POST';htu=$Path;correlation_id=$Correlation;body_sha256=(Base64Url ([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($Body))))}|ConvertTo-Json -Compress
    $unsigned=(Base64Url ([Text.Encoding]::UTF8.GetBytes($header)))+'.'+(Base64Url ([Text.Encoding]::UTF8.GetBytes($claims)))
    return $unsigned+'.'+(Base64Url ($rsa.SignData([Text.Encoding]::UTF8.GetBytes($unsigned),[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.RSASignaturePadding]::Pkcs1)))
}
$operatorFile=Join-Path $private 'operator.properties'
$invitation=Get-Content (Join-Path $private 'bootstrap-invitation.json') -Raw | ConvertFrom-Json
if(-not (Test-Path $operatorFile)){
    $password='Dev!'+[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
    [IO.File]::WriteAllText($operatorFile,"IDENTITY_KIND=SERVICE`nIDENTIFIER=logimarui-dev-operator`nPASSWORD=$password`n")
    $null=Request 'POST' '/authentication/activation/complete' @{token=$invitation.invitation;password=$password}
}
$operator=Get-Content $operatorFile -Raw | ConvertFrom-StringData
$csrf=(Request 'GET' '/authentication/csrf').token
$session=Request 'POST' '/authentication/login' @{identityKind=$operator.IDENTITY_KIND;identifier=$operator.IDENTIFIER;password=$operator.PASSWORD}
$bearer=$session.accessToken
$stateFile=Join-Path $private 'core-provisioning-state.json'
$state=if(Test-Path $stateFile){Get-Content $stateFile -Raw|ConvertFrom-Json -AsHashtable}else{@{}}
$state.operatorIdentityId=$invitation.identityId
$platform='00000000-0000-0000-0000-000000000001'
$ownerBody=Reason; $ownerBody.serviceCode='logimarui-core';$ownerBody.namespace='logimarui-core'
$null=Request 'POST' '/api/v1/authorization/admin/catalog/owners' $ownerBody
$keyFile=Join-Path $private 'core-service-private.pem'
$rsa=[Security.Cryptography.RSA]::Create(3072)
try {
    if(Test-Path $keyFile){$rsa.ImportFromPem([IO.File]::ReadAllText($keyFile))}
    else{[IO.File]::WriteAllText($keyFile,$rsa.ExportPkcs8PrivateKeyPem())}
    $client=Request 'GET' '/api/v1/authorization/admin/service-clients/logimarui-core' -AllowNotFound
    if(-not $client){
        $body=Reason; $body.service='logimarui-core';$body.operations=@('PDP_DECIDE','CATALOG_SYNC');$body.scopeRoots=@($platform)
        $body.publicKey=[Convert]::ToBase64String($rsa.ExportSubjectPublicKeyInfo());$body.expiresAt=[DateTimeOffset]::UtcNow.AddDays(89).ToString('o')
        $client=Request 'POST' '/api/v1/authorization/admin/service-clients' $body
    }
    $fingerprint=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($rsa.ExportSubjectPublicKeyInfo())).ToLowerInvariant()
    $credential=$client.keys | Where-Object {$_.active -and $_.fingerprint -eq $fingerprint} | Select-Object -First 1
    if(-not $credential){throw 'No active IIAM credential matches the private Core key; do not reset keys automatically'}
    $state.credentialId=$credential.id;SaveState
    $entries=@(
        @{code='logimarui-core.services.read';description='Consultar supervisor e disponibilidade dos serviços';module='gateway';deprecated=$false},
        @{code='logimarui-core.services.manage';description='Iniciar, parar e reiniciar serviços do servidor';module='gateway';deprecated=$false},
        @{code='logimarui-core.services.use';description='Utilizar serviço protegido publicado pelo gateway';module='gateway';deprecated=$false},
        @{code='logimarui-core.modules.use';description='Utilizar módulo de negócio ainda hospedado no Core';module='core';deprecated=$false},
        @{code='logimarui-core.receivables.read';description='Consultar carteira e pagamentos de recebíveis';module='receivables';deprecated=$false})
    $manifest=@{owner='logimarui-core';namespace='logimarui-core';version=1;permissions=$entries}|ConvertTo-Json -Depth 8 -Compress
    $path='/api/v1/authorization/service/catalog';$correlation=[guid]::NewGuid().ToString()
    $null=Request 'POST' $path $manifest -Proof (ServiceProof $path $manifest $correlation)
    foreach($entry in $entries){
        if(-not $state.ContainsKey($entry.code)){
            $body=Reason;$body.permission=$entry.code;$body.rootScopeId=$platform
            $null=Request 'POST' '/api/v1/authorization/admin/catalog/initial-authority' $body
            $state[$entry.code]=$true;SaveState
        }
    }
    if(-not $state.roleId){
        $body=Reason;$body.name='LogImarui Core DEV operator';$body.ownership='GLOBAL';$body.permissions=@($entries|ForEach-Object {$_.code})
        $role=Request 'POST' '/api/v1/authorization/admin/roles' $body
        $state.roleId=$role.id;SaveState
    }
    # Governance cannot self-grant new permissions. Provision a separate synthetic DEV consumer.
    if(-not $state.devTenantId){
        $tenant=Request 'POST' '/api/v1/admin/tenants' @{code='CORE_DEV';name='Synthetic Core DEV integration tenant'}
        $state.devTenantId=$tenant.id;SaveState
    }
    if(-not $state.devTenantActive){
        $null=Request 'POST' "/api/v1/admin/tenants/$($state.devTenantId)/activate"
        $state.devTenantActive=$true;SaveState
    }
    if(-not $state.consumerApplicationId){
        $application=Request 'POST' '/api/v1/identity-applications' @{type='SERVICE';nickname='logimarui-dev-user';name='Synthetic Core DEV consumer';email='dev-user@localhost.invalid'}
        $state.consumerApplicationId=$application.id;SaveState
    }
    if(-not $state.consumerIdentityId){
        $application=Request 'POST' "/api/v1/admin/identity-applications/$($state.consumerApplicationId)/approve" @{tenantId=$state.devTenantId;affiliation=@{type='EXTERNAL'}}
        $state.consumerIdentityId=$application.resolvedIdentityId;SaveState
    }
    $consumerFile=Join-Path $private 'core-operator.properties'
    if(-not (Test-Path $consumerFile)){
        $newPassword='Dev!'+[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
        [IO.File]::WriteAllText($consumerFile,"IDENTITY_KIND=SERVICE`nIDENTIFIER=logimarui-dev-user`nPASSWORD=$newPassword`n")
    }
    if(-not $state.consumerActive){
        $challenge=Request 'POST' "/authentication/admin/identities/$($state.consumerIdentityId)/invitation"
        $consumerCredentials=Get-Content $consumerFile -Raw|ConvertFrom-StringData
        $null=Request 'POST' '/authentication/activation/complete' @{token=$challenge.invitation;password=$consumerCredentials.PASSWORD}
        $state.consumerActive=$true;SaveState
    }
    if(-not $state.assignmentId){
        $body=Reason;$body.identityId=$state.consumerIdentityId;$body.roleId=$state.roleId;$body.scopeId=$platform
        $assignment=Request 'POST' '/api/v1/authorization/admin/assignments' $body
        $state.assignmentId=$assignment.id;SaveState
    }
    $resources=@(@{type='GATEWAY_SERVER';resourceId='local'})
    foreach($module in @('customerlabel','journey','memberships','occurrence','operationalload','receivables','replenishment','vehicles')){$resources+=@{type='CORE_MODULE';resourceId=$module}}
    foreach($service in @('gerenciador-extracao','gerenciador-database-monitoring','gerenciador-database-backup','imarui_savi_automation_service','n8n-interno','evolution-interno','critica-pedidos')){$resources+=@{type='GATEWAY_SERVICE';resourceId=$service}}
    foreach($resource in $resources){
        $marker="resource::$($resource.type)::$($resource.resourceId)"
        if(-not $state.ContainsKey($marker)){
            $body=Reason;$body.type=$resource.type;$body.resourceId=$resource.resourceId;$body.scopeId=$platform
            $null=Request 'POST' '/api/v1/authorization/admin/service-clients/logimarui-core/resources' $body
            $state[$marker]=$platform;SaveState
        }
    }
    [IO.File]::WriteAllLines((Join-Path $private 'core-client.properties'),@("IIAM_SERVICE_CODE=logimarui-core","IIAM_SERVICE_KEY_ID=$($credential.id)","IIAM_SERVICE_PRIVATE_KEY_FILE=$($keyFile.Replace('\','/'))","IIAM_BASE_URI=$BaseUri","IIAM_JWKS_URI=$BaseUri/.well-known/jwks.json","SERVICE_IIAM_UPSTREAM_SCHEME=https"))
    Write-Output 'Core client, owned catalog, DEV operator grants and trusted resource bindings configured through IIAM APIs. No legacy accounts imported.'
} finally {$rsa.Dispose()}
