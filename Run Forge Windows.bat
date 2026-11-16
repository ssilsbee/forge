@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\run-forge-windows.ps1"
if errorlevel 1 (
  echo Forge could not start. See the message above.
  pause
  exit /b 1
)
endlocal
