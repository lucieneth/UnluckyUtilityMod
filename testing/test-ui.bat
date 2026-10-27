@echo off
REM ScreenSmokeTest and the ClickGUI button checks.
call "%~dp0_run.bat" ui %*
exit /b %ERRORLEVEL%
