@echo off
setlocal
rem Builds (incrementally) and launches the Microbot client without IntelliJ.
rem   Run Microbot.bat          -> rebuild if sources changed, then launch
rem   Run Microbot.bat nobuild  -> launch the last built jar immediately

cd /d "%~dp0"

rem Prefer JAVA_HOME's JDK (Gradle needs 17-21); fall back to java on PATH.
set "JAVAW=javaw"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javaw.exe" set "JAVAW=%JAVA_HOME%\bin\javaw.exe"

if /i not "%~1"=="nobuild" (
    echo Building Microbot client...
    call gradlew.bat :client:shadowJar --console=plain -q
    if errorlevel 1 (
        echo.
        echo Build failed. See errors above.
        pause
        exit /b 1
    )
)

set "JAR="
rem Pick the most recently built jar (dir /o:d sorts oldest -> newest).
for /f "delims=" %%F in ('dir /b /o:d "runelite-client\build\libs\client-*-shaded.jar" 2^>nul') do set "JAR=%~dp0runelite-client\build\libs\%%F"
if not defined JAR (
    echo No shaded jar found in runelite-client\build\libs. Run without "nobuild" first.
    pause
    exit /b 1
)

echo Launching %JAR%
start "Microbot" "%JAVAW%" -Dfile.encoding=UTF-8 -jar "%JAR%"
endlocal
