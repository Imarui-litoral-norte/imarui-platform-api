$ErrorActionPreference='Stop'
# Isolated fixture: no real process is started/stopped and no PROD PID is changed.
$fixture=Join-Path ([IO.Path]::GetTempPath()) ('logimarui-launcher-'+[guid]::NewGuid())
$scripts=Join-Path $fixture 'logimarui-core-api/scripts'
$private=Join-Path $fixture '.runtime/iiam'
New-Item -ItemType Directory -Force $scripts,$private | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'start-iiam-integrated-prod.ps1'),(Join-Path $PSScriptRoot 'managed-runtime-ownership.ps1') -Destination $scripts
$launcher=Join-Path $scripts 'start-iiam-integrated-prod.ps1'
$artifact=Join-Path $fixture 'logimarui-core-api/target/logimarui-core-api-0.0.1-SNAPSHOT.jar'
$global:launcherTestState=@{};$global:launcherTestState.snapshot=[pscustomobject]@{ProcessId=123;Name='java.exe';CommandLine=('java -jar "'+$artifact+'"')}
$global:launcherTestState.listening=$true
$global:launcherTestState.stopped=@()
$global:launcherTestState.requests=0
function Get-NetTCPConnection {param($State,$LocalPort,$ErrorAction) if($global:launcherTestState.listening){[pscustomobject]@{OwningProcess=123}}}
function Get-CimInstance {param($ClassName,$Filter) if($global:launcherTestState.snapshot -and $Filter -eq 'ProcessId=123'){$global:launcherTestState.snapshot}}
function Get-Process {param($Id,$ErrorAction) if($global:launcherTestState.snapshot -and $Id -eq 123){$global:launcherTestState.snapshot}}
function Stop-Process {param($Id,$ErrorAction) $global:launcherTestState.stopped+=$Id;$global:launcherTestState.snapshot=$null;$global:launcherTestState.listening=$false}
function curl.exe {
    $global:launcherTestState.requests++;$global:LASTEXITCODE=0
    if($global:launcherTestState.failNextProbe){$global:launcherTestState.failNextProbe=$false;'503'}else{'200'}
}
function Start-Process {
    param($FilePath,$ArgumentList,$WorkingDirectory,$WindowStyle,[switch]$PassThru,$RedirectStandardOutput,$RedirectStandardError)
    if($WindowStyle -ne 'Hidden'){throw 'Background launch must be hidden'}
    if($FilePath -eq $env:ComSpec){
        if(($ArgumentList -join ' ') -notlike '*--webpack*'){throw 'Production build must use Webpack'}
        $global:launcherTestState.builds++
        $code=if($global:launcherTestState.failBuild){1}else{0}
        if(!$code){
            $directory=Join-Path (Join-Path $fixture 'logImarui-frontend') $env:LOGIMARUI_NEXT_DIST_DIR
            New-Item -ItemType Directory -Path $directory -Force|Out-Null
            Set-Content -LiteralPath (Join-Path $directory 'BUILD_ID') -Value ('fixture-build-'+$global:launcherTestState.builds)
        }
        $builder=[pscustomobject]@{Id=777;Handle=[IntPtr]::Zero;ExitCode=$code}
        $builder|Add-Member -MemberType ScriptMethod -Name WaitForExit -Value {param([int]$Timeout) return $true}
        $builder|Add-Member -MemberType ScriptMethod -Name Refresh -Value {}
        return $builder
    }
    $global:launcherTestState.snapshot=[pscustomobject]@{ProcessId=123;Name='java.exe';CommandLine=('java -jar "'+$artifact+'"')}
    $global:launcherTestState.listening=$true
    [pscustomobject]@{Id=123}
}

# 1. Unreadable elevated process must fail clearly, before touching ownership state.
$command=$global:launcherTestState.snapshot.CommandLine
$global:launcherTestState.snapshot.CommandLine=$null
try{& $launcher -Action start;throw 'Unreadable identity accepted'}catch{
    if($_.Exception.Message -notlike '*administrador*'){throw}
}
if(Test-Path (Join-Path $private 'core-integrated.pid')){throw 'Unverified process was adopted'}

# 2. Exact artifact + listener recover stale state and check all three endpoints.
$global:launcherTestState.snapshot.CommandLine=$command
Set-Content -LiteralPath (Join-Path $private 'core-integrated.pid') -Value 999
& $launcher -Action start
if([IO.File]::ReadAllText((Join-Path $private 'core-integrated.pid')) -ne '123' -or $global:launcherTestState.requests -ne 3){throw 'PID recovery/readiness failed'}

