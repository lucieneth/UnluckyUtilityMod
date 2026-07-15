@echo off
title Unlucky Client - perf profiler
cd /d "%~dp0"

REM Same as run.bat, but with the per-module profiler on. Once a second the client
REM logs one [Perf] line with avg/max ms for every module tick, ESP overlay and HUD
REM widget. The environment variable (rather than -D) is deliberate: it survives the
REM gradle daemon, which a JVM flag on the gradle command line would not.
set UNLUCKY_PERF_DEBUG=true

echo Launching Unlucky Client with the profiler enabled...
echo.
echo   Stand where the game stutters for ~15 seconds, then quit.
echo   The report is written to run\logs\latest.log - lines starting with [Perf].
echo.
call gradlew.bat runClient
if errorlevel 1 (
	echo.
	echo The client exited with an error. Check the output above.
	pause
	exit /b 1
)

echo.
echo ==================== worst [Perf] samples ====================
powershell -NoProfile -Command "if (Test-Path 'run\logs\latest.log') { Select-String -Path 'run\logs\latest.log' -Pattern '\[Perf\]' | Select-Object -Last 20 | ForEach-Object { $_.Line } } else { 'No log found at run\logs\latest.log' }"
echo =============================================================
echo.
echo Full log: run\logs\latest.log
pause
