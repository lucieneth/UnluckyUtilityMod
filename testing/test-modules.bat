@echo off
REM Every module and the module-wide passes; no screens or input.
call "%~dp0_run.bat" modules %*
exit /b %ERRORLEVEL%