# 3. A foreign listener must never be stopped or adopted.
$global:launcherTestState.snapshot.CommandLine='java -jar "C:\other\core.jar"'
try{& $launcher -Action stop;throw 'Foreign listener accepted'}catch{
    if($_.Exception.Message -notlike '*outro processo*'){throw}
}
if($global:launcherTestState.stopped.Count){throw 'Foreign process stopped'}

# 4. Stop recovers a missing PID, waits and removes ownership state.
$global:launcherTestState.snapshot.CommandLine=$command
Remove-Item -LiteralPath (Join-Path $private 'core-integrated.pid')
& $launcher -Action stop
if($global:launcherTestState.stopped.Count -ne 1 -or $global:launcherTestState.stopped[0] -ne 123 -or (Test-Path (Join-Path $private 'core-integrated.pid'))){throw 'Stop cleanup failed'}

# 5. Repeated stop on an empty runtime succeeds.
& $launcher -Action stop
if($global:launcherTestState.stopped.Count -ne 1){throw 'Repeated stop changed processes'}

# 6. A fresh start prepares the runtime and waits for HTTP readiness.
New-Item -ItemType Directory -Force (Split-Path $artifact),(Join-Path $fixture 'logImarui-frontend/.next-iiam-production') | Out-Null
foreach($path in @((Join-Path $fixture '.env'),(Join-Path $private 'core-client.properties'),(Join-Path $private 'core-truststore.jks'),(Join-Path $private 'intranet-store-password.txt'),$artifact,(Join-Path $fixture 'logImarui-frontend/.next-iiam-production/BUILD_ID'))){
    Set-Content -LiteralPath $path -Value 'fixture'
}
$before=$global:launcherTestState.requests
$global:launcherTestState.failNextProbe=$true
& $launcher -Action start
if($global:launcherTestState.requests -ne ($before+4) -or !$global:launcherTestState.listening){throw 'Fresh start did not retry until readiness'}

# 7. Restart stops the owned runtime then starts and verifies its replacement.
$before=$global:launcherTestState.requests
& $launcher -Action restart
if($global:launcherTestState.stopped.Count -ne 2 -or $global:launcherTestState.requests -ne ($before+3) -or !$global:launcherTestState.listening){throw 'Restart failed'}
# 8. An existing valid build does not prevent building the newest frontend.
$activeBefore=[IO.File]::ReadAllText((Join-Path $private 'frontend-active-dist.txt'))
if(!(Test-Path (Join-Path (Join-Path $fixture 'logImarui-frontend') ($activeBefore+'/BUILD_ID')))){throw 'Active release missing'}
& $launcher -Action restart
if($global:launcherTestState.builds -ne 3 -or $global:launcherTestState.stopped.Count -ne 3 -or [IO.File]::ReadAllText((Join-Path $private 'frontend-active-dist.txt')) -eq $activeBefore){throw 'Existing stale frontend was reused'}

# 9. A failed build must preserve the running Core and its managed processes.
$activeBefore=[IO.File]::ReadAllText((Join-Path $private 'frontend-active-dist.txt'))
$global:launcherTestState.failBuild=$true
try{& $launcher -Action restart;throw 'Failed build accepted'}catch{
    if($_.Exception.Message -notlike '*Falha ao gerar o frontend*'){throw}
}
if($global:launcherTestState.stopped.Count -ne 3 -or !$global:launcherTestState.listening){throw 'Failed build stopped the running Core'}
if([IO.File]::ReadAllText((Join-Path $private 'frontend-active-dist.txt')) -ne $activeBefore){throw 'Failed build replaced the active release'}

# 10. A failed build must also terminate execution inside the root log pipeline.
try{& $launcher -Action restart | ForEach-Object {[string]$_|Out-Null};throw 'Pipeline accepted a failed build'}catch{
    if($_.Exception.Message -notlike '*Falha ao gerar o frontend*'){throw}
}
if($global:launcherTestState.stopped.Count -ne 3){throw 'Pipeline continued after build failure'}

# 11. A simultaneous click cannot stop or start a second operation.
$held=[IO.File]::Open((Join-Path $private 'lifecycle.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
try{
    try{& $launcher -Action stop;throw 'Concurrent operation accepted'}catch{
        if($_.Exception.Message -notlike '*em andamento*'){throw}
    }
}finally{$held.Dispose()}
if($global:launcherTestState.stopped.Count -ne 3){throw 'Concurrent click modified processes'}
Write-Output 'PASS: 11 isolated PROD launcher regression scenarios; no real processes changed.'
Write-Output ('Fixture: '+$fixture)
