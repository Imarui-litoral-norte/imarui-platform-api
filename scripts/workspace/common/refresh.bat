@echo off
setlocal EnableExtensions

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0logimarui-core-api-lifecycle.ps1" restart
set "EXIT_CODE=%ERRORLEVEL%"
if not "%EXIT_CODE%"=="0" (
    echo Falha ao reiniciar. Consulte .runtime\logs\logimarui-core-api-launcher.log e .runtime\iiam\core-integrated.log
    pause
)
exit /b %EXIT_CODE%
