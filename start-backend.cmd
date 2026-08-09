@echo off
setlocal
cd /d "%~dp0"
echo [Hanaki Mall] Starting the Spring Boot backend at http://localhost:8080 ...
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-backend.ps1"
exit /b %ERRORLEVEL%
