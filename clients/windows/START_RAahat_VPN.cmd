@echo off
setlocal
set "APP=%~dp0RaahatVPN.exe"
if not exist "%APP%" (
  echo Raahat VPN executable was not found.
  echo Please extract the complete ZIP file before running this launcher.
  pause
  exit /b 1
)
start "" "%APP%"
endlocal
