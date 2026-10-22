@echo off
REM Runs the client gametests in both legs CI runs (vanilla, then with Sodium) and
REM writes each to its own log in build\, so the result can be handed back without
REM copying the console. Just double-click, or run: tester.bat
REM   tester.bat vanilla   - only the vanilla leg
REM   tester.bat sodium    - only the Sodium leg
setlocal enabledelayedexpansion
cd /d "%~dp0"
title Unlucky Client - gametests

set "LEGS=%~1"
if "%LEGS%"=="" set "LEGS=vanilla sodium"
if not exist build mkdir build

set "SUMMARY="
set "FAILED=0"
for %%L in (%LEGS%) do (
	set "FLAGS="
	if /i "%%L"=="sodium" set "FLAGS=-PwithSodium"
	set "LOG=build\tester-%%L.log"
	echo(
	echo === Gametest: %%L !FLAGS! ===
	echo Log: !LOG!
	echo(
	REM Tee through PowerShell so the run is both visible and saved.
	powershell -NoProfile -Command "& '%~dp0gradlew.bat' runClientGameTest !FLAGS! --console=plain 2>&1 | Tee-Object -FilePath '!LOG!'; exit $LASTEXITCODE"
	if errorlevel 1 (
		set "SUMMARY=!SUMMARY! %%L=FAILED"
		set "FAILED=1"
	) else (
		set "SUMMARY=!SUMMARY! %%L=passed"
	)
)

echo(
echo === Result:%SUMMARY% ===
echo Logs: build\tester-*.log  (crash reports: build\run\clientGameTest\crash-reports)
echo(
pause
endlocal & exit /b %FAILED%
