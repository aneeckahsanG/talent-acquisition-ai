@echo off
REM Clears candidate data from the local H2 database (candidates, applications, screenings, interviews,
REM scorecards, offers, Resume Drop Zone items, agent history) and keeps users, job requisitions,
REM settings and AI usage. See cleanup-db.sql for the exact list.
REM
REM   cleanup-db.bat              show what would go, ask for confirmation, back up, then clean
REM   cleanup-db.bat /dry-run     only show row counts; change nothing
REM   cleanup-db.bat /y           skip the confirmation question
REM
REM The backend must be stopped first (stop.bat): H2 allows one process on the database file.
REM A copy of the database is saved to a "backups" folder next to it before anything is removed.
REM Set DB_PATH (without the .mv.db extension) to clean a different database file.

setlocal
set SCRIPTDIR=%~dp0
set ROOT=%SCRIPTDIR%..\

REM Must be the H2 version the backend uses (managed by Spring Boot); a different one can leave the file unreadable.
set H2_VERSION=2.2.224
set H2_JAR=%USERPROFILE%\.m2\repository\com\h2database\h2\%H2_VERSION%\h2-%H2_VERSION%.jar

set DRYRUN=
set ASSUMEYES=
:PARSE
if "%~1"=="" goto PARSED
if /i "%~1"=="/dry-run" set DRYRUN=1
if /i "%~1"=="/y" set ASSUMEYES=1
shift
goto PARSE
:PARSED

set DEFAULT_DB=
if not defined DB_PATH (
    set DB_PATH=%ROOT%backend\data\talentai
    set DEFAULT_DB=1
)
for %%I in ("%DB_PATH%") do set DB_PATH=%%~fI
set DB_FILE=%DB_PATH%.mv.db
set DB_URL=jdbc:h2:file:%DB_PATH:\=/%;MODE=PostgreSQL

where java >nul 2>&1
if errorlevel 1 (
    echo ERROR: "java" was not found on PATH.
    exit /b 1
)
if not exist "%H2_JAR%" (
    echo ERROR: H2 %H2_VERSION% was not found at:
    echo   %H2_JAR%
    echo Run the backend build once ^(mvn package^) so Maven downloads it, then try again.
    exit /b 1
)
if not exist "%DB_FILE%" (
    echo ERROR: no database file at %DB_FILE%
    exit /b 1
)

if defined DEFAULT_DB (
    netstat -ano | findstr /R /C:":8080 .*LISTENING" >nul 2>&1
    if not errorlevel 1 (
        echo ERROR: the backend is running on port 8080 and has the database open.
        echo Stop it first ^(scripts\stop.bat^), then run this again.
        exit /b 1
    )
)

echo Database: %DB_FILE%
echo.
REM RunScript echoes the SQL it runs; keep only the result rows (they start with "-->").
java -cp "%H2_JAR%" org.h2.tools.RunScript -url "%DB_URL%" -user sa -script "%SCRIPTDIR%count-rows.sql" -showResults | findstr /B /C:"-->"
if errorlevel 1 (
    echo.
    echo ERROR: could not open the database. If the backend is running, stop it first.
    exit /b 1
)

if defined DRYRUN (
    echo.
    echo Dry run: nothing was changed.
    exit /b 0
)

REM No parenthesised block here on purpose: inside one, %CONFIRM% would be read before the question is asked.
if defined ASSUMEYES goto CONFIRMED
echo.
set CONFIRM=
set /p CONFIRM=Remove everything marked REMOVED above? Type YES to continue:
set CONFIRM=%CONFIRM: =%
if /i "%CONFIRM%"=="YES" goto CONFIRMED
echo Cancelled. Nothing was changed.
exit /b 0
:CONFIRMED

for %%I in ("%DB_FILE%") do set BACKUP_DIR=%%~dpIbackups
if not exist "%BACKUP_DIR%" mkdir "%BACKUP_DIR%"
REM A file-name-safe timestamp from the date and time, whatever the regional format.
set NOW=%TIME: =0%
set STAMP=%DATE: =_%_%NOW:~0,8%
set STAMP=%STAMP:/=-%
set STAMP=%STAMP::=-%
set STAMP=%STAMP:.=-%
set STAMP=%STAMP:,=-%
copy /y "%DB_FILE%" "%BACKUP_DIR%\talentai-%STAMP%.mv.db" >nul
if errorlevel 1 (
    echo ERROR: could not back up the database, so nothing was removed.
    exit /b 1
)
echo Backup saved: %BACKUP_DIR%\talentai-%STAMP%.mv.db

java -cp "%H2_JAR%" org.h2.tools.RunScript -url "%DB_URL%" -user sa -script "%SCRIPTDIR%cleanup-db.sql"
if errorlevel 1 (
    echo.
    echo ERROR: the clean-up failed. The backup above is untouched; restore it by copying it back over %DB_FILE%.
    exit /b 1
)

echo.
echo Done. Counts now:
java -cp "%H2_JAR%" org.h2.tools.RunScript -url "%DB_URL%" -user sa -script "%SCRIPTDIR%count-rows.sql" -showResults | findstr /B /C:"-->"
exit /b 0
