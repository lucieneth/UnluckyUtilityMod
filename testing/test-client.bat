@echo off
REM Only the client modules, and the contracts that cover them.
call "%~dp0_run.bat" client %*
exit /b %ERRORLEVEL%
