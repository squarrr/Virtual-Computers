@echo off
setlocal enabledelayedexpansion
rem Runs the level-of-detail arithmetic tests. No Minecraft, no Gradle daemon, about a second.
rem
rem The classes under test import nothing from Minecraft on purpose, so they compile against JOML
rem alone - which is what makes the numbers that decide everyone's bandwidth checkable in a second
rem rather than by launching a game and squinting at a screen.

cd /d "%~dp0"

rem Find a JDK the same way the other scripts do: JAVA_HOME, then PATH, then the usual install.
set "JAVAC="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "JAVAC=%JAVA_HOME%\bin\javac.exe"
if not defined JAVAC for %%J in (javac.exe) do if not "%%~$PATH:J"=="" set "JAVAC=%%~$PATH:J"
if not defined JAVAC for /d %%D in ("%ProgramFiles%\Microsoft\jdk-*") do set "JAVAC=%%D\bin\javac.exe"
if not defined JAVAC (
  echo Could not find a JDK. Install one:  winget install Microsoft.OpenJDK.25
  exit /b 1
)
for %%F in ("%JAVAC%") do set "JBIN=%%~dpF"

rem JOML comes from the Gradle cache, which is already populated by any build of the mod.
rem No wildcards in the middle of the path: dir accepts them only in the final component,
rem and with one earlier it matches nothing and reports the cache as empty. /s recurses anyway.
set "JOML="
for /f "delims=" %%F in ('dir /b /s "%USERPROFILE%\.gradle\caches\modules-2\files-2.1\org.joml\joml\joml-*.jar" 2^>nul ^| findstr /v sources') do set "JOML=%%F"
if not defined JOML (
  echo Could not find joml in the Gradle cache. Run  gradlew build  once first.
  exit /b 1
)

if exist build\lodtest rmdir /s /q build\lodtest
mkdir build\lodtest

"%JBIN%javac.exe" -d build\lodtest -cp "%JOML%" ^
  src\main\java\squarrr\virtualcomputers\lod\Rung.java ^
  src\main\java\squarrr\virtualcomputers\lod\LodState.java ^
  src\main\java\squarrr\virtualcomputers\lod\ScreenQuad.java ^
  src\main\java\squarrr\virtualcomputers\screen\Resample.java ^
  src\test\java\LodMathTest.java
if errorlevel 1 exit /b 1

"%JBIN%java.exe" -cp "build\lodtest;%JOML%" LodMathTest
exit /b %errorlevel%
