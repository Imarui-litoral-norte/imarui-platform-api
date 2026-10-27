param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('start', 'stop', 'restart', 'profile')]
    [string]$Action
)

$ErrorActionPreference = 'Stop'
# IIAM_DEV_DISPATCH
$iiamDevLauncher=Join-Path $PSScriptRoot 'logimarui-core-api/scripts/start-iiam-integrated-dev.ps1'
if((Test-Path -LiteralPath (Join-Path $PSScriptRoot '.runtime/iiam/localhost.p12')) -and (Test-Path -LiteralPath (Join-Path $PSScriptRoot '.runtime/iiam/iiam.properties'))){
    if($Action -eq 'profile'){
        [pscustomobject]@{logimaruiProfile='dev';springProfile='dev';coreApiPort=8443;frontendPort=8191}
    }else{
        $launcherLog=Join-Path $PSScriptRoot '.runtime/logs/logimarui-core-api-launcher.log'
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $launcherLog) | Out-Null
        try{
            & $iiamDevLauncher -Action $Action *>&1 | Tee-Object -FilePath $launcherLog -Append | Out-Host
        }catch{
            Add-Content -LiteralPath $launcherLog -Value ((Get-Date -Format s)+' '+$_.Exception.Message)
            throw
        }
    }
    return
}

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$coreDir = Join-Path $root 'logimarui-core-api'
$runtimeDir = Join-Path $root '.runtime'
$logsDir = Join-Path $runtimeDir 'logs'
$pidsDir = Join-Path $runtimeDir 'pids'
$pidFile = Join-Path $pidsDir 'logimarui-core-api.pid'
$logFile = Join-Path $logsDir 'logimarui-core-api.log'
$errorLogFile = Join-Path $logsDir 'logimarui-core-api.err.log'
$launcherLogFile = Join-Path $logsDir 'logimarui-core-api-launcher.log'
$mainClass = 'com.logimarui.LogimaruiCoreApiApplication'
$managedServicesBasePath = '/api/v1/admin/services'
$defaultJavaHome = 'C:\Users\lucas.l\.jdks\ms-17.0.18'
$javaHome = if ($env:LOGIMARUI_CORE_API_JAVA_HOME) { $env:LOGIMARUI_CORE_API_JAVA_HOME } else { $defaultJavaHome }

function Write-LauncherLog([string]$message, [string]$level = 'INFO') {
    $line = '{0} [{1}] {2}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss.fff'), $level, $message

    try {
        Add-Content -Path $launcherLogFile -Value $line -Encoding UTF8
    } catch {
        # O log auxiliar nunca deve impedir a inicializacao ou a exibicao do erro real.
    }

    Write-Host $line
}

function Write-StartupProgress([int]$percent, [string]$status) {
    $safePercent = [Math]::Max(0, [Math]::Min(100, $percent))

    Write-Progress `
        -Id 1 `
        -Activity 'Inicializacao do Logimarui' `
        -Status $status `
        -PercentComplete $safePercent

    Write-LauncherLog ('progresso={0}% | {1}' -f $safePercent, $status)
}

function Complete-StartupProgress {
    Write-Progress -Id 1 -Activity 'Inicializacao do Logimarui' -Completed
}

function Load-DotEnv {
    $envFile = Join-Path $root '.env'

    if (-not (Test-Path $envFile)) {
        return
    }

    foreach ($rawLine in Get-Content $envFile) {
        $line = $rawLine.Trim()

        if (-not $line) {
            continue
        }

        if ($line.StartsWith('#')) {
            continue
        }

        $parts = $line -split '=', 2

        if ($parts.Count -ne 2) {
            continue
        }

        [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1], 'Process')
    }
}

