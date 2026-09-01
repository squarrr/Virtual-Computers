# Virtual Computers

Many mods add computers to minecraft, but I am yet to see one that fully takes on the task of making
them work properly, so that's what I did. Simply craft, place down, and install different types of
computers.

## How to set up a computer (for those who don't know)

To setup a computer you will need an operating system for it, these are craftable (not yet) items
that point at the operating system you want to install. There are many premade OS items that you can
simply use without any searching for ISO downloads, these being:

*   Windows 10 x64 (you download the ISO)
*   Windows 11 x64 (broken)
*   Linux x64 (Debian 13)
*   Libre Elec x64 (open source TV system that uses an imaging system instead of ISO)
*   A custom ISO of your choice
*   BlissOS x64 (open source android community build) — *planned, not in the mod yet*

The item holds an **id**, not the image and not the download itself. The id names a small entry that carries the URL and a SHA-256, and the mod fetches from the vendor's own servers when you ask it to.

Once holding one of these operating systems, right click a laptop or desktop to fetch the media and boot the installer, then install it however that OS wants to be installed. **When it's finished, sneak and right click with the box again** to freeze it — after that every machine you make with that box no longer needs to do the setup process again, in that world and every other one.

*Windows 11 is broken because its installer requires a TPM 2.0, and the QEMU build for
Windows has TPM support left out at compile time, so there is nothing for swtpm to attach to. Secure Boot works fine, it's only the TPM missing. Windows 10 is unaffected.

## Computer Types

There are a handful of different computers types that are planned for this mod, these being:

*   **Desktop PC** — in the mod. A placable computer tower that requires a display and peripherals to function, aswell as shutting down when broken, good for customizability
*   **Laptop** — in the mod. A placable laptop that is fully self contained with a display and all the peripherals you will need, aswell as only sleeping when broken, allowing for portability. The downside is the extremely small display, that is often extremely hard to read off of
*   **Screen** — in the mod. A dumb panel with no computer in it, showing whatever the tower is doing. Place several and they all show it
*   **TV** — planned. A display containing a small computer and requires a TV remote to use, this is good for placing in a main room to hang out with friends in
*   **Tablet** — planned. A computer of similar size to the laptop, the difference being it is used in your hands instead of on a block, which increasing it's usability by a lot. Since it only has a touch screen, Bliss OS is the only operating system that the item will take
*   **Smart Phone** — planned. Similar to the tablet, but only requiring one hand that includes the offhand while being half the size

## Peripherals

There are a bunch of cool peripherals I have planned for this mod, none of which are in yet:

*   Keyboard
*   Mouse
*   Game Controller
*   Mic
*   Headphones
*   Speakers
*   Webcam (this would allow you to similate a video input from inside minecraft)

For now, right clicking a laptop or a screen gives that machine your keyboard and mouse directly.

## How it works

This mod is possible by using QEMU (an open source machine emulator and virtualizer) to run a computer for each one you place. It runs them with **hardware virtualization** — WHPX on Windows, KVM on Linux, HVF on macOS, and refuses to fall back to software emulation. If it can't find acceleration it tells you what's missing instead of starting to prevent the heavy lag.

These computers get stored and hosted on the player who made them, which is why a GUI to show all of the downloaded computers and ISOs is planned. Each operating system is installed **once** and frozen as a template; every machine after that is a copy-on-write overlay on it, so ten machines store one install plus ten deltas instead of ten installs.

## FAQ

_"Is this compatible with other mods?"_
Compatibility is currently untested, you can try it but I don't recommend it for the time being.

_"Does this work in multiplayer?"_
Not yet, it's singleplayer only right now. Multiplayer is a planned feature for release.

_"Will this work on (any) OS?"_
It's built for Windows 10/11, macOS and Linux, and only Windows has actually been tested so far. macOS is supported as the machine you play on, never as a guest. On Apple Silicon the guests have to be ARM too, and picking ARM images automatically there is planned.

## Requirements

*   Minecraft **26.2** with NeoForge **26.2.0.72**
*   Java **25**
*   [QEMU](https://www.qemu.org/) installed: `winget install SoftwareFreedomConservancy.QEMU`
*   Hardware virtualization enabled. On Windows that's Windows Hypervisor Platform: `Enable-WindowsOptionalFeature -Online -FeatureName HypervisorPlatform -All`

## Running

Everything the mod downloads or creates goes to "virtualcomputers" which is a folder created in the pack.

## Credits

This mod is heavily inspired by
[VM Computers](https://www.curseforge.com/minecraft/mc-mods/vm-computers) by
[delta2force](https://www.curseforge.com/members/delta2force/projects)

## Licence

All Rights Reserved.
