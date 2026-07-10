@echo off
REM Builds the Unlucky Utility Mod and drops the finished jar in the repo root
REM as "Unlucky Utility Mod.jar". Just double-click, or run: build.bat
setlocal enabledelayedexpansion
cd /d "%~dp0"

echo(
echo === Building Unlucky Utility Mod ===
echo(

REM Clear old artifacts so only the current jar remains (also clears stale renames)
if exist "build\libs\*.jar" del /q "build\libs\*.jar"

call "%~dp0gradlew.bat" build
if errorlevel 1 (
	echo(
	echo *** BUILD FAILED - see the errors above. ***
	exit /b 1
)

REM Pick the built mod jar: newest .jar in build\libs that isn't the sources jar.
REM (Local builds are versioned "dev" -> unlucky-dev.jar, so don't filter on "dev"!)
set "JAR="
for /f "delims=" %%F in ('dir /b /a-d /o-d "build\libs\*.jar" 2^>nul ^| findstr /v /i "sources"') do (
	if not defined JAR set "JAR=%%F"
)

if not defined JAR (
	echo(
	echo *** Could not find a built jar in build\libs. ***
	exit /b 1
)

copy /y "build\libs\!JAR!" "Unlucky Utility Mod.jar" >nul
if errorlevel 1 (
	echo(
	echo *** Failed to copy the jar to the root folder. ***
	exit /b 1
)

echo(
echo === Done ===
echo Built:  build\libs\!JAR!
echo Output: %~dp0Unlucky Utility Mod.jar
echo(
endlocal
