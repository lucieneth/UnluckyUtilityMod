@echo off
title Unlucky Client - local registry
cd /d "%~dp0"

REM The client normally talks to https://api.unlucky.life. This points it at a
REM worker running on your own machine instead, so you can break things without
REM breaking them for everyone.
REM
REM Start the worker FIRST, in a second terminal:
REM
REM     cd server
REM     npx wrangler dev
REM
REM (wrangler dev simulates KV on disk, so no Cloudflare account is needed for this.)
set UNLUCKY_API=http://127.0.0.1:8787

echo Client will use the registry at %UNLUCKY_API%
echo.
call gradlew.bat runClient
if errorlevel 1 (
	echo.
	echo The client exited with an error. Check the output above.
	pause
)
