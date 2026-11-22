@echo off
setlocal
title Raahat VPN - iOS Signing Setup
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0prepare-ios-signing.ps1"
if errorlevel 1 (
  echo.
  echo Preparation did not complete.
)
echo.
pause
endlocal
