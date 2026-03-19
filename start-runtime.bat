@echo off
setlocal

set "ROOT=%~dp0"
cd /d "%ROOT%"

set "TARGET=%~1"
if /I "%TARGET%"=="" set "TARGET=fabric"

if /I "%TARGET%"=="fabric" goto run_fabric
if /I "%TARGET%"=="forge" goto run_forge

echo Invalid runtime target: %TARGET%
echo Usage: start-runtime.bat [fabric^|forge]
exit /b 1

:run_fabric
echo Starting Fabric dev runtime...
call "%ROOT%gradlew.bat" :fabric:runClient
exit /b %errorlevel%

:run_forge
echo Starting Forge dev runtime...
call "%ROOT%gradlew.bat" :forge:runClient
exit /b %errorlevel%
