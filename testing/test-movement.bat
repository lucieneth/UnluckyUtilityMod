@echo off
REM Only the movement modules, and the contracts that cover them.
call "%~dp0_run.bat" movement %*
exit /b %ERRORLEVEL%
