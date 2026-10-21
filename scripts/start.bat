@echo off
REM Starts backend (Spring Boot, port 8080) and frontend (Vite, port 5173)
REM in separate minimized windows. Logs are written to this script's folder
REM and are cleared at the start of every run. If a target port is already
REM in use, the process holding it is killed first. Use stop.bat to stop both.

setlocal
set SCRIPTDIR=%~dp0
set ROOT=%SCRIPTDIR%..\
cd /d "%ROOT%"

where mvn >nul 2>&1
if errorlevel 1 (
    echo ERROR: "mvn" was not found on PATH. Install Maven or open a shell where it is available.
    exit /b 1
)
where npm >nul 2>&1
if errorlevel 1 (
    echo ERROR: "npm" was not found on PATH. Install Node.js or open a shell where it is available.
    exit /b 1
)

del /q "%SCRIPTDIR%backend.log" >nul 2>&1
del /q "%SCRIPTDIR%frontend.log" >nul 2>&1

call :FREE_PORT 8080
call :FREE_PORT 5173

echo Starting backend (Spring Boot on :8080)...
start "talent-acquisition-backend" /min cmd /c "cd /d "%ROOT%backend" && mvn spring-boot:run > "%SCRIPTDIR%backend.log" 2>&1"

echo Starting frontend (Vite on :5173)...
start "talent-acquisition-frontend" /min cmd /c "cd /d "%ROOT%frontend" && npm run dev > "%SCRIPTDIR%frontend.log" 2>&1"

echo.
echo Backend:  http://localhost:8080  (log: %SCRIPTDIR%backend.log)
echo Frontend: http://localhost:5173  (log: %SCRIPTDIR%frontend.log)
echo Use stop.bat to stop both.
endlocal
exit /b 0

:FREE_PORT
set PORT=%~1
set FOUND_PID=
for /f "tokens=5" %%p in ('netstat -ano 2^>nul ^| findstr ":%PORT%" ^| findstr "LISTENING"') do (
    set FOUND_PID=%%p
)
if defined FOUND_PID (
    echo Port %PORT% is in use by PID %FOUND_PID% - stopping it...
    taskkill /PID %FOUND_PID% /F >nul 2>&1
)
exit /b 0
