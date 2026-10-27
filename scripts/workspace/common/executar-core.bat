@echo off
setlocal EnableExtensions

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0logimarui-core-api-lifecycle.ps1" start
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" (
    echo.
    echo Inicializacao do Logimarui falhou. Consulte .runtime\logs\logimarui-core-api-launcher.log e .runtime\iiam\core-integrated.log
    pause
)

exit /b %EXIT_CODE%
