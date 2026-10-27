@echo off
REM Compiles the client and the gametests without launching anything - the fast check.
setlocal
cd /d "%~dp0.."
REM Full path: a shell that sets NoDefaultCurrentDirectoryInExePath (Git Bash does) won't find it bare.
call "%CD%\gradlew.bat" compileClientJava compileGametestJava
set "CODE=%ERRORLEVEL%"
echo(
if "%CODE%"=="0" (echo === PASS: compile ===) else (echo === FAIL: compile ^(exit %CODE%^) ===)
endlocal & exit /b %CODE%
