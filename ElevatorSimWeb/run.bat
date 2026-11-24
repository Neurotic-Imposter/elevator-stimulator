
@echo off
setlocal
cd /d "%~dp0"

title Elevator Simulator

echo ==========================================
echo       JAVA ELEVATOR SIMULATOR
echo ==========================================
echo.

where java >nul 2>nul
if errorlevel 1 goto NOJAVA

where javac >nul 2>nul
if errorlevel 1 goto NOJAVAC

if not exist out mkdir out

echo [1/2] Compiling Java files...
javac -encoding UTF-8 -d out src\*.java

if errorlevel 1 goto COMPILEERROR

echo.
echo [2/2] Starting Elevator Simulator...
echo.
echo Open http://localhost:8080 in your browser.
echo Keep this window open while using the application.
echo Press Ctrl+C to stop the server.
echo.

java -cp out Main

if errorlevel 1 goto RUNERROR

goto END

:NOJAVA
echo ERROR: Java was not found.
echo Install a JDK from https://adoptium.net
goto FAILED

:NOJAVAC
echo ERROR: Java compiler javac was not found.
echo Install a JDK, not just a JRE.
goto FAILED

:COMPILEERROR
echo ERROR: Java compilation failed.
goto FAILED

:RUNERROR
echo ERROR: The application stopped unexpectedly.
echo Check the port and configuration.
goto FAILED

:FAILED
pause
exit /b 1

:END
pause
