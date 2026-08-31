# Launches Minecraft with the mod.
#
#   .\run-client.ps1                     test pattern on the block
#   .\run-client.ps1 127.0.0.1:5901      a real VM on the block
#
# The endpoint is a plain argument, not -Dvc.vnc=... Two reasons: PowerShell mangles a
# bare -Dfoo=bar token before the script ever sees it, and -D on the Gradle command line
# only reaches the Gradle daemon anyway - runClient forks a separate JVM. build.gradle
# turns -Pvc.vnc into a systemProperty on that forked run.
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string] $Vnc
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

# Resolve a JDK. The machine-scope lookup matters: the installer sets JAVA_HOME there, but
# Windows reads the environment when a process starts, so a terminal opened before the
# install has neither JAVA_HOME nor java on PATH.
function Resolve-Jdk {
    foreach ($candidate in @(
            $env:JAVA_HOME,
            [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User'),
            [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine'))) {
        if ($candidate -and (Test-Path (Join-Path $candidate 'bin\java.exe'))) {
            return $candidate.TrimEnd('\')
        }
    }
    $guess = Get-ChildItem 'C:\Program Files\Microsoft', 'C:\Program Files\Eclipse Adoptium' `
                -Directory -ErrorAction SilentlyContinue |
             Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } |
             Sort-Object Name -Descending | Select-Object -First 1
    if ($guess) { return $guess.FullName }
    return $null
}

$jdk = Resolve-Jdk
if (-not $jdk) {
    Write-Host "No JDK found." -ForegroundColor Red
    Write-Host "  Install one with:  winget install Microsoft.OpenJDK.25"
    exit 1
}
$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;$env:PATH"
Write-Host "Using JDK: $jdk"

$gradleArgs = @('runClient')
if ($Vnc) {
    if ($Vnc -notmatch '^[^\s:]+:\d+$') {
        Write-Host "Endpoint should look like host:port, for example 127.0.0.1:5901" -ForegroundColor Red
        Write-Host "  You passed: $Vnc"
        exit 1
    }
    $gradleArgs += "-Pvc.vnc=$Vnc"
    Write-Host "Screen source: VNC at $Vnc" -ForegroundColor Cyan
} else {
    Write-Host "Screen source: built-in test pattern" -ForegroundColor Cyan
    Write-Host "  The local pattern cannot show input. For a VM, or for the test server that"
    Write-Host "  echoes what you type:  .\run-client.ps1 127.0.0.1:5901"
}
Write-Host "In game:  /give @s virtualcomputers:laptop  - place it, then right-click to use it."
Write-Host ""

Set-Location $root
& "$root\gradlew.bat" @gradleArgs
exit $LASTEXITCODE
