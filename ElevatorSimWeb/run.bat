@echo off
setlocal
cd /d "%~dp0"
title Elevator Simulator (Java engine + browser)

where java >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Java was not found. Install a JDK 8 or newer from https://adoptium.net and run this file again.
  pause & exit /b 1
)
where javac >nul 2>nul
if errorlevel 1 (
  echo [ERROR] "javac" was not found. You have a JRE, but you need a JDK (the compiler). Install a JDK from https://adoptium.net
  pause & exit /b 1
)

if not exist out mkdir out
echo Compiling Java source...
javac -encoding UTF-8 -d out src\*.java
if errorlevel 1 (
  echo [ERROR] Compilation failed. Read the messages above, fix the .java file, and run again.
  pause & exit /b 1
)

echo Starting server. Your browser opens by itself. If it does not, open the address printed below.
echo Close this window (or press Ctrl+C) to stop the simulator.
echo.
java -cp out Main
if errorlevel 1 (
  echo [ERROR] The program stopped with an error. If it says the port is in use, change "port" in config.txt.
)
pause
