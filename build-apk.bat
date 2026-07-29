@echo off
setlocal EnableExtensions EnableDelayedExpansion

rem ============================================================
rem  Booxin Launcher - APK packaging script (Windows)
rem
rem  Usage:
rem    build-apk.bat           Build debug APK (for testing)
rem    build-apk.bat debug     Same as above
rem    build-apk.bat release   Build release APK
rem    build-apk.bat clean     Clean then build debug
rem ============================================================

cd /d "%~dp0"

set "BUILD_TYPE=debug"
set "DO_CLEAN=0"

if /i "%~1"=="release" set "BUILD_TYPE=release"
if /i "%~1"=="debug" set "BUILD_TYPE=debug"
if /i "%~1"=="clean" (
  set "BUILD_TYPE=debug"
  set "DO_CLEAN=1"
)
if /i "%~1"=="clean-release" (
  set "BUILD_TYPE=release"
  set "DO_CLEAN=1"
)

echo.
echo ========================================
echo   Booxin Launcher APK Builder
echo   Type: %BUILD_TYPE%
echo ========================================
echo.

if not exist "gradlew.bat" (
  echo [ERROR] gradlew.bat not found. Run this script from the project root.
  exit /b 1
)

if not exist "local.properties" (
  echo [WARN] local.properties missing.
  if exist "D:\Android\Sdk" (
    >local.properties echo sdk.dir=D:/Android/Sdk
    echo [INFO] Created local.properties pointing to D:/Android/Sdk
  ) else (
    echo [ERROR] Android SDK not found. Open project in Android Studio once.
    exit /b 1
  )
)

if "%DO_CLEAN%"=="1" (
  echo [1/3] Cleaning...
  call gradlew.bat clean --no-daemon
  if errorlevel 1 (
    echo [ERROR] Clean failed.
    exit /b 1
  )
) else (
  echo [1/3] Skip clean ^(pass "clean" to force^)
)

echo [2/3] Building %BUILD_TYPE% APK...
if /i "%BUILD_TYPE%"=="release" (
  call gradlew.bat :app:assembleRelease --no-daemon
) else (
  call gradlew.bat :app:assembleDebug --no-daemon
)
if errorlevel 1 (
  echo [ERROR] Build failed.
  exit /b 1
)

if /i "%BUILD_TYPE%"=="release" (
  set "SRC_APK=app\build\outputs\apk\release\app-release-unsigned.apk"
  if not exist "!SRC_APK!" set "SRC_APK=app\build\outputs\apk\release\app-release.apk"
) else (
  set "SRC_APK=app\build\outputs\apk\debug\app-debug.apk"
)

if not exist "!SRC_APK!" (
  echo [ERROR] APK not found: !SRC_APK!
  exit /b 1
)

set "VERSION=0.0.1"
for /f "usebackq delims=" %%A in (`powershell -NoProfile -Command "(Get-Content 'app\build.gradle.kts' -Raw) -match 'versionName\s*=\s*\"([^\"]+)\"' | Out-Null; $Matches[1]"`) do set "VERSION=%%A"
if "!VERSION!"=="" set "VERSION=0.0.1"

for /f %%I in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set "STAMP=%%I"

if not exist "dist" mkdir dist

set "OUT_NAME=BooxinLauncher-!VERSION!-%BUILD_TYPE%-!STAMP!.apk"
set "OUT_APK=dist\!OUT_NAME!"
set "OUT_LATEST=dist\BooxinLauncher-%BUILD_TYPE%-latest.apk"

copy /Y "!SRC_APK!" "!OUT_APK!" >nul
copy /Y "!SRC_APK!" "!OUT_LATEST!" >nul

for %%F in ("!OUT_APK!") do set "SIZE=%%~zF"
set /a SIZE_KB=!SIZE!/1024

echo.
echo [3/3] Done.
echo ----------------------------------------
echo   Version : !VERSION!
echo   Source  : !SRC_APK!
echo   Output  : !OUT_APK!
echo   Latest  : !OUT_LATEST!
echo   Size    : !SIZE_KB! KB
echo ----------------------------------------
echo.

explorer.exe "dist"
endlocal
exit /b 0
