@echo off
setlocal EnableDelayedExpansion
cd /d "%~dp0"

echo ============================================
echo  Recording Mod - setup and launch
echo ============================================
echo.
echo This will:
echo   1. Install Java 21 (Eclipse Temurin) if it's not already present
echo   2. Install ffmpeg if it's not already present
echo   3. Build and launch the modded Minecraft client
echo.

where winget >nul 2>&1
if errorlevel 1 (
    echo [ERROR] winget was not found on this system.
    echo Please install Java 21+ and ffmpeg yourself, then run gradlew.bat runClient manually.
    goto :fail
)

call :ensure_java
if not defined JAVA_BIN_DIR goto :fail

call :ensure_ffmpeg

set "PATH=%JAVA_BIN_DIR%;%FFMPEG_BIN_DIR%;%PATH%"
for %%I in ("%JAVA_BIN_DIR%\..") do set "JAVA_HOME=%%~fI"

echo.
echo Using Java:
"%JAVA_BIN_DIR%\java.exe" -version
echo.
if defined FFMPEG_BIN_DIR (
    echo Using ffmpeg from: %FFMPEG_BIN_DIR%
) else (
    echo [WARNING] ffmpeg was not found or could not be installed automatically.
    echo Video export won't work until you install ffmpeg yourself and set the
    echo "Ffmpeg Path" setting in the mod's in-game settings menu.
)

echo.
echo ============================================
echo  Building and launching the game
echo  (the first run downloads Minecraft assets - this can take several minutes)
echo ============================================
echo.
call ".\gradlew.bat" runClient
goto :end

REM ---------------------------------------------------------------
REM Finds an installed Temurin JDK 21+, installing one via winget if
REM none is found. Sets JAVA_BIN_DIR to its bin folder.
REM ---------------------------------------------------------------
:ensure_java
set "JAVA_BIN_DIR="

for /f "delims=" %%D in ('dir /b /ad "C:\Program Files\Eclipse Adoptium" 2^>nul ^| findstr /r /i "jdk-2[1-9]"') do (
    set "JAVA_BIN_DIR=C:\Program Files\Eclipse Adoptium\%%D\bin"
)
if defined JAVA_BIN_DIR exit /b

echo Installing Java 21 (Eclipse Temurin) via winget - this may take a minute...
winget install --id EclipseAdoptium.Temurin.21.JDK -e --accept-source-agreements --accept-package-agreements --silent

for /f "delims=" %%D in ('dir /b /ad "C:\Program Files\Eclipse Adoptium" 2^>nul ^| findstr /r /i "jdk-2[1-9]"') do (
    set "JAVA_BIN_DIR=C:\Program Files\Eclipse Adoptium\%%D\bin"
)
if not defined JAVA_BIN_DIR (
    echo [ERROR] Could not find or install a Java 21+ JDK. Please install one manually.
)
exit /b

REM ---------------------------------------------------------------
REM Finds ffmpeg (on PATH, or a previous winget install), installing
REM it via winget if none is found. Sets FFMPEG_BIN_DIR to its bin
REM folder (left empty if it still couldn't be found).
REM ---------------------------------------------------------------
:ensure_ffmpeg
set "FFMPEG_BIN_DIR="

where ffmpeg >nul 2>&1
if not errorlevel 1 (
    for /f "delims=" %%P in ('where ffmpeg') do set "FFMPEG_BIN_DIR=%%~dpP"
    exit /b
)

for /f "delims=" %%F in ('dir /b /s "%LOCALAPPDATA%\Microsoft\WinGet\Packages\ffmpeg.exe" 2^>nul') do (
    set "FFMPEG_BIN_DIR=%%~dpF"
)
if defined FFMPEG_BIN_DIR exit /b

echo Installing ffmpeg via winget - this may take a minute...
winget install --id Gyan.FFmpeg -e --accept-source-agreements --accept-package-agreements --silent

for /f "delims=" %%F in ('dir /b /s "%LOCALAPPDATA%\Microsoft\WinGet\Packages\ffmpeg.exe" 2^>nul') do (
    set "FFMPEG_BIN_DIR=%%~dpF"
)
exit /b

:fail
echo.
echo Setup failed - see the messages above.
pause
exit /b 1

:end
echo.
echo Done. If the game window closed on its own, check the output above for errors.
pause
