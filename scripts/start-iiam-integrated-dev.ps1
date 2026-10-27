param([string]$RuntimeDirectory=(Join-Path $PSScriptRoot '../../.runtime/iiam'),[ValidateSet('start','stop','restart')][string]$Action='start')
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$core=Join-Path $workspace 'logimarui-core-api'
$private=[IO.Path]::GetFullPath($RuntimeDirectory)
$javaHome='C:\Users\lucas.l\.jdks\ms-17.0.18'
. (Join-Path $PSScriptRoot 'managed-runtime-ownership.ps1')
function Test-CoreIdentity($Snapshot){
    if(!$Snapshot){return $false}
    if([string]::IsNullOrWhiteSpace($Snapshot.CommandLine)){
        throw 'Nao foi possivel ler a identidade do processo. Execute o BAT em uma sessao com permissao para consultar o Java.'
    }
    $expected=[IO.Path]::GetFullPath((Join-Path $core 'target/logimarui-core-api-0.0.1-SNAPSHOT.jar')).Replace('/','\')
    $command=$Snapshot.CommandLine.Replace('/','\')
    return $Snapshot.Name -eq 'java.exe' -and ($command.Contains('"'+$expected+'"') -or $command.EndsWith(' -jar '+$expected))
}
function Wait-IntegratedReady([int]$CoreProcessId){
    $deadline=(Get-Date).AddMinutes(4)
    $certificate=Join-Path $private 'localhost.crt'
    do{
        if(!(Test-CoreIdentity (Get-CimInstance Win32_Process -Filter ('ProcessId='+$CoreProcessId)))){
            throw 'Core DEV encerrou antes de ficar pronto. Consulte .runtime/iiam/core-integrated.log'
        }
        $ready=$true
        foreach($url in @('https://localhost:8443/login','https://localhost:8443/api/iam/.well-known/jwks.json','http://127.0.0.1:8191/login')){
            $status=& curl.exe --silent --cacert $certificate --connect-timeout 2 --max-time 5 --output NUL --write-out '%{http_code}' $url
            if($LASTEXITCODE -ne 0 -or $status -ne '200'){$ready=$false;break}
        }
        if($ready){Write-Output 'DEV pronto: Gateway HTTPS, IIAM e frontend responderam HTTP 200.';return}
        Write-Output 'Aguardando Gateway HTTPS, IIAM e frontend do DEV...'
        Start-Sleep -Seconds 3
    }while((Get-Date) -lt $deadline)
    throw 'Timeout na inicializacao integrada do DEV. Consulte .runtime/iiam/core-integrated.log e core-integrated.err.log'
}
$corePidFile=Join-Path $private 'core-integrated.pid'
foreach($httpsListener in @(Get-NetTCPConnection -State Listen -LocalPort 8443 -ErrorAction SilentlyContinue)){
    $runningCore=Get-CimInstance Win32_Process -Filter ('ProcessId='+$httpsListener.OwningProcess)
    if(!(Test-CoreIdentity $runningCore)){throw 'Porta HTTPS 8443 ocupada por outro processo; nenhum processo foi encerrado.'}
    [IO.File]::WriteAllText($corePidFile,[string]$runningCore.ProcessId)
    if($Action -eq 'start'){
        Wait-IntegratedReady $runningCore.ProcessId
        Write-Output ('Integrated DEV already running, PID '+$runningCore.ProcessId);return
    }
}
if($Action -eq 'start' -and (Test-Path -LiteralPath $corePidFile)){
    $savedPid=[int][IO.File]::ReadAllText($corePidFile)
    $saved=Get-CimInstance Win32_Process -Filter ('ProcessId='+$savedPid)
    if(Test-CoreIdentity $saved){Wait-IntegratedReady $savedPid;return}
    Remove-Item -LiteralPath $corePidFile
}
if($Action -in @('stop','restart')){
    $coreProcessId=0
    $process=$null
    if(Test-Path -LiteralPath $corePidFile){
        $coreProcessId=[int][IO.File]::ReadAllText($corePidFile)
        $process=Get-CimInstance Win32_Process -Filter ('ProcessId='+$coreProcessId)
        if($process -and !(Test-CoreIdentity $process)){throw 'Core DEV process ownership mismatch'}
    }
    # Child ownership comes from the supervisor files AND the current listener.
    $runtimePids=Join-Path $core '.runtime/pids'
    $records=@(if(Test-Path -LiteralPath $runtimePids){Get-ChildItem -LiteralPath $runtimePids -Filter '*.pid' | ForEach-Object {Get-Content -LiteralPath $_.FullName -Raw|ConvertFrom-Json}})
    foreach($record in $records){
        foreach($ownedProcessId in @($record.rootPid,$record.listenerPid)|Select-Object -Unique){
            Assert-ManagedRuntimeProcessTime $record (Get-CimInstance Win32_Process -Filter ('ProcessId='+$ownedProcessId))
        }
        $listener=Get-NetTCPConnection -State Listen -LocalPort $record.port -ErrorAction SilentlyContinue
        if($listener -and $listener.OwningProcess -notcontains $record.listenerPid){throw ('Service listener ownership mismatch: '+$record.serviceId)}
        if($record.serviceId -eq 'iiam'){
            $child=Get-CimInstance Win32_Process -Filter ('ProcessId='+$record.rootPid)
            if($child -and ($child.Name -ne 'java.exe' -or !$child.CommandLine -or !$child.CommandLine.Replace('/','\').Contains((Join-Path $private 'build-identity-update/target/imarui-iam-service-0.0.1-SNAPSHOT.jar').Replace('/','\')))){throw 'IIAM process ownership mismatch; verifique a permissao para consultar o Java'}
        }
    }
    foreach($record in $records){
        foreach($ownedProcessId in @($record.listenerPid,$record.rootPid)|Select-Object -Unique){
            Assert-ManagedRuntimeProcessTime $record (Get-CimInstance Win32_Process -Filter ('ProcessId='+$ownedProcessId))
            if(Get-Process -Id $ownedProcessId -ErrorAction SilentlyContinue){Stop-Process -Id $ownedProcessId -ErrorAction Stop}
        }
    }
    if($process){Stop-Process -Id $coreProcessId -ErrorAction Stop}
    $deadline=(Get-Date).AddSeconds(30)
    do{
        $remaining=@(foreach($ownedProcessId in @($coreProcessId)+@($records.rootPid)+@($records.listenerPid)|Select-Object -Unique){
            if($ownedProcessId -gt 0){Get-Process -Id $ownedProcessId -ErrorAction SilentlyContinue}
        })
        if(!$remaining.Count){break}
        Start-Sleep -Milliseconds 500
    }while((Get-Date) -lt $deadline)
    if($remaining.Count){throw 'Timeout aguardando processos do DEV encerrarem; registros preservados.'}
    Remove-Item -LiteralPath $corePidFile -ErrorAction SilentlyContinue
    foreach($record in $records){Remove-Item -LiteralPath (Join-Path $runtimePids ($record.serviceId+'.pid')) -ErrorAction SilentlyContinue}
    if($Action -eq 'stop'){Write-Output 'Integrated DEV stopped. PostgreSQL remains running.';return}
}
function LoadEnvironment([string]$Path) {
    foreach($line in Get-Content -LiteralPath $Path){
        if($line -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$'){
            [Environment]::SetEnvironmentVariable($matches[1],$matches[2],'Process')
        }
    }
}
LoadEnvironment (Join-Path $workspace '.env')
LoadEnvironment (Join-Path $private 'core-client.properties')
$env:LOGIMARUI_PROFILE='dev';$env:SPRING_PROFILES_ACTIVE='dev'
$env:LOGIMARUI_ROOT=$workspace;$env:LOGIMARUI_SERVICES_ROOT=Join-Path $workspace 'logimarui_services'
$env:CORE_API_PORT='8443';$env:CORE_PUBLIC_ORIGIN='https://localhost:8443'
$env:APP_CORS_ALLOWED_ORIGINS='https://localhost:8443'
$env:APP_FRONTEND_URL='http://127.0.0.1:8191';$env:FRONTEND_PORT='8191'
$env:NEXT_PUBLIC_GATEWAY_URL=$env:CORE_PUBLIC_ORIGIN
$env:SERVICE_FRONTEND_COMMAND='npm run start'
$env:SERVICE_IIAM_ENABLED='true';$env:SERVICE_IIAM_START_ON_BOOT='true'
$env:SERVICE_IIAM_ENV_FILE=Join-Path $private 'iiam.properties'
$env:SERVICE_IIAM_JAR=Join-Path $private 'build-identity-update/target/imarui-iam-service-0.0.1-SNAPSHOT.jar'
$env:SERVICE_IIAM_JAVA=Join-Path $javaHome 'bin/java.exe'
$env:SERVER_SSL_ENABLED='true';$env:SERVER_SSL_KEY_STORE_TYPE='PKCS12'
$env:SERVER_SSL_KEY_STORE=Join-Path $private 'localhost.p12'
$env:SERVER_SSL_KEY_STORE_PASSWORD=[IO.File]::ReadAllText((Join-Path $private 'tls-store-password.txt')).Trim()
$env:SERVER_SSL_KEY_ALIAS='iiam-local'
$env:JPA_DDL_AUTO='validate'
$trust=Join-Path $private 'core-truststore.jks'
if(!(Test-Path -LiteralPath $trust)){
    Copy-Item -LiteralPath (Join-Path $javaHome 'lib/security/cacerts') -Destination $trust
    & (Join-Path $javaHome 'bin/keytool.exe') -importcert -noprompt -alias logimarui-iiam-local -file (Join-Path $private 'localhost.crt') -keystore $trust -storepass changeit
    if($LASTEXITCODE -ne 0){throw 'Local certificate trust setup failed'}
}
if(Get-NetTCPConnection -State Listen -LocalPort 8443 -ErrorAction SilentlyContinue){throw 'DEV HTTPS port already occupied'}
$artifact=Join-Path $core 'target/logimarui-core-api-0.0.1-SNAPSHOT.jar'
if(!(Test-Path -LiteralPath $artifact)){throw 'Build the Core artifact and frontend before starting'}
$process=Start-Process -FilePath (Join-Path $javaHome 'bin/java.exe') -ArgumentList @(
    ('-Djavax.net.ssl.trustStore="'+$trust+'"'),'-Djavax.net.ssl.trustStorePassword=changeit','-jar',('"'+$artifact+'"')
) -WorkingDirectory $core -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $private 'core-integrated.log') -RedirectStandardError (Join-Path $private 'core-integrated.err.log')
[IO.File]::WriteAllText((Join-Path $private 'core-integrated.pid'),[string]$process.Id)
Write-Output ('Core DEV launched, PID '+$process.Id+'. Aguardando prontidao...')
Wait-IntegratedReady $process.Id
