@echo off
rem Build (no clean - repo convention) and launch the serial link lab.
rem Usage: run.bat peer --port COM11 [options]
rem        run.bat bridge --ports COM11,COM14 [options]
rem        run.bat selftest [wire options]
setlocal
set "JAVA_HOME_OVERRIDE=C:/Users/liuke/.jdks/corretto-21.0.9"
set "MAVEN=C:/Users/liuke/scoop/apps/maven/current/bin/mvn.cmd"

cd /d "%~dp0"
if not exist target mkdir target

echo [link-lab] building...
JAVA_HOME="%JAVA_HOME_OVERRIDE%" "%MAVEN%" -o -q package -DskipTests
if errorlevel 1 (
    echo [link-lab] build failed
    exit /b 1
)

if not exist "target\link-lab-1.0.0.jar" (
    echo [link-lab] expected jar missing - check target/
    exit /b 1
)

echo [link-lab] starting %*
"%JAVA_HOME_OVERRIDE%\bin\java.exe" -jar "target\link-lab-1.0.0.jar" %*
endlocal
