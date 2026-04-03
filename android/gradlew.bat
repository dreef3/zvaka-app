@ECHO OFF
SET DIRNAME=%~dp0
IF "%DIRNAME%"=="" SET DIRNAME=.
SET APP_HOME=%DIRNAME%

IF NOT "%JAVA_HOME%"=="" GOTO findJavaFromJavaHome
SET JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
IF %ERRORLEVEL% EQU 0 GOTO execute
ECHO java.exe not found in PATH
EXIT /B 1

:findJavaFromJavaHome
SET JAVA_EXE=%JAVA_HOME%/bin/java.exe
IF EXIST "%JAVA_EXE%" GOTO execute
ECHO JAVA_HOME is set to an invalid directory: %JAVA_HOME%
EXIT /B 1

:execute
"%JAVA_EXE%" %JAVA_OPTS% %GRADLE_OPTS% -Dorg.gradle.appname=gradlew -classpath "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
