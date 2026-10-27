@echo off
REM Only the render modules, and the contracts that cover them.
call "%~dp0_run.bat" render %*
exit /b %ERRORLEVEL%
