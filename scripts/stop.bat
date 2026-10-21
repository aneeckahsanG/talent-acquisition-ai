@echo off
REM Stops the backend and frontend dev processes started by start.bat.

echo Stopping backend...
taskkill /FI "WINDOWTITLE eq talent-acquisition-backend" /T /F >nul 2>&1

echo Stopping frontend...
taskkill /FI "WINDOWTITLE eq talent-acquisition-frontend" /T /F >nul 2>&1

REM Fallback: free the ports in case the window-title match missed a child process
REM (e.g. mvn/npm detached from the cmd window that owned the title)
for /f "tokens=5" %%p in ('netstat -ano 2^>nul ^| findstr ":8080" ^| findstr "LISTENING"') do taskkill /PID %%p /F >nul 2>&1
for /f "tokens=5" %%p in ('netstat -ano 2^>nul ^| findstr ":5173" ^| findstr "LISTENING"') do taskkill /PID %%p /F >nul 2>&1

echo Done.
