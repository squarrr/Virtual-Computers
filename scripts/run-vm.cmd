@echo off
REM Boots the phase 0 test VM.
REM Exists because Windows will not run a .ps1 on double-click, and the default
REM execution policy blocks them from a terminal too. This wrapper sidesteps both
REM for this one script without changing any machine-wide setting.
setlocal enabledelayedexpansion
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-vm.ps1" %*
set RC=%ERRORLEVEL%
REM Pause only when double-clicked from Explorer. Strip quotes first: cmdcmdline
REM contains them, and an unstripped comparison is a batch parse error.
set "CL=!cmdcmdline!"
set "CL=!CL:"=!"
if not "!CL!"=="!CL:/c=!" pause
exit /b %RC%
