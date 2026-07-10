@echo off
title Unlucky Client - dev launcher
cd /d "%~dp0"
echo Launching Unlucky Client (Minecraft 26.2)...
call gradlew.bat runClient
if errorlevel 1 (
	echo.
	echo The client exited with an error. Check the output above.
	pause
)
