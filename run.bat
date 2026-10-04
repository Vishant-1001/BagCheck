@echo off
REM Baggage Journey Integrity Checker - Windows driver (build / test / serve / benchmark).
REM No Maven, no Gradle, no dependencies: a JDK with javac + java is enough.
setlocal
cd /d "%~dp0"

set "JAVAC=javac"
set "JAVA=java"
for /d %%D in (.tools\jdk*) do (
  if exist "%%~fD\bin\javac.exe" (
    set "JAVAC=%%~fD\bin\javac.exe"
    set "JAVA=%%~fD\bin\java.exe"
  )
)

set "ACTION=%1"
if "%ACTION%"=="" set "ACTION=serve"

if exist build rmdir /s /q build
mkdir build
dir /s /b backend\src\*.java > build\sources.txt
"%JAVAC%" -d build @build\sources.txt
if errorlevel 1 goto fail

if exist build-tests rmdir /s /q build-tests
mkdir build-tests
dir /s /b backend\tests\*.java > build\testsources.txt
"%JAVAC%" -cp build -d build-tests @build\testsources.txt
if errorlevel 1 goto fail
echo Build OK

if /i "%ACTION%"=="build" goto done
if /i "%ACTION%"=="test" goto tests
if /i "%ACTION%"=="benchmark" goto benchmark
if /i "%ACTION%"=="serve" goto serve

:serve
if "%2"=="" (
  "%JAVA%" -cp build api.ApiServer 8080 data frontend
) else (
  "%JAVA%" -cp build api.ApiServer %2 data frontend
)
goto done

:tests
"%JAVA%" -cp "build;build-tests" tests.TestRunner
goto done

:benchmark
if "%2"=="" (
  "%JAVA%" -cp build benchmark.Benchmark
) else (
  "%JAVA%" -cp build benchmark.Benchmark %2
)
goto done

:fail
echo BUILD FAILED
exit /b 1

:done
endlocal
