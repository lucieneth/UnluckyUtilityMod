@echo off
REM Visibility metadata and the panic sweep.
call "%~dp0_run.bat" panic %*
exit /b %ERRORLEVEL%
