@echo off
setlocal EnableExtensions

echo Atualizando o frontend e reiniciando o Logimarui PROD...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0logimarui-core-api-lifecycle.ps1" restart
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" (
    echo.
    echo Atualizacao falhou. Consulte .runtime\logs\logimarui-core-api-launcher.log
    pause
) else (
    echo Logimarui PROD atualizado e pronto.
)
exit /b %EXIT_CODE%
