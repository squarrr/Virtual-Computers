@echo off
setlocal
rem Proves phase 3's storage model without a guest, a download or a game.
rem
rem The phase rests on one sentence - "the machines are fully independent; every write a machine
rem makes lands in its own overlay and is invisible to the others" - and that sentence is either true
rem of qemu-img's backing files or the whole model is wrong. It is also invisible from inside the
rem game: three machines boot and three machines work, right up until one writes over a block
rem another was reading.
rem
rem So qemu-io writes a pattern straight into one overlay and the other two are required to still
rem read the base. Takes about a second.

cd /d "%~dp0"
call gradlew.bat storageTest --console=plain %*
set RESULT=%errorlevel%
echo.
if not "%~1"=="--no-pause" pause
exit /b %RESULT%
