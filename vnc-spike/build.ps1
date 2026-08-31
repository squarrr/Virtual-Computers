# Compiles and runs the spike. No build tool, no dependencies - just a JDK.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

# Find a JDK. The registry fallback matters: the installer sets JAVA_HOME at machine level,
# but Windows reads the environment when a process starts, so a terminal opened before the
# install still has neither JAVA_HOME nor the JDK on PATH. Rather than telling the user to
# reopen their shell, just find it.
function Resolve-Jdk {
    if (Get-Command javac -ErrorAction SilentlyContinue) { return $null }  # already on PATH
    # Not $home: that is a read-only automatic variable in PowerShell.
    foreach ($candidate in @(
            $env:JAVA_HOME,
            [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User'),
            [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine'))) {
        if ($candidate -and (Test-Path (Join-Path $candidate 'bin\javac.exe'))) {
            return $candidate.TrimEnd('\')
        }
    }
    $guess = Get-ChildItem 'C:\Program Files\Microsoft', 'C:\Program Files\Eclipse Adoptium' `
                -Directory -ErrorAction SilentlyContinue |
             Where-Object { Test-Path (Join-Path $_.FullName 'bin\javac.exe') } |
             Sort-Object Name -Descending | Select-Object -First 1
    if ($guess) { return $guess.FullName }
    Write-Host "No JDK found." -ForegroundColor Red
    Write-Host "  Install one with:  winget install Microsoft.OpenJDK.25"
    exit 1
}

$jdk = Resolve-Jdk
if ($jdk) {
    Write-Host "Using JDK: $jdk"
    $env:JAVA_HOME = $jdk
    $env:PATH = "$jdk\bin;$env:PATH"
}

$out = Join-Path $root 'out'
if (-not (Test-Path $out)) { New-Item -ItemType Directory $out | Out-Null }
$sources = Get-ChildItem -Path (Join-Path $root 'src') -Filter *.java -Recurse | ForEach-Object { $_.FullName }
& javac -d $out $sources
if ($LASTEXITCODE -ne 0) { throw "compile failed" }

# Run from the spike directory so --out lands here rather than wherever the caller
# happened to be standing.
Set-Location $root
& java -cp $out vncspike.Spike @args
exit $LASTEXITCODE
