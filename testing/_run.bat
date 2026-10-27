@echo off
REM Shared runner for the testing\test-*.bat wrappers. Not meant to be run directly.
REM   _run.bat <filter> [extra gradle args...]
REM Runs the client gametests with -Dunlucky.test=<filter> (see testing\README.md),
REM tees the output to build\test-<filter>.log and prints PASS or FAIL.
setlocal enabledelayedexpansion
set "HERE=%~dp0"
cd /d "%HERE%.."

set "FILTER=%~1"
if "%FILTER%"=="" set "FILTER=all"
shift
set "EXTRA="
:args
if "%~1"=="" goto run
set "EXTRA=!EXTRA! %1"
shift
goto args

:run
set "LOGNAME=%FILTER::=-%"
set "LOGNAME=%LOGNAME:,=+%"
set "LOGNAME=%LOGNAME: =_%"
set "LOG=build\test-%LOGNAME%.log"
if not exist build mkdir build
title Unlucky Client - gametest %FILTER%

echo(
echo === Gametest: %FILTER% !EXTRA! ===
echo Log: %LOG%
echo(
REM Quoted for PowerShell, which would otherwise split -Dunlucky.test at the dot. Tee-Object
REM writes UTF-16 on Windows PowerShell, so the log is rewritten as UTF-8 for grep afterwards.
powershell -NoProfile -Command "& '%CD%\gradlew.bat' runClientGameTest '-Dunlucky.test=%FILTER%' !EXTRA! --console=plain 2>&1 | Tee-Object -FilePath '%LOG%'; $code = $LASTEXITCODE; (Get-Content '%LOG%') | Set-Content -Encoding utf8 '%LOG%'; exit $code"
set "CODE=%ERRORLEVEL%"

echo(
if "%CODE%"=="0" (
	echo === PASS: %FILTER% ===
) else (
	echo === FAIL: %FILTER% ^(exit %CODE%^) ===
	echo Crash reports: build\run\clientGameTest\crash-reports
)
echo Log: %LOG%
echo(

REM Pause only when double-clicked, where the window would otherwise close on the result.
REM Explorer runs it as cmd /c "<full path>", so the path shows up in the command line.
set "CL=!cmdcmdline!"
if not defined NOPAUSE if /i not "!CL:\testing\=!"=="!CL!" pause
endlocal & exit /b %CODE%
