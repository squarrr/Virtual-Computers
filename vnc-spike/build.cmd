@echo off
REM Compiles and runs the RFB spike. See the note in run-vm.cmd for why this
REM wrapper exists rather than just a .ps1.
setlocal enabledelayedexpansion
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" %*
set RC=%ERRORLEVEL%
REM Pause only when double-clicked from Explorer. Strip quotes first: cmdcmdline
REM contains them, and an unstripped comparison is a batch parse error.
set "CL=!cmdcmdline!"
set "CL=!CL:"=!"
if not "!CL!"=="!CL:/c=!" pause
exit /b %RC%
