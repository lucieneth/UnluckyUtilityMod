@echo off
REM Every gametest - the full suite, same as a plain runClientGameTest.
call "%~dp0_run.bat" all %*
exit /b %ERRORLEVEL%