function Set-EnvironmentDefault([string]$name, [string]$value) {
    $currentValue = [Environment]::GetEnvironmentVariable($name, 'Process')

    if ([string]::IsNullOrWhiteSpace($currentValue)) {
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
}

function Resolve-LogimaruiProfile {
    $requestedProfile = if ($env:LOGIMARUI_PROFILE) { $env:LOGIMARUI_PROFILE.Trim().ToLowerInvariant() } else { '' }

    if (-not $requestedProfile) {
        $devMode = if ($env:LOGIMARUI_DEV_MODE) { $env:LOGIMARUI_DEV_MODE.Trim().ToLowerInvariant() } else { '' }
        $requestedProfile = if ($devMode -in @('1', 'true', 'yes', 'sim', 'dev')) { 'dev' } else { 'local' }
    }

    if ($requestedProfile -in @('dev', 'development')) {
        $profile = 'dev'
        $springProfile = 'dev'
        $isDev = $true
    } elseif ($requestedProfile -in @('local', 'prod', 'production')) {
        # prod/production permanecem como aliases de compatibilidade para snapshots antigos.
        $profile = 'local'
        $springProfile = 'local'
        $isDev = $false
    } else {
        throw ('LOGIMARUI_PROFILE invalido: {0}. Use dev ou local.' -f $requestedProfile)
    }

    $corePort = if ($isDev) { if ($env:LOGIMARUI_DEV_CORE_API_PORT) { $env:LOGIMARUI_DEV_CORE_API_PORT } else { '81' } } else { if ($env:LOGIMARUI_PROD_CORE_API_PORT) { $env:LOGIMARUI_PROD_CORE_API_PORT } else { '80' } }
    $frontendPort = if ($isDev) { if ($env:LOGIMARUI_DEV_FRONTEND_PORT) { $env:LOGIMARUI_DEV_FRONTEND_PORT } else { '8191' } } else { if ($env:LOGIMARUI_PROD_FRONTEND_PORT) { $env:LOGIMARUI_PROD_FRONTEND_PORT } else { '8091' } }

    [Environment]::SetEnvironmentVariable('LOGIMARUI_PROFILE', $profile, 'Process')
    [Environment]::SetEnvironmentVariable('SPRING_PROFILES_ACTIVE', $springProfile, 'Process')
    Set-EnvironmentDefault 'CORE_API_PORT' $corePort
    Set-EnvironmentDefault 'FRONTEND_PORT' $frontendPort
    Set-EnvironmentDefault 'LOGIMARUI_ROOT' $root
    Set-EnvironmentDefault 'LOGIMARUI_SERVICES_ROOT' (Join-Path $root 'logimarui_services')
    Set-EnvironmentDefault 'APP_FRONTEND_URL' ('http://127.0.0.1:{0}' -f $frontendPort)
    Set-EnvironmentDefault 'APP_CORS_ALLOWED_ORIGINS' ('http://localhost:{0},http://127.0.0.1:{0},http://localhost:{1},http://127.0.0.1:{1}' -f $corePort, $frontendPort)
}

function Get-CoreApiPort {
    if ($env:CORE_API_PORT) {
        return [int]$env:CORE_API_PORT
    }

    return 80
}

function Assert-JavaHome {
    if (-not (Test-Path $javaHome)) {
        throw ('JAVA_HOME nao encontrado: ' + $javaHome)
    }
}

function Get-ProcessSnapshot([long]$processId) {
    if ($processId -le 0) {
        return $null
    }

    try {
        $process = Get-Process -Id $processId -ErrorAction Stop
        $startTimeUtc = $null

        try {
            $startTimeUtc = $process.StartTime.ToUniversalTime()
        } catch {
            # O horario pode ficar indisponivel para processos de outra sessao.
        }

        return [pscustomobject]@{
            process       = $process
            processName   = $process.ProcessName
            startTimeUtc  = $startTimeUtc
        }
    } catch {
        return $null
    }
}

function Test-ProcessAlive([long]$processId) {
    return $null -ne (Get-ProcessSnapshot $processId)
}

function Test-LauncherProcessIdentity([object]$state, [switch]$Quiet) {
    if ($null -eq $state) {
        return $false
    }

    $snapshot = Get-ProcessSnapshot ([long]$state.launcherPid)

    if ($null -eq $snapshot) {
        if (-not $Quiet) {
            Write-LauncherLog ('PID do launcher nao esta ativo: {0}' -f $state.launcherPid) 'WARN'
        }

        return $false
    }

    $expectedProcessName = [string]$state.launcherProcessName
    $expectedStartTime = [string]$state.launcherStartTimeUtc

    if ([string]::IsNullOrWhiteSpace($expectedProcessName) -or [string]::IsNullOrWhiteSpace($expectedStartTime)) {
        if (-not $Quiet) {
            Write-LauncherLog ('Estado antigo sem identidade completa para o PID {0}; tratando como obsoleto.' -f $state.launcherPid) 'WARN'
        }

        return $false
    }

    if ($snapshot.processName -ne $expectedProcessName) {
        if (-not $Quiet) {
            Write-LauncherLog (
                'PID {0} reutilizado por outro processo: esperado={1}, atual={2}.' -f
                    $state.launcherPid,
                    $expectedProcessName,
                    $snapshot.processName
            ) 'WARN'
        }

        return $false
    }

    if ($null -eq $snapshot.startTimeUtc) {
        if (-not $Quiet) {
            Write-LauncherLog ('Nao foi possivel ler o horario de inicio do PID {0}; tratando como obsoleto.' -f $state.launcherPid) 'WARN'
        }

        return $false
    }

    try {
        $expectedStart = [DateTime]::Parse($expectedStartTime).ToUniversalTime()
        $deltaSeconds = [Math]::Abs(($snapshot.startTimeUtc - $expectedStart).TotalSeconds)

        if ($deltaSeconds -gt 5) {
            if (-not $Quiet) {
                Write-LauncherLog (
                    'PID {0} reutilizado: esperado iniciado em {1}, atual iniciado em {2}.' -f
                        $state.launcherPid,
                        $expectedStart.ToString('o'),
                        $snapshot.startTimeUtc.ToString('o')
                ) 'WARN'
            }

            return $false
        }
    } catch {
        if (-not $Quiet) {
            Write-LauncherLog ('Horario de inicio invalido no estado do PID {0}; tratando como obsoleto.' -f $state.launcherPid) 'WARN'
        }

        return $false
    }

    return $true
}

function Read-State {
    if (-not (Test-Path $pidFile)) {
        return $null
    }

    try {
        return Get-Content $pidFile -Raw | ConvertFrom-Json
    } catch {
        return $null
    }
}

function Save-State([int]$port, [object]$processSnapshot) {
    $state = [ordered]@{
        serviceId            = 'logimarui-core-api'
        port                 = $port
        launcherPid          = [long]$processSnapshot.process.Id
        launcherProcessName  = $processSnapshot.processName
        launcherStartTimeUtc = if ($processSnapshot.startTimeUtc) { $processSnapshot.startTimeUtc.ToString('o') } else { $null }
        logFile              = $logFile
        startedAt            = (Get-Date).ToString('o')
    }

    $state | ConvertTo-Json | Set-Content -Path $pidFile -Encoding ASCII

    return [pscustomobject]$state
}

function Remove-State {
    Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
}

function Test-AdminRuntime([int]$port) {
    try {
        Invoke-RestMethod -Uri (('http://127.0.0.1:{0}' -f $port) + $managedServicesBasePath + '/runtime') -Method Get -TimeoutSec 5 | Out-Null
        $script:lastAdminRuntimeProbeError = $null
        return $true
    } catch {
        $script:lastAdminRuntimeProbeError = $_.Exception.Message
        return $false
    }
}

function Get-AdminRuntimes([int]$port) {
    try {
        return Invoke-RestMethod -Uri (('http://127.0.0.1:{0}' -f $port) + $managedServicesBasePath + '/runtime') -Method Get -TimeoutSec 10
    } catch {
        return $null
    }
}

function Get-StartupSnapshot([int]$port) {
    try {
        return Invoke-RestMethod -Uri (('http://127.0.0.1:{0}' -f $port) + $managedServicesBasePath + '/startup') -Method Get -TimeoutSec 10
    } catch {
        $script:lastStartupProbeError = $_.Exception.Message
        return $null
    }
}

function Test-FrontendHttpReady {
    $frontendPort = [int]$env:FRONTEND_PORT

    try {
        Invoke-WebRequest -Uri ('http://127.0.0.1:{0}' -f $frontendPort) -UseBasicParsing -TimeoutSec 10 | Out-Null
        $script:lastFrontendProbeError = $null
        return $true
    } catch {
        $script:lastFrontendProbeError = $_.Exception.Message
        return $false
    }
}

function Test-FrontendRuntimeRegistered([int]$port) {
    try {
        $overview = Invoke-RestMethod -Uri (('http://127.0.0.1:{0}' -f $port) + $managedServicesBasePath + '/overview') -Method Get -TimeoutSec 10

        foreach ($service in @($overview.services)) {
            if ($service.serviceId -eq 'frontend' -or $service.port -eq [int]$env:FRONTEND_PORT) {
                return [bool]$service.running
            }
        }
    } catch {
        Write-LauncherLog ('Nao foi possivel consultar o status do frontend: {0}' -f $_.Exception.Message) 'WARN'
    }

    return $false
}

function Request-FrontendLifecycle([int]$port, [string]$operation) {
    Invoke-WebRequest `
        -Uri (('http://127.0.0.1:{0}' -f $port) + $managedServicesBasePath + '/frontend/' + $operation) `
        -Method Post `
        -UseBasicParsing `
        -TimeoutSec 30 | Out-Null
}

function Ensure-FrontendReady([int]$port) {
    $frontendPort = [int]$env:FRONTEND_PORT

    if (Test-FrontendHttpReady) {
        Write-LauncherLog ('Frontend respondeu na porta {0}.' -f $frontendPort)
        return
    }

    Write-LauncherLog ('Frontend nao respondeu na porta {0}: {1}' -f $frontendPort, $script:lastFrontendProbeError) 'WARN'

    $operation = if (Test-FrontendRuntimeRegistered $port) { 'restart' } else { 'start' }
    Write-LauncherLog ('Solicitando {0} do frontend pelo gateway.' -f $operation)
    Request-FrontendLifecycle -port $port -operation $operation

    $deadline = (Get-Date).AddMinutes(4)
    $lastCheckpointAt = (Get-Date).AddSeconds(-30)

    while ((Get-Date) -lt $deadline) {
        if (Test-FrontendHttpReady) {
            Write-StartupProgress 100 ('frontend pronto na porta {0}' -f $frontendPort)
            return
        }

        if (((Get-Date) - $lastCheckpointAt).TotalSeconds -ge 10) {
            Write-StartupProgress 90 ('aguardando frontend na porta {0}; motivo={1}' -f $frontendPort, $script:lastFrontendProbeError)
            $lastCheckpointAt = Get-Date
        }

        Start-Sleep -Seconds 2
    }

    throw ('Timeout aguardando frontend responder na porta ' + $frontendPort + '. Veja os logs em ' + $logFile + ' e ' + $launcherLogFile)
}

function Request-StopAll([int]$port) {
    Invoke-WebRequest -Uri (('http://127.0.0.1:{0}' -f $port) + $managedServicesBasePath + '/stop-all') -Method Post -UseBasicParsing -TimeoutSec 30 | Out-Null
}

function Wait-ForReady([int]$port, [object]$state) {
    $deadline = (Get-Date).AddMinutes(4)
    $lastCheckpoint = ''
    $lastCheckpointAt = (Get-Date).AddSeconds(-30)

    while ((Get-Date) -lt $deadline) {
        if ($null -ne $state -and -not (Test-LauncherProcessIdentity $state -Quiet)) {
            throw ('O processo do core finalizou ou o PID foi reutilizado antes de ficar pronto. Veja os logs em ' + $logFile + ' e ' + $launcherLogFile)
        }

        $startup = Get-StartupSnapshot $port
        $status = if ($null -ne $startup) { [string]$startup.status } else { 'UNAVAILABLE' }
        $service = if ($null -ne $startup -and $startup.currentServiceId) { [string]$startup.currentServiceId } else { '-' }
        $checkpoint = '{0}|{1}' -f $status, $service
        $checkpointDue = $checkpoint -ne $lastCheckpoint -or ((Get-Date) - $lastCheckpointAt).TotalSeconds -ge 10

        if ($checkpointDue) {
            $percent = switch ($status) {
                'COMPLETED' { 100; break }
                'RUNNING' { 60; break }
                'FAILED' { 100; break }
                default { 45; break }
            }

            $statusMessage = if ($null -ne $startup) {
                'reconcile={0}; servico={1}' -f $status, $service
            } else {
                'endpoint startup indisponivel; motivo={0}' -f $script:lastStartupProbeError
            }

            Write-StartupProgress $percent $statusMessage
            $lastCheckpoint = $checkpoint
            $lastCheckpointAt = Get-Date
        }

        if ($null -ne $startup) {
            if ($startup.status -eq 'COMPLETED') {
                return
            }

            if ($startup.status -eq 'FAILED') {
                $message = if ($startup.errorMessage) { $startup.errorMessage } else { 'falha sem mensagem registrada' }
                $service = if ($startup.currentServiceId) { $startup.currentServiceId } else { 'servico nao identificado' }

                throw ('Reconcile do gateway falhou em {0}: {1}. Veja os logs em {2} e {3}' -f $service, $message, $logFile, $launcherLogFile)
            }
        }

        Start-Sleep -Seconds 2
    }

    throw ('Timeout aguardando ' + $managedServicesBasePath + '/startup ficar COMPLETED. Veja os logs em ' + $logFile + ' e ' + $launcherLogFile)
}

function Wait-ForAdminRuntime([int]$port, [object]$state) {
    $deadline = (Get-Date).AddMinutes(4)
    $attempt = 0
    $lastCheckpointAt = (Get-Date).AddSeconds(-30)

    while ((Get-Date) -lt $deadline) {
        if ($null -ne $state -and -not (Test-LauncherProcessIdentity $state -Quiet)) {
            throw ('O processo do core finalizou ou o PID foi reutilizado antes de responder em ' + $managedServicesBasePath + '/runtime. Veja os logs em ' + $logFile + ' e ' + $launcherLogFile)
        }

        $attempt++
        if (Test-AdminRuntime $port) {
            Write-StartupProgress 35 ('endpoint runtime respondeu apos {0} tentativa(s)' -f $attempt)
            return
        }

        if (((Get-Date) - $lastCheckpointAt).TotalSeconds -ge 10) {
            Write-StartupProgress 20 ('aguardando endpoint runtime; tentativa={0}; motivo={1}' -f $attempt, $script:lastAdminRuntimeProbeError)
            $lastCheckpointAt = Get-Date
        }

        Start-Sleep -Seconds 2
    }

    throw ('Timeout aguardando o core responder em ' + $managedServicesBasePath + '/runtime. Veja os logs em ' + $logFile + ' e ' + $launcherLogFile)
}

function Wait-ForStop([int]$port, [object]$state) {
    $deadline = (Get-Date).AddMinutes(4)
    $lastCheckpointAt = (Get-Date).AddSeconds(-30)

    while ((Get-Date) -lt $deadline) {
        $alive = $null -ne $state -and (Test-LauncherProcessIdentity $state -Quiet)
        $responding = Test-AdminRuntime $port

        if (-not $alive -and -not $responding) {
            Write-LauncherLog 'Core e endpoint administrativo encerrados.'
            return
        }

        if (((Get-Date) - $lastCheckpointAt).TotalSeconds -ge 10) {
            Write-LauncherLog ('aguardando encerramento: launcherAtivo={0}; endpointRespondendo={1}' -f $alive, $responding)
            $lastCheckpointAt = Get-Date
        }

        Start-Sleep -Seconds 2
    }

    throw ('Timeout aguardando encerramento do core. Veja o log em ' + $logFile)
}

function Wait-ForManagedServicesToStop([int]$port) {
    $deadline = (Get-Date).AddMinutes(4)

    while ((Get-Date) -lt $deadline) {
        $runtimes = Get-AdminRuntimes $port

        if ($null -eq $runtimes) {
            return
        }

        if (@($runtimes).Count -eq 0) {
            return
        }

        Start-Sleep -Seconds 2
    }

    throw 'Timeout aguardando o gateway encerrar os processos filhos.'
}

function Stop-LauncherProcessTree([long]$launcherPid) {
    if ($launcherPid -le 0) {
        return
    }

    & taskkill /PID $launcherPid /T /F | Out-Null
}

function Start-CoreApi {
    Load-DotEnv
    Resolve-LogimaruiProfile
    Assert-JavaHome

    $port = Get-CoreApiPort

    New-Item -ItemType Directory -Force -Path $logsDir, $pidsDir | Out-Null
    Write-LauncherLog ('==== inicio solicitado; perfil={0}; spring={1}; core={2}; frontend={3} ====' -f $env:LOGIMARUI_PROFILE, $env:SPRING_PROFILES_ACTIVE, $env:CORE_API_PORT, $env:FRONTEND_PORT)
    Write-StartupProgress 5 ('configuracao carregada; root={0}' -f $root)
    Write-LauncherLog ('javaHome={0}; coreDir={1}; pidFile={2}' -f $javaHome, $coreDir, $pidFile)
    Write-StartupProgress 10 ('Java validado; porta core={0}; porta frontend={1}' -f $port, $env:FRONTEND_PORT)

    $state = Read-State

    if ($state) {
        Write-LauncherLog ('Estado encontrado: pid={0}; iniciadoEm={1}; processoEsperado={2}' -f $state.launcherPid, $state.startedAt, $state.launcherProcessName)

        if (Test-LauncherProcessIdentity $state) {
            Write-LauncherLog ('Instancia ja iniciada pelo launcher com PID validado. Aguardando readiness na porta {0}...' -f $port)
            Write-StartupProgress 25 ('processo existente validado; aguardando endpoint runtime')
            Wait-ForReady -port $port -state $state
            Ensure-FrontendReady -port $port
            Write-StartupProgress 100 ('core ja estava pronto na porta {0}' -f $port)
            Complete-StartupProgress
            Write-LauncherLog ('Core API pronta na porta {0}. Log: {1}' -f $port, $logFile)
            return
        }

        Write-LauncherLog 'Estado persistido nao corresponde ao processo atual. Removendo estado obsoleto.' 'WARN'
        Remove-State
    }

    if (Test-AdminRuntime $port) {
        Write-LauncherLog ('Ja existe uma instancia respondendo na porta {0}. Aguardando apenas o reconcile do gateway.' -f $port) 'WARN'
        Write-StartupProgress 40 ('endpoint runtime ja responde; validando startup')
        Wait-ForReady -port $port -state $null
        Ensure-FrontendReady -port $port
        Write-StartupProgress 100 ('instancia existente pronta na porta {0}' -f $port)
        Complete-StartupProgress
        return
    }

    $listener = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -First 1

    if ($listener) {
        throw ('A porta {0} ja esta ocupada pelo PID {1}. Consulte o processo antes de iniciar.' -f $port, $listener.OwningProcess)
    }

    Add-Content -Path $logFile -Value ('`r`n==== start ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + ' ====`r`n') -Encoding ASCII

    $mvnw = Join-Path $coreDir 'mvnw.cmd'
    $jvmArgs = '-Dfile.encoding=UTF-8'
    $javaBin = Join-Path $javaHome 'bin'

    [Environment]::SetEnvironmentVariable('JAVA_HOME', $javaHome, 'Process')
    [Environment]::SetEnvironmentVariable('PATH', ($javaBin + ';' + $env:PATH), 'Process')

    Add-Content -Path $errorLogFile -Value ('`r`n==== start ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + ' ====`r`n') -Encoding ASCII

    $process = Start-Process `
        -FilePath $mvnw `
        -ArgumentList @(
            '-DskipTests',
            'spring-boot:run',
            ('-Dspring-boot.run.mainClass=' + $mainClass),
            ('-Dspring-boot.run.profiles=' + $env:SPRING_PROFILES_ACTIVE),
            ('-Dspring-boot.run.jvmArguments=' + $jvmArgs)
        ) `
        -WorkingDirectory $coreDir `
        -PassThru `
        -WindowStyle Hidden `
        -RedirectStandardOutput $logFile `
        -RedirectStandardError $errorLogFile

    $processSnapshot = Get-ProcessSnapshot $process.Id

    if ($null -eq $processSnapshot) {
        throw ('Nao foi possivel validar o processo do core recem-iniciado. PID={0}.' -f $process.Id)
    }

    $state = Save-State -port $port -processSnapshot $processSnapshot

    Write-LauncherLog ('Processo iniciado com launcher PID {0}, nome={1}, inicioUTC={2}. Aguardando bootstrap...' -f $process.Id, $processSnapshot.processName, $processSnapshot.startTimeUtc)
    Write-StartupProgress 25 ('processo do core iniciado; pid={0}; aguardando porta {1}' -f $process.Id, $port)

    try {
        Wait-ForAdminRuntime -port $port -state $state
        Write-LauncherLog 'Core API respondeu. Aguardando reconcile do gateway.'

        Wait-ForReady -port $port -state $state
        Ensure-FrontendReady -port $port
    } catch {
        Write-LauncherLog ('Falha durante bootstrap: {0}' -f $_.Exception.Message) 'ERROR'

        if (Test-LauncherProcessIdentity $state -Quiet) {
            Stop-LauncherProcessTree -launcherPid $process.Id
        }

        Remove-State
        throw
    }

    Write-StartupProgress 100 ('core e reconcile prontos na porta {0}' -f $port)
    Complete-StartupProgress
    Write-LauncherLog ('Core API pronta na porta {0}. Log: {1}; launcherLog: {2}' -f $port, $logFile, $launcherLogFile)
}

function Stop-CoreApi {
    Load-DotEnv
    Resolve-LogimaruiProfile

    $port = Get-CoreApiPort
    $state = Read-State
    $launcherPid = if ($state) { [long]$state.launcherPid } else { 0 }
    $managedAlive = Test-LauncherProcessIdentity $state
    $responding = Test-AdminRuntime $port

    if (-not $managedAlive -and -not $responding) {
        Remove-State
        Write-LauncherLog 'Nenhuma instancia do core esta ativa.'
        return
    }

    if (-not $managedAlive) {
        throw ('A instancia responde na porta ' + $port + ', mas o estado local nao corresponde ao processo do launcher. Nao foi encerrado nenhum processo.')
    }

    if ($responding) {
        Request-StopAll -port $port
        Write-LauncherLog 'Stop-all solicitado. Aguardando o gateway encerrar os processos filhos.'
        Wait-ForManagedServicesToStop -port $port
    }

    Stop-LauncherProcessTree -launcherPid $launcherPid

    Wait-ForStop -port $port -state $state

    Remove-State

    Complete-StartupProgress
    Write-LauncherLog 'Core API finalizada com sucesso.'
}

function Show-ResolvedProfile {
    Load-DotEnv
    Resolve-LogimaruiProfile

    [pscustomobject]@{
        logimaruiProfile = $env:LOGIMARUI_PROFILE
        springProfile    = $env:SPRING_PROFILES_ACTIVE
        coreApiPort      = [int]$env:CORE_API_PORT
        frontendPort     = [int]$env:FRONTEND_PORT
    }
}

try {
    switch ($Action) {
        'start' { Start-CoreApi }
        'stop' { Stop-CoreApi }
        'profile' { Show-ResolvedProfile }
        'restart' {
            Stop-CoreApi
            Start-CoreApi
        }
    }
} catch {
    try {
        Write-LauncherLog ('Falha fatal no lifecycle: {0}' -f $_.Exception.Message) 'ERROR'
        Complete-StartupProgress
    } catch {
        # Preserva o erro original mesmo se o log auxiliar estiver indisponivel.
    }

    throw
}
