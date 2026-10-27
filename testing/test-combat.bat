@echo off
REM Only the combat modules, and the contracts that cover them.
call "%~dp0_run.bat" combat %*
exit /b %ERRORLEVEL%
