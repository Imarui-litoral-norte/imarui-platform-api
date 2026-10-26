param([string]$RuntimeDirectory=(Join-Path $PSScriptRoot '../../.runtime/iiam'),[ValidateSet('start','stop','restart')][string]$Action='start')
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$core=Join-Path $workspace 'logimarui-core-api'
$private=[IO.Path]::GetFullPath($RuntimeDirectory)
$javaHome='C:\Users\lucas.l\.jdks\ms-17.0.18'
. (Join-Path $PSScriptRoot 'managed-runtime-ownership.ps1')
if($Action -eq 'start'){
    $expectedArtifact=[IO.Path]::GetFullPath((Join-Path $core 'target/logimarui-core-api-0.0.1-SNAPSHOT.jar')).Replace('/','\')
    foreach($httpsListener in @(Get-NetTCPConnection -State Listen -LocalPort 443 -ErrorAction SilentlyContinue)){
        $runningCore=Get-CimInstance Win32_Process -Filter ('ProcessId='+$httpsListener.OwningProcess)
        if($runningCore -and $runningCore.Name -eq 'java.exe' -and $runningCore.CommandLine -and $runningCore.CommandLine.Replace('/','\').Contains('"'+$expectedArtifact+'"')){
            [IO.File]::WriteAllText((Join-Path $private 'core-integrated.pid'),[string]$runningCore.ProcessId)
            Write-Output ('Integrated PROD already running, PID '+$runningCore.ProcessId);return
        }
    }
}
if($Action -eq 'start' -and (Test-Path -LiteralPath (Join-Path $private 'core-integrated.pid'))){
    $savedPid=[int][IO.File]::ReadAllText((Join-Path $private 'core-integrated.pid'))
    $saved=Get-CimInstance Win32_Process -Filter ('ProcessId='+$savedPid)
    $https=Get-NetTCPConnection -State Listen -LocalPort 443 -ErrorAction SilentlyContinue
    if($saved -and $saved.Name -eq 'java.exe' -and $saved.CommandLine.Contains((Join-Path $core 'target/logimarui-core-api-0.0.1-SNAPSHOT.jar')) -and $https.OwningProcess -contains $savedPid){
        Write-Output ('Integrated PROD already running, PID '+$savedPid);return
    }
}
if($Action -in @('stop','restart')){
    $corePidFile=Join-Path $private 'core-integrated.pid'
    if(!(Test-Path -LiteralPath $corePidFile)){throw 'No integrated Core ownership record'}
    $coreProcessId=[int][IO.File]::ReadAllText($corePidFile)
    $process=Get-CimInstance Win32_Process -Filter ('ProcessId='+$coreProcessId)
    if($process -and ($process.Name -ne 'java.exe' -or !$process.CommandLine.Contains((Join-Path $core 'target/logimarui-core-api-0.0.1-SNAPSHOT.jar')))){throw 'Core process ownership mismatch'}
    # Child ownership comes from the supervisor files AND the current listener.
    $records=@(Get-ChildItem -LiteralPath (Join-Path $core '.runtime/pids') -Filter '*.pid' | ForEach-Object {Get-Content -LiteralPath $_.FullName -Raw|ConvertFrom-Json})
    foreach($record in $records){
        foreach($ownedProcessId in @($record.rootPid,$record.listenerPid)|Select-Object -Unique){
            Assert-ManagedRuntimeProcessTime $record (Get-CimInstance Win32_Process -Filter ('ProcessId='+$ownedProcessId))
        }
        $listener=Get-NetTCPConnection -State Listen -LocalPort $record.port -ErrorAction SilentlyContinue
        if($listener -and $listener.OwningProcess -notcontains $record.listenerPid){throw ('Service listener ownership mismatch: '+$record.serviceId)}
        if($record.serviceId -eq 'iiam'){
            $child=Get-CimInstance Win32_Process -Filter ('ProcessId='+$record.rootPid)
            $expectedIiam=[IO.Path]::GetFullPath((Join-Path $private '../../logimarui_services/imarui-identity-and-access-manager-service/target/imarui-iam-service-0.0.1-SNAPSHOT.jar')).Replace('/','\')
            if($child -and ($child.Name -ne 'java.exe' -or !$child.CommandLine.Replace('/','\').Contains($expectedIiam))){throw 'IIAM process ownership mismatch'}
        }
    }
    foreach($record in $records){
        foreach($ownedProcessId in @($record.listenerPid,$record.rootPid)|Select-Object -Unique){
            Assert-ManagedRuntimeProcessTime $record (Get-CimInstance Win32_Process -Filter ('ProcessId='+$ownedProcessId))
            if(Get-Process -Id $ownedProcessId -ErrorAction SilentlyContinue){Stop-Process -Id $ownedProcessId}
        }
    }
    if($process){Stop-Process -Id $coreProcessId}
    if($Action -eq 'stop'){Write-Output 'Integrated PROD stopped. PostgreSQL remains running.';return}
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
$env:LOGIMARUI_PROFILE='local';$env:SPRING_PROFILES_ACTIVE='local'
$env:LOGIMARUI_ROOT=$workspace;$env:LOGIMARUI_SERVICES_ROOT=Join-Path $workspace 'logimarui_services'
$env:CORE_API_PORT='443';$env:CORE_PUBLIC_ORIGIN='https://logistica.imaruileste.dominiolocal'
# Temporary PROD policy: every live authenticated session can use Core and gateway services.
$env:CORE_AUTHORIZATION_PERMISSIONS_REQUIRED='false'
$env:APP_SERVER_HTTP_REDIRECT_ENABLED='true';$env:APP_SERVER_HTTP_REDIRECT_PORT='80'
$env:LOGIMARUI_NEXT_DIST_DIR='.next-iiam-production';$env:LOGIMARUI_TSCONFIG_PATH='tsconfig.iiam-production.json'
$env:APP_CORS_ALLOWED_ORIGINS='https://logistica.imaruileste.dominiolocal,https://localhost'
$env:APP_FRONTEND_URL='http://127.0.0.1:8091';$env:FRONTEND_PORT='8091'
$env:NEXT_PUBLIC_GATEWAY_URL=''
$env:NEXT_PUBLIC_CORE_API_URL='';$env:NEXT_PUBLIC_API_URL='';$env:NEXT_PUBLIC_BACKEND_URL=''
$env:SERVICE_FRONTEND_COMMAND='npm run start'
$env:SERVICE_IIAM_ENABLED='true';$env:SERVICE_IIAM_START_ON_BOOT='true'
$env:SERVICE_IIAM_ENV_FILE=Join-Path $private 'iiam.properties'
$env:SERVICE_IIAM_JAR=Join-Path $private '../../logimarui_services/imarui-identity-and-access-manager-service/target/imarui-iam-service-0.0.1-SNAPSHOT.jar'
$env:SERVICE_IIAM_JAVA=Join-Path $javaHome 'bin/java.exe'
$env:SERVER_SSL_ENABLED='true';$env:SERVER_SSL_KEY_STORE_TYPE='PKCS12'
$env:SERVER_SSL_KEY_STORE=Join-Path $private 'intranet.p12'
$env:SERVER_SSL_KEY_STORE_PASSWORD=[IO.File]::ReadAllText((Join-Path $private 'intranet-store-password.txt')).Trim()
$env:SERVER_SSL_KEY_ALIAS='logimarui-intranet'
$env:JPA_DDL_AUTO='validate'
if (!(Test-Path -LiteralPath (Join-Path $core '../logImarui-frontend/.next-iiam-production/BUILD_ID'))) { throw 'Build the isolated production frontend first' }
$trust=Join-Path $private 'core-truststore.jks'
if(!(Test-Path -LiteralPath $trust)){
    Copy-Item -LiteralPath (Join-Path $javaHome 'lib/security/cacerts') -Destination $trust
    & (Join-Path $javaHome 'bin/keytool.exe') -importcert -noprompt -alias logimarui-iiam-local -file (Join-Path $private 'localhost.crt') -keystore $trust -storepass changeit
    if($LASTEXITCODE -ne 0){throw 'Local certificate trust setup failed'}
}
if(Get-NetTCPConnection -State Listen -LocalPort 443 -ErrorAction SilentlyContinue){throw 'PROD HTTPS port already occupied'}
$artifact=Join-Path $core 'target/logimarui-core-api-0.0.1-SNAPSHOT.jar'
if(!(Test-Path -LiteralPath $artifact)){throw 'Build the Core artifact and frontend before starting'}
$process=Start-Process -FilePath (Join-Path $javaHome 'bin/java.exe') -ArgumentList @(
    ('-Djavax.net.ssl.trustStore="'+$trust+'"'),'-Djavax.net.ssl.trustStorePassword=changeit','-jar',('"'+$artifact+'"')
) -WorkingDirectory $core -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $private 'core-integrated.log') -RedirectStandardError (Join-Path $private 'core-integrated.err.log')
[IO.File]::WriteAllText((Join-Path $private 'core-integrated.pid'),[string]$process.Id)
Write-Output ('Core PROD launched, PID '+$process.Id+'. Inspect private log and readiness before use.')
