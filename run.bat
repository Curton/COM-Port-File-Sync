@echo off
setlocal enabledelayedexpansion

set "TARGET_DIR=%~dp0target"

if not exist "%TARGET_DIR%" (
    echo target folder not found: %TARGET_DIR%
    pause
    exit /b 1
)

set "LATEST="
set /a COUNT=0
for /f "delims=" %%i in ('dir /b /a-d /o-d "%TARGET_DIR%\com-file-sync-*.jar" 2^>nul') do (
    set /a COUNT+=1
    set "JAR!COUNT!=%%i"
)

if !COUNT! equ 0 (
    echo no JAR found matching com-file-sync-*.jar in %TARGET_DIR%
    pause
    exit /b 1
)

if !COUNT! equ 1 (
    set "LATEST=!JAR1!"
    goto :runjar
)

echo.
echo multiple JARs found:
for /L %%n in (1,1,!COUNT!) do (
    call echo   %%n^) %%JAR%%n%%
)
echo.

:promptChoice
set "CHOICE="
set /p "CHOICE=Select JAR to run (1-!COUNT!): "
if "!CHOICE!"=="" goto :promptChoice
if !CHOICE! lss 1 goto :invalidChoice
if !CHOICE! gtr !COUNT! goto :invalidChoice
call set "LATEST=%%JAR!CHOICE!%%"
if defined LATEST goto :runjar

:invalidChoice
echo Invalid selection.
goto :promptChoice

:runjar
echo using JAR: !LATEST!
java -jar "%TARGET_DIR%\!LATEST!"
echo.
echo JAR exited with code: !errorlevel!
pause
endlocal
