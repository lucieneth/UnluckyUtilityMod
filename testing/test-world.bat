@echo off
REM Only the world modules, and the contracts that cover them.
call "%~dp0_run.bat" world %*
exit /b %ERRORLEVEL%
