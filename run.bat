@echo off
setlocal

set "TARGET_DIR=%~dp0target"

if not exist "%TARGET_DIR%" (
    echo target folder not found: %TARGET_DIR%
    exit /b 1
)

set "LATEST="
for /f "delims=" %%i in ('dir /b /a-d /o-d "%TARGET_DIR%\com-file-sync-*.jar" 2^>nul') do (
    set "LATEST=%%i"
    goto :runjar
)

echo no JAR found matching com-file-sync-*.jar in %TARGET_DIR%
exit /b 1

:runjar
echo using JAR: %LATEST%
java -jar "%TARGET_DIR%\%LATEST%"
endlocal
