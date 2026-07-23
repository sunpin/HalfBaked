@rem
@rem  Gradle start up script for Windows
@rem
@if "%DEBUG%"=="" @echo off
@if "%DEFAULT_JVM_OPTS%"=="" set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set CMD_LINE_ARGS=%*

set GRADLE_WRAPPER_JAR="%DIRNAME%gradle\wrapper\gradle-wrapper.jar"

if not exist %GRADLE_WRAPPER_JAR% (
    echo Error: Could not find gradle-wrapper.jar
    exit /b 1
)

"%JAVA_HOME%\bin\java.exe" %DEFAULT_JVM_OPTS% -jar %GRADLE_WRAPPER_JAR% %CMD_LINE_ARGS%
