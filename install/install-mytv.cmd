@echo off
rem Double-click to install MyTV on an Android TV. Uses install-mytv.ps1 next to this file, or downloads it.
set "PS1=%~dp0install-mytv.ps1"
if not exist "%PS1%" (
  set "PS1=%TEMP%\install-mytv.ps1"
  powershell -NoProfile -Command "Invoke-WebRequest -UseBasicParsing -Uri https://github.com/iResultMan/MyTV-launcher/releases/latest/download/install-mytv.ps1 -OutFile $env:TEMP\install-mytv.ps1"
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%PS1%" %*
pause
