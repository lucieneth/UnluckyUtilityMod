@echo off
REM One module by name, ignoring case:  test-module.bat AutoTrap
REM Asks for the name when none is given. Extra arguments go to Gradle.
setlocal
REM Before any shift, which moves %0 and with it %~dp0.
set "HERE=%~dp0"
set "NAME=%~1"
if "%NAME%"=="" set /p "NAME=Module name: "
if "%NAME%"=="" (
	echo No module name given.
	exit /b 2
)
shift
set "EXTRA="
:args
if "%~1"=="" goto run
set "EXTRA=%EXTRA% %1"
shift
goto args
:run
call "%HERE%_run.bat" "module:%NAME%" %EXTRA%
endlocal & exit /b %ERRORLEVEL%
