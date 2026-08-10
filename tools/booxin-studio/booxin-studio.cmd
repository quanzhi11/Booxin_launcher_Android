@echo off
setlocal
set "ROOT=%~dp0"
where node >nul 2>nul
if errorlevel 1 (
  echo [Booxin Studio] node not found. Install Node.js 18+ and add it to PATH.
  echo https://nodejs.org/
  exit /b 1
)
node "%ROOT%src\cli.js" %*
set "ERR=%ERRORLEVEL%"
endlocal & exit /b %ERR%
