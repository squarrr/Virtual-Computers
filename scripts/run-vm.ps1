<#
.SYNOPSIS
  Boots the phase 0 test VM and reports honestly about hardware acceleration.

.DESCRIPTION
  QEMU installs on Windows without adding itself to PATH, so this finds it. More
  importantly, it implements the rule from the plan's risk register: detect the absence
  of hardware acceleration and say so in words, rather than silently falling back to
  software emulation and letting the user conclude the mod is broken.

  A guest under TCG is roughly two orders of magnitude slower than one under WHPX. It is
  fine for a BIOS screen and useless for an operating system.

.EXAMPLE
  .\scripts\run-vm.ps1
  Boots with no disk. Lands on the SeaBIOS "no bootable device" screen, which is a real
  framebuffer and enough to prove the pixel path end to end.

.EXAMPLE
  .\scripts\run-vm.ps1 -Iso C:\isos\debian.iso -MemoryMb 4096
  Boots installation media.
#>
[CmdletBinding()]
param(
    [string] $Iso,
    [int]    $MemoryMb = 2048,
    [int]    $VncDisplay = 1,
    [switch] $AllowSoftware,
    [switch] $Tablet
)

$ErrorActionPreference = 'Stop'

# ---- locate QEMU -------------------------------------------------------------
$qemu = (Get-Command qemu-system-x86_64 -ErrorAction SilentlyContinue).Source
if (-not $qemu) {
    foreach ($candidate in @(
            'C:\Program Files\qemu\qemu-system-x86_64.exe',
            'C:\Program Files (x86)\qemu\qemu-system-x86_64.exe',
            "$env:LOCALAPPDATA\Programs\qemu\qemu-system-x86_64.exe")) {
        if (Test-Path $candidate) { $qemu = $candidate; break }
    }
}
if (-not $qemu) {
    Write-Host "QEMU not found." -ForegroundColor Red
    Write-Host "  Install it with:  winget install SoftwareFreedomConservancy.QEMU"
    exit 1
}
Write-Host "QEMU: $qemu"

# ---- decide on an accelerator, and explain the decision ----------------------
$accelerators = & $qemu -accel help 2>&1 | ForEach-Object { $_.Trim() }
$hasWhpx = $accelerators -contains 'whpx'
$hypervisorRunning = (Get-CimInstance Win32_ComputerSystem).HypervisorPresent

$accel = $null
if ($hasWhpx -and $hypervisorRunning) {
    $accel = 'whpx'
    Write-Host "Acceleration: WHPX (hardware)" -ForegroundColor Green
} else {
    Write-Host ""
    Write-Host "No hardware acceleration available." -ForegroundColor Yellow
    if (-not $hasWhpx) {
        Write-Host "  This QEMU build has no WHPX support compiled in."
    } else {
        Write-Host "  QEMU supports WHPX, but the Windows hypervisor is not running."
        Write-Host "  Windows Hypervisor Platform is available on every Windows 11 edition,"
        Write-Host "  including Home - only the full Hyper-V role is Pro and above."
        Write-Host ""
        Write-Host "  Enable it from an ADMINISTRATOR PowerShell, then reboot:" -ForegroundColor Cyan
        Write-Host "    Enable-WindowsOptionalFeature -Online -FeatureName HypervisorPlatform -All"
    }
    Write-Host ""
    Write-Host "  Falling back to TCG means software emulation: fine for a BIOS screen,"
    Write-Host "  unusably slow for an actual operating system." -ForegroundColor Yellow
    if (-not $AllowSoftware -and $Iso) {
        Write-Host ""
        Write-Host "Refusing to boot installation media under software emulation." -ForegroundColor Red
        Write-Host "Enable acceleration, or pass -AllowSoftware if you really mean it."
        exit 2
    }
    $accel = 'tcg'
}

# ---- boot --------------------------------------------------------------------
$vncPort = 5900 + $VncDisplay
# Pointer device. QEMU's default is a relative PS/2 mouse, and RFB PointerEvent carries an
# ABSOLUTE position - so QEMU turns "the pointer is at 700,500" into a delta, the guest applies its
# own acceleration on top, and the cursor drifts out of step with where the client thinks it is.
#
# -Tablet swaps in a USB tablet, which is an absolute device: the coordinate arrives as a
# coordinate. That is the correct fix and what most real guests want.
#
# It used to say here that Tiny Core silently kills the pointer with a tablet attached. PHASE 2
# DISPROVED THAT: `query-mice` reports "QEMU HID Tablet, current: true, absolute: true" on a booted
# Tiny Core desktop. The original measurement was taken against a guest that had not finished
# starting - a guest binds a USB HID device while its operating system comes up, so asking too early
# gets the wrong answer and the wrong answer looked like a permanent limitation.
#
# The mod attaches it unconditionally now. It stays opt-in HERE only because this script exists to
# reproduce a bare, known-quantity VM by hand.
# The panel's resolution is NOT set here, and the attempt is worth recording so nobody repeats it.
#
# -global VGA.xres/VGA.yres looks like the answer: it sets the preferred mode the virtual card
# advertises. On Tiny Core it left the guest stuck at 640x384 with X never starting at all - the
# advertised mode is a hint, and a guest that cannot use it can end up worse off than with no hint.
#
# There is no way round that from out here. RFB cannot ask a guest to change mode, and neither can
# QEMU compel one. Resolution is set inside the guest: Tiny Core takes the boot code
# `screen=1920x1080x32`, most distributions take a kernel `video=` argument, and Windows is a
# display-settings dialog. Phase 3 owns this properly, by provisioning the guest rather than asking
# it nicely - which is exactly why the plan puts guest resolution under "install the OS", not under
# "draw the screen".
$qemuArgs = @('-m', "$MemoryMb", '-accel', $accel,
              '-vnc', "127.0.0.1:$VncDisplay", '-name', 'vc-phase0')
if ($Tablet) {
    # The default machine has no USB controller at all, so one has to come first or QEMU refuses
    # to start with "No 'usb-bus' bus found for device 'usb-tablet'".
    $qemuArgs += @('-device', 'qemu-xhci', '-device', 'usb-tablet')
    Write-Host "Pointer: USB tablet (absolute). If the guest ignores it, drop -Tablet." -ForegroundColor Cyan
}
if ($Iso) {
    if (-not (Test-Path $Iso)) { Write-Host "ISO not found: $Iso" -ForegroundColor Red; exit 1 }
    $qemuArgs += @('-cdrom', $Iso, '-boot', 'd')
}

$repo = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Write-Host ""
Write-Host "VNC on 127.0.0.1:$vncPort  (loopback only, no password)"
Write-Host ""
Write-Host "In another terminal - note the leading .\ , PowerShell needs it:" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Prove the transport:"
Write-Host "    cd `"$repo\vnc-spike`"; .\build.cmd --host 127.0.0.1 --port $vncPort"
Write-Host "  Put it on a block:"
Write-Host "    cd `"$repo\mod`"; .\run-client.cmd 127.0.0.1:$vncPort"
Write-Host ""
Write-Host "Ctrl+C to stop the VM."
Write-Host ""

& $qemu @qemuArgs
