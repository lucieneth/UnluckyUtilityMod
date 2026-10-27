@echo off
REM InputTest: keys, binds, mouse buttons, text input.
call "%~dp0_run.bat" input %*
exit /b %ERRORLEVEL%
