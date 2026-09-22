@echo off
cd /d "%~dp0"
where java >nul 2>nul
if errorlevel 1 (
 echo Install Java 21 or newer, then run this file again.
 pause
 exit /b 1
)
copy /b "release\ZERO_ECLIPSE_V9.jar.part0"+"release\ZERO_ECLIPSE_V9.jar.part1" "ZERO_ECLIPSE_V9.jar" >nul
if errorlevel 1 (
 echo Missing release files. Extract the entire repository ZIP first.
 pause
 exit /b 1
)
java -Dsun.java2d.uiScale=1.0 -jar "ZERO_ECLIPSE_V9.jar"
pause
