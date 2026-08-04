@echo off
setlocal EnableExtensions
cd /d "%~dp0"
title DevCollab Local Launcher

if not exist "tools\local-demo.ps1" (
    echo [launcher] Missing tools\local-demo.ps1
    goto :failed
)

where powershell.exe >nul 2>nul
if errorlevel 1 (
    echo [launcher] PowerShell is not available.
    goto :failed
)

echo [launcher] Starting DevCollab. The first run may take several minutes.
echo [launcher] Keep this window open until startup finishes.
echo.

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%CD%\tools\local-demo.ps1" start -WithObservability -VerifyAfterStart
if errorlevel 1 goto :failed

echo.
echo [launcher] DevCollab is ready: http://localhost:8088
if not "%DEVCOLLAB_LAUNCHER_NO_OPEN%"=="1" start "" "http://localhost:8088"
goto :finished

:failed
echo.
echo [launcher] Startup failed.
echo [launcher] Check logs\local-demo or run: tools\local-demo.ps1 status
if not "%DEVCOLLAB_LAUNCHER_NO_PAUSE%"=="1" pause
exit /b 1

:finished
if not "%DEVCOLLAB_LAUNCHER_NO_PAUSE%"=="1" pause
exit /b 0
