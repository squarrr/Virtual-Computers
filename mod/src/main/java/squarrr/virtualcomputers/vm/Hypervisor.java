package squarrr.virtualcomputers.vm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Hypervisor {
    private static final Logger LOGGER = LoggerFactory.getLogger("vc.vm");

    private static final String[] WINDOWS_CANDIDATES = {
            "C:\\Program Files\\qemu",
            "C:\\Program Files (x86)\\qemu",
            System.getenv("LOCALAPPDATA") + "\\Programs\\qemu",
    };

    private static final String[] UNIX_CANDIDATES = {
            "/usr/bin", "/usr/local/bin", "/opt/homebrew/bin", "/run/current-system/sw/bin",
    };

    private static Diagnosis cached;
    private static Boolean tpmCompiledIn;
    private static final java.util.Map<String, Path> FIRMWARE = new java.util.HashMap<>();

    private Hypervisor() {
    }

    public static synchronized boolean supportsTpm() {
        if (tpmCompiledIn == null) {
            Diagnosis diagnosis = diagnose();
            if (diagnosis.qemuSystem() == null) {
                return false;
            }
            String devices = runAndCapture(List.of(diagnosis.qemuSystem().toString(), "-device", "help"));
            tpmCompiledIn = devices != null && devices.toLowerCase(Locale.ROOT).contains("tpm-tis");
        }
        return tpmCompiledIn;
    }

    public static synchronized Path firmware(String what) {
        return FIRMWARE.computeIfAbsent(what, key -> {
            String[] names = switch (key) {
                case "secure-code" -> new String[] {
                        "edk2-x86_64-secure-code.fd", "OVMF_CODE.secboot.fd", "OVMF_CODE_4M.secboot.fd"};
                case "vars" -> new String[] {
                        "edk2-i386-vars.fd", "OVMF_VARS.fd", "OVMF_VARS_4M.fd"};
                default -> new String[] {
                        "edk2-x86_64-code.fd", "OVMF_CODE.fd", "OVMF_CODE_4M.fd"};
            };
            for (Path directory : firmwareDirectories()) {
                for (String name : names) {
                    Path candidate = directory.resolve(name);
                    if (Files.isReadable(candidate)) {
                        return candidate;
                    }
                }
            }
            return null;
        });
    }

    private static List<Path> firmwareDirectories() {
        List<Path> directories = new ArrayList<>();
        Diagnosis diagnosis = diagnose();
        if (diagnosis.qemuSystem() != null) {
            Path home = diagnosis.qemuSystem().getParent();
            if (home != null) {
                directories.add(home.resolve("share"));
                directories.add(home);
                Path up = home.getParent();
                if (up != null) {
                    directories.add(up.resolve("share").resolve("qemu"));
                }
            }
        }
        for (String unix : new String[] {
                "/usr/share/qemu", "/usr/share/OVMF", "/usr/share/edk2/ovmf",
                "/usr/share/edk2-ovmf/x64", "/opt/homebrew/share/qemu"}) {
            directories.add(Path.of(unix));
        }
        return directories;
    }

    public static synchronized Diagnosis diagnose() {
        if (cached == null) {
            cached = probe();
        }
        return cached;
    }

    public static synchronized void forget() {
        cached = null;
    }

    private static Diagnosis probe() {
        Path system = findExecutable("qemu-system-x86_64");
        Path image = findExecutable("qemu-img");
        if (system == null) {
            return new Diagnosis(null, null, null, List.of(),
                    "QEMU is not installed, or not where this mod looked.\n"
                    + "  Install it:  winget install SoftwareFreedomConservancy.QEMU\n"
                    + "  Or point at it directly:  -Dvc.qemu=C:\\path\\to\\qemu");
        }
        if (image == null) {
            return new Diagnosis(system, null, null, List.of(),
                    "Found qemu-system-x86_64 but not qemu-img, which sits beside it in every\n"
                    + "  normal install. A partial install is the likely cause; reinstall QEMU.");
        }

        List<String> accelerators = listAccelerators(system);
        String accelerator = chooseAccelerator(accelerators);

        if (accelerator == null) {
            return new Diagnosis(system, image, null, accelerators, noAccelerationAdvice(accelerators));
        }
        return new Diagnosis(system, image, accelerator, accelerators, null);
    }

    private static String chooseAccelerator(List<String> available) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String wanted = os.contains("win") ? "whpx" : os.contains("mac") ? "hvf" : "kvm";
        if (available.contains(wanted) && hypervisorRunning()) {
            return wanted;
        }
        return null;
    }

    private static boolean hypervisorRunning() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String out = runAndCapture(List.of("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-Command", "(Get-CimInstance Win32_ComputerSystem).HypervisorPresent"));
            return out != null && out.toLowerCase(Locale.ROOT).contains("true");
        }
        if (os.contains("linux")) {
            return Files.exists(Path.of("/dev/kvm"));
        }
        return true;
    }

    private static String noAccelerationAdvice(List<String> available) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        StringBuilder advice = new StringBuilder("No hardware virtualization is available.\n");
        if (os.contains("win")) {
            if (!available.contains("whpx")) {
                advice.append("  This QEMU build has no WHPX support compiled in.\n");
            } else {
                advice.append("  QEMU supports WHPX, but the Windows hypervisor is not running.\n")
                      .append("  Windows Hypervisor Platform ships on every Windows 11 edition,\n")
                      .append("  Home included - only the full Hyper-V role is Pro and above.\n")
                      .append("  From an ADMINISTRATOR PowerShell, then reboot:\n")
                      .append("    Enable-WindowsOptionalFeature -Online -FeatureName HypervisorPlatform -All\n");
            }
        } else if (os.contains("linux")) {
            advice.append("  /dev/kvm is missing. Enable virtualization in firmware, and check that\n")
                  .append("  your user is in the 'kvm' group.\n");
        } else {
            advice.append("  This QEMU build has no HVF support compiled in.\n");
        }
        advice.append("  Software emulation would be about a hundred times slower, which reads as a\n")
              .append("  broken mod rather than a missing feature, so it is refused.\n")
              .append("  Override with -Dvc.allowSoftware=true if you really mean it.");
        return advice.toString();
    }

    private static List<String> listAccelerators(Path qemu) {
        String out = runAndCapture(List.of(qemu.toString(), "-accel", "help"));
        List<String> found = new ArrayList<>();
        if (out == null) {
            return found;
        }
        for (String line : out.split("\\R")) {
            String trimmed = line.trim();

            if (!trimmed.isEmpty() && !trimmed.endsWith(":") && !trimmed.contains(" ")) {
                found.add(trimmed.toLowerCase(Locale.ROOT));
            }
        }
        return found;
    }

    private static Path findExecutable(String name) {
        String suffix = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? ".exe" : "";

        String override = System.getProperty("vc.qemu");
        if (override != null && !override.isBlank()) {
            Path direct = Path.of(override);
            Path inDirectory = direct.resolve(name + suffix);
            if (Files.isExecutable(inDirectory)) {
                return inDirectory;
            }
            if (Files.isExecutable(direct) && direct.getFileName().toString().startsWith(name)) {
                return direct;
            }
        }

        String path = System.getenv("PATH");
        if (path != null) {
            for (String entry : path.split(java.io.File.pathSeparator)) {
                if (entry.isBlank()) {
                    continue;
                }
                Path candidate = Path.of(entry).resolve(name + suffix);
                if (Files.isExecutable(candidate)) {
                    return candidate;
                }
            }
        }

        String[] roots = suffix.isEmpty() ? UNIX_CANDIDATES : WINDOWS_CANDIDATES;
        for (String root : roots) {
            if (root == null) {
                continue;
            }
            Path candidate = Path.of(root).resolve(name + suffix);
            if (Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static final long PROBE_TIMEOUT_MS = 20_000;

    /** These run before anything is on screen, so they must be the kind that always come back. */
    private static String runAndCapture(List<String> command) {
        try {
            return Exec.run(command, PROBE_TIMEOUT_MS).output();
        } catch (IOException e) {
            return null;
        }
    }

    public record Diagnosis(Path qemuSystem, Path qemuImg, String accelerator,
                            List<String> acceleratorsAvailable, String explanation) {
        public boolean usable() {
            return qemuSystem != null && qemuImg != null && (accelerator != null || allowSoftware());
        }

        public String effectiveAccelerator() {
            return accelerator != null ? "tcg".equals(accelerator) ? "tcg" : accelerator : "tcg";
        }

        public boolean isSoftwareOnly() {
            return accelerator == null;
        }

        public int maxVcpus() {
            int override = Integer.getInteger("vc.smp", 0);
            if (override > 0) {
                return override;
            }
            return "whpx".equals(accelerator) ? 1 : Integer.MAX_VALUE;
        }

        public boolean supportsSnapshots() {
            return accelerator != null && !"whpx".equals(accelerator);
        }

        public static boolean allowSoftware() {
            return Boolean.getBoolean("vc.allowSoftware");
        }

        public Path firmware(OsEntry.Firmware wanted) {
            return switch (wanted) {
                case BIOS -> qemuSystem;
                case UEFI -> Hypervisor.firmware("code");
                case UEFI_SECURE -> Hypervisor.firmware("secure-code");
            };
        }

        public String whyNot(OsEntry entry) {
            if (!usable()) {
                return explanation() != null ? explanation() : "no usable hypervisor";
            }
            if (entry.firmware() != OsEntry.Firmware.BIOS && firmware(entry.firmware()) == null) {
                return entry.name() + " needs UEFI firmware, and this QEMU install has none.\n"
                        + "  Looked for edk2-x86_64-code.fd and OVMF_CODE.fd beside QEMU.\n"
                        + "  A full QEMU install ships them; a trimmed one may not.";
            }
            if (entry.firmware() == OsEntry.Firmware.UEFI_SECURE && !supportsTpm()) {
                return entry.name() + " needs a TPM 2.0, and this QEMU cannot emulate one.\n"
                        + "  The QEMU build for Windows leaves TPM support out at compile time, so\n"
                        + "  there is no device for swtpm to attach to even if swtpm were installed.\n"
                        + "  Its Secure Boot firmware is present and fine; it is only the TPM missing.\n"
                        + "  Windows 10 needs neither and installs normally, as does every other\n"
                        + "  operating system in the list.";
            }
            if (entry.kind() == OsEntry.Kind.RECIPE && OsRegistry.get(entry.base()) == null) {
                return entry.name() + " is a recipe built on \"" + entry.base()
                        + "\", and there is no entry by that name.";
            }
            return null;
        }

        public String summary() {
            if (qemuSystem == null) {
                return "QEMU not found";
            }
            if (accelerator != null) {
                int cores = maxVcpus();
                return "QEMU ready, " + accelerator + " (hardware)"
                        + (cores == Integer.MAX_VALUE ? "" : ", max " + cores + " vCPU")
                        + (supportsSnapshots() ? "" : ", no snapshots");
            }
            return allowSoftware() ? "QEMU ready, tcg (SOFTWARE - will be unusably slow)"
                                   : "QEMU found, no hardware acceleration";
        }

        public void log() {
            LOGGER.info("[vm] {}", summary());
            if (explanation != null) {
                for (String line : explanation.split("\\R")) {
                    LOGGER.warn("[vm] {}", line);
                }
            }
        }
    }
}
