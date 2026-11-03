@echo off
setlocal EnableExtensions
powershell -NoProfile -ExecutionPolicy Bypass -Command "$p=New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent()); if($p.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)){exit 0}else{exit 1}"
if errorlevel 1 (
  echo O atualizador IIAM requer administrador. Confirme a solicitacao do Windows.
  powershell -NoProfile -ExecutionPolicy Bypass -Command "try {$p=Start-Process -FilePath '%ComSpec%' -ArgumentList '/d /c ""%~f0""' -Verb RunAs -Wait -PassThru; exit $p.ExitCode} catch {exit 1}"
  exit /b
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0logimarui-core-api\scripts\update-iiam-prod.ps1"
set "IIAM_EXIT=%ERRORLEVEL%"
echo.
if not "%IIAM_EXIT%"=="0" echo Atualizacao falhou. Verifique a mensagem e os logs em .runtime\iiam.
pause
exit /b %IIAM_EXIT%
