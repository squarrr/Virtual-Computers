@echo off
setlocal
rem Boots a real guest, snapshots or shuts it down, resumes it, and checks the picture survived.
rem
rem This is the phase 2 equivalent of vnc-spike's build.cmd: the risky layer, exercised without
rem launching Minecraft. It found the thing that changed phase 2's design - WHPX cannot snapshot -
rem on its first run, which is a finding no amount of playing the game would have surfaced before a
rem player lost a machine to it.
rem
rem Put an .iso in run\virtualcomputers\images first, or pass one:
rem   .\vm-smoke.cmd -Pvc.iso=C:\path\to\some.iso

cd /d "%~dp0"
call gradlew.bat vmSmoke --console=plain %*
set RESULT=%errorlevel%
echo.
if not "%~1"=="--no-pause" pause
exit /b %RESULT%
