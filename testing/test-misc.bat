@echo off
REM Only the misc modules, and the contracts that cover them.
call "%~dp0_run.bat" misc %*
exit /b %ERRORLEVEL%
