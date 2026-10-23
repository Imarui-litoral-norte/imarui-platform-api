param([string]$RuntimeDirectory=(Join-Path $PSScriptRoot '../../.runtime/iiam'),[string]$BaseUri='https://localhost:8443')
$ErrorActionPreference='Stop'
$private=[IO.Path]::GetFullPath($RuntimeDirectory)
$jar=Join-Path $private 'gateway-test-cookies.txt'
$requestFile=Join-Path $private 'gateway-test-request.conf'
$bodyFile=Join-Path $private 'gateway-test-body.json'
$responseFile=Join-Path $private 'gateway-test-response.json'
$headerFile=Join-Path $private 'gateway-test-response.headers'
$checks=[Collections.Generic.List[string]]::new()
function Request([string]$Method,[string]$Path,$Body=$null,[string]$Bearer=$null,[string]$Csrf=$null,[hashtable]$ExtraHeaders=@{}){
    $lines=@('header = "Accept: application/json"','header = "Content-Type: application/json"')
    if($Bearer){$lines+='header = "Authorization: Bearer '+$Bearer+'"'}
    if($Csrf){$lines+='header = "X-XSRF-TOKEN: '+$Csrf+'"'}
    foreach($name in $ExtraHeaders.Keys){$lines+='header = "'+$name+': '+$ExtraHeaders[$name]+'"'}
    if($null -ne $Body){[IO.File]::WriteAllText($bodyFile,($Body|ConvertTo-Json -Depth 12 -Compress));$lines+='data-binary = "@'+$bodyFile.Replace('\','/')+'"'}
    [IO.File]::WriteAllLines($requestFile,$lines)
    $status=& curl.exe --silent --show-error --cacert (Join-Path $private 'localhost.crt') --cookie $jar --cookie-jar $jar --config $requestFile --request $Method --output $responseFile --dump-header $headerFile --write-out '%{http_code}' ($BaseUri+$Path)
    if($LASTEXITCODE -ne 0){throw 'Gateway HTTPS transport failed'}
    $script:status=[int]$status
    $script:headers=[IO.File]::ReadAllText($headerFile)
    $text=[IO.File]::ReadAllText($responseFile)
    if($text -and $text.TrimStart().StartsWith('{')){return ($text|ConvertFrom-Json -AsHashtable)}
    return $null
}
function Expect([int]$Expected,[string]$Name){if($script:status -ne $Expected){throw "$Name returned $script:status (expected $Expected); inspect private response"};$checks.Add($Name)}
$null=Request 'GET' '/login';Expect 200 'frontend-login-https'
$null=Request 'GET' '/api/iam/docs';Expect 200 'iiam-docs-prefix'
if(![IO.File]::ReadAllText($responseFile).Contains('/api/iam/swagger-ui/')){throw 'Documentation assets escaped prefix'}
$spec=Request 'GET' '/api/iam/v3/api-docs';Expect 200 'iiam-openapi'
if($spec.servers[0].url -ne '/api/iam'){throw 'OpenAPI server escaped proxy'}
$keys=Request 'GET' '/api/iam/.well-known/jwks.json';Expect 200 'jwks-through-gateway'
if($keys.keys.Count -lt 1){throw 'No signing key'}
$null=Request 'OPTIONS' '/api/iam/authentication/login' $null $null $null @{Origin=$BaseUri;'Access-Control-Request-Method'='POST'};Expect 200 'same-origin-options'
$null=Request 'GET' '/api/iam/authentication/csrf' $null $null $null @{Origin='https://attacker.invalid'};Expect 403 'cors-foreign-origin-rejected'
$credentials=Get-Content (Join-Path $private 'core-operator.properties') -Raw|ConvertFrom-StringData
$csrf=(Request 'GET' '/api/iam/authentication/csrf').token;Expect 200 'csrf-cookie'
$payload=@{identityKind=$credentials.IDENTITY_KIND;identifier=$credentials.IDENTIFIER;password=$credentials.PASSWORD}
$null=Request 'POST' '/api/iam/authentication/login' $payload;Expect 403 'login-requires-csrf'
$session=Request 'POST' '/api/iam/authentication/login' $payload $null $csrf;Expect 200 'login-v1'
if($session.refreshToken){throw 'Refresh token leaked in JSON'}
if($headers -notmatch '(?i)Set-Cookie:.*Secure.*HttpOnly' -or $headers -notmatch '(?i)Path=/api/iam/authentication'){throw 'Refresh cookie flags/path not preserved'}
$access=$session.accessToken
$null=Request 'GET' '/api/v1/gateway/me';Expect 401 'core-api-never-authenticated-by-cookie'
$me=Request 'GET' '/api/v1/gateway/me' $null $access;Expect 200 'own-context-live-pdp'
if($me.authorities -notcontains 'logimarui-core.services.read'){throw 'Core capability missing'}
$null=Request 'GET' '/api/v1/admin/services/overview' $null $access;Expect 200 'supervisor-bearer-pdp'
$null=Request 'GET' '/api/v1/receivables/titles?size=1' $null $access;Expect 200 'retained-receivables-read'
$null=Request 'GET' '/critica-pedidos/';Expect 200 'critica-ui-through-gateway'
$null=Request 'POST' '/api/v1/gateway/browser-context' $null $access;Expect 204 'browser-context-secure-cookies'
if($headers -notmatch '(?i)LG_SERVICE_ACCESS=.*Secure.*HttpOnly.*SameSite=Strict'){throw 'Browser cookie flags not preserved'}
$null=Request 'GET' '/api/savi/health';Expect 200 'savi-navigation-cookie-live-pdp'
$null=Request 'POST' '/api/savi/health';Expect 403 'cookie-write-origin-required'
$csrf=(Request 'GET' '/api/iam/authentication/csrf').token
$next=Request 'POST' '/api/iam/authentication/refresh' $null $null $csrf;Expect 200 'refresh-cookie-rotation'
if(!$next.accessToken -or $next.accessToken -eq $access){throw 'Access did not rotate'}
$null=Request 'DELETE' '/api/v1/gateway/browser-context' $null $next.accessToken;Expect 204 'browser-context-cleared'
$csrf=(Request 'GET' '/api/iam/authentication/csrf').token
$null=Request 'POST' '/api/iam/authentication/logout' $null $null $csrf;Expect 204 'logout-cookie-csrf'
$null=Request 'POST' '/api/iam/authentication/refresh' $null $null $csrf;Expect 401 'logged-out-refresh-rejected'
$null=Request 'GET' '/api/v1/admin/services/overview' $null $next.accessToken;Expect 403 'logged-out-actor-denied-by-live-pdp'
$report=@{gateway=$BaseUri;passed=$checks.Count;checks=$checks;verifiedAt=[DateTimeOffset]::UtcNow.ToString('o')}
$report|ConvertTo-Json -Depth 4|Set-Content (Join-Path $private 'gateway-verification.json')
$report|ConvertTo-Json -Depth 4
