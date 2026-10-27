@echo off
REM Only the player modules, and the contracts that cover them.
call "%~dp0_run.bat" player %*
exit /b %ERRORLEVEL%
