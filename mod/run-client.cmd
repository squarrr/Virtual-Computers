@echo off
REM Launches Minecraft with the mod. Double-clickable.
REM   run-client.cmd                   test pattern on the block
REM   run-client.cmd 127.0.0.1:5901    a real VM on the block
REM Wraps the .ps1 because Windows will not run a .ps1 on double-click, and the
REM default execution policy blocks them from a terminal too.
setlocal enabledelayedexpansion
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-client.ps1" %*
set RC=%ERRORLEVEL%
REM Pause only when double-clicked from Explorer. Strip quotes first: cmdcmdline
REM contains them, and an unstripped comparison is a batch parse error.
set "CL=!cmdcmdline!"
set "CL=!CL:"=!"
if not "!CL!"=="!CL:/c=!" pause
exit /b %RC%
