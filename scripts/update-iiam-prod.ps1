param([switch]$LibraryOnly)
$ErrorActionPreference='Stop'
function Assert-IiamJar([string]$Path) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip=[IO.Compression.ZipFile]::OpenRead($Path)
    try {
        if(!$zip.GetEntry('BOOT-INF/classes/br/com/imarui/ima/IdentityServiceApplication.class')){throw 'JAR IIAM invalido.'}
        $migrations=@{}
        foreach($e in $zip.Entries | Where-Object {$_.FullName -like 'BOOT-INF/classes/db/migration/*' -and $_.Length -gt 0}) {
            $stream=$e.Open();$sha=[Security.Cryptography.SHA256]::Create()
            try{$migrations[$e.FullName]=[Convert]::ToBase64String($sha.ComputeHash($stream))}finally{$stream.Dispose();$sha.Dispose()}
        }
        return $migrations
    }finally{$zip.Dispose()}
}
function Invoke-IiamSwap {
    $stopped=$false
    try {
        Stop-Iiam
        $stopped=$true
        Copy-Item -LiteralPath $candidate -Destination $artifact -Force
        if((Get-FileHash $artifact).Hash -ne (Get-FileHash $candidate).Hash){throw 'Hash do JAR instalado divergiu.'}
        Start-Iiam
        Wait-Iiam
    } catch {
        $failure=$_.Exception.Message
        if($stopped) {
            try {
                Stop-Iiam
                Copy-Item -LiteralPath $backup -Destination $artifact -Force
                Start-Iiam
                Wait-Iiam
                throw "Nova versao falhou; versao anterior restaurada e respondendo. Erro: $failure"
            } catch {
                if($_.Exception.Message -like 'Nova versao falhou;*'){throw}
                throw "Falha na atualizacao e na recuperacao automatica. Backup preservado: $backup. Consulte os logs privados."
            }
        }
        throw
    }
    Remove-Item -LiteralPath $backup
    Write-Output 'IIAM atualizado e respondendo. JAR anterior removido apos validacao.'
}
if($LibraryOnly){return}
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$private=Join-Path $workspace '.runtime/iiam'
$service=Join-Path $workspace 'logimarui_services/imarui-identity-and-access-manager-service'
$artifact=Join-Path $service 'target/imarui-iam-service-0.0.1-SNAPSHOT.jar'
$pidFile=Join-Path $workspace 'logimarui-core-api/.runtime/pids/iiam.pid'
$java='C:\Users\lucas.l\.jdks\ms-17.0.18\bin\java.exe'
$lock=$null
try {
    $lock=[IO.File]::Open((Join-Path $private 'lifecycle.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    $runtime=Get-Content -LiteralPath $pidFile -Raw | ConvertFrom-Json
    if($runtime.serviceId -ne 'iiam' -or $runtime.port -le 0){throw 'Registro do IIAM invalido.'}
    $script:currentPid=[int]$runtime.rootPid
    $port=[int]$runtime.port
    function Stop-Iiam {
        $proc=Get-CimInstance Win32_Process -Filter ('ProcessId='+$script:currentPid)
        if(!$proc){return}
        if($proc.Name -ne 'java.exe' -or !$proc.CommandLine.Contains($artifact)){throw 'Identidade do processo IIAM nao confirmada; parada cancelada.'}
        $handle=Get-Process -Id $script:currentPid
        Stop-Process -Id $script:currentPid -ErrorAction Stop
        if(!$handle.WaitForExit(15000)){throw 'IIAM nao encerrou; artefato preservado.'}
    }
    function Start-Iiam {
        $info=New-Object Diagnostics.ProcessStartInfo
        $info.FileName=$java;$info.Arguments='-jar "'+$artifact+'"';$info.WorkingDirectory=$service
        $info.UseShellExecute=$false;$info.CreateNoWindow=$true
        $info.EnvironmentVariables.Clear()
        foreach($key in @('SystemRoot','WINDIR','TEMP','TMP','PATH')){if([Environment]::GetEnvironmentVariable($key)){$info.EnvironmentVariables[$key]=[Environment]::GetEnvironmentVariable($key)}}
        foreach($line in Get-Content -LiteralPath (Join-Path $private 'iiam.properties')) {
            if($line -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
                # Java properties escaping used by the managed runner.
                $value=[regex]::Replace($matches[2],'\\(u[0-9a-fA-F]{4}|.)',{param($m) switch($m.Groups[1].Value){'n'{"`n"};'r'{"`r"};'t'{"`t"};default{if($m.Groups[1].Value -match '^u') {[char][Convert]::ToInt32($m.Groups[1].Value.Substring(1),16)}else{$m.Groups[1].Value}}}})
                $info.EnvironmentVariables[$matches[1]]=$value
            }
        }
        $info.EnvironmentVariables['SERVER_PORT']=[string]$port
        $info.EnvironmentVariables['SERVER_ADDRESS']='127.0.0.1'
        $script:scheme=if($info.EnvironmentVariables['SERVER_SSL_ENABLED'] -eq 'true'){'https'}else{'http'}
        $log=Join-Path $release ('iiam-'+[guid]::NewGuid().ToString('N')+'.log')
        $info.FileName=$env:ComSpec
        $info.Arguments='/d /s /c ""'+$java+'" -jar "'+$artifact+'" >> "'+$log+'" 2>&1"'
        $launcher=[Diagnostics.Process]::Start($info)
        $deadline=(Get-Date).AddSeconds(10)
        do {
            $jvm=Get-CimInstance Win32_Process -Filter ('ParentProcessId='+$launcher.Id) | Where-Object {$_.Name -eq 'java.exe' -and $_.CommandLine.Contains($artifact)} | Select-Object -First 1
            if($jvm){break}
            Start-Sleep -Milliseconds 200
        }while((Get-Date) -lt $deadline)
        if(!$jvm){throw "JVM nao encontrada. Consulte $log"}
        $script:currentPid=[int]$jvm.ProcessId
        $runtime.rootPid=$script:currentPid;$runtime.listenerPid=$script:currentPid;$runtime.status='RUNNING'
        $runtime.startedAt=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()/1000.0
        $runtime | ConvertTo-Json | Set-Content -LiteralPath $pidFile -Encoding ASCII
    }
    function Wait-Iiam {
        $deadline=(Get-Date).AddSeconds(120)
        do {
            if(!(Get-Process -Id $script:currentPid -ErrorAction SilentlyContinue)){throw 'IIAM encerrou antes da prontidao.'}
            try {
                $result=Invoke-RestMethod -Uri ($script:scheme+'://localhost:'+$port+'/.well-known/jwks.json') -TimeoutSec 3
                if($result.keys.Count -gt 0){Start-Sleep -Seconds 3;if(Get-Process -Id $script:currentPid -ErrorAction SilentlyContinue){return}}
            }catch{}
            Start-Sleep -Seconds 1
        }while((Get-Date) -lt $deadline)
        throw 'IIAM nao ficou pronto em 120 segundos.'
    }
    if(!(Test-Path -LiteralPath $artifact)){throw 'JAR anterior ausente; atualizacao cancelada.'}
    $release=Join-Path $private ('iiam-build-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,8))
    New-Item -ItemType Directory -Path $release | Out-Null
    $archive=Join-Path $release 'source.zip'
    & git -C $service archive HEAD --format=zip -o $archive
    if($LASTEXITCODE -ne 0){throw 'Falha ao preparar fonte commitada.'}
    Expand-Archive -LiteralPath $archive -DestinationPath $release
    $env:JAVA_HOME=Split-Path (Split-Path $java)
    Write-Output "Compilando IIAM isoladamente; versao atual continua rodando. Logs: $release"
    Push-Location $release
    try { & .\mvnw.cmd -B -DskipTests package *> (Join-Path $release 'build.log');if($LASTEXITCODE -ne 0){throw 'Build falhou; IIAM atual preservado.'} }finally{Pop-Location}
    $candidate=Join-Path $release 'target/imarui-iam-service-0.0.1-SNAPSHOT.jar'
    $old=Assert-IiamJar $artifact;$new=Assert-IiamJar $candidate
    if(($old | ConvertTo-Json -Compress) -ne ($new | ConvertTo-Json -Compress)) {
        # Hashtable order is not stable; compare entries explicitly.
        if($old.Count -ne $new.Count -or @($old.Keys | Where-Object {$old[$_] -ne $new[$_]}).Count){throw 'Migracoes de banco divergentes; exige implantacao supervisionada. IIAM atual preservado.'}
    }
    $backup=Join-Path $release 'previous.jar';Copy-Item -LiteralPath $artifact -Destination $backup
    Invoke-IiamSwap
} catch { Write-Error $_; exit 1 }
finally {if($lock){$lock.Dispose()}}
