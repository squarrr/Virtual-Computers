package squarrr.virtualcomputers.vm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Templates {
    private static final Logger LOGGER = LoggerFactory.getLogger("vc.vm");

    private Templates() {
    }

    public static Path directory() {
        Path directory = VmStore.root().resolve("templates");
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            LOGGER.error("[os] cannot create {}", directory, e);
        }
        return directory;
    }

    public static Path forEntry(String entryId) {
        return directory().resolve(entryId + ".qcow2");
    }

    public static boolean exists(String entryId) {
        return Files.isRegularFile(forEntry(entryId));
    }

    public static List<String> installed() {
        List<String> found = new ArrayList<>();
        for (String id : OsRegistry.all().keySet()) {
            if (exists(id)) {
                found.add(id);
            }
        }
        return found;
    }

    public static synchronized Path freeze(Path installedDisk, String entryId) throws IOException {
        Hypervisor.Diagnosis diagnosis = Hypervisor.diagnose();
        if (diagnosis.qemuImg() == null) {
            throw new IOException("qemu-img is not available, so a template cannot be written");
        }
        if (!Files.isRegularFile(installedDisk)) {
            throw new IOException("there is no disk at " + installedDisk + " to make a template of");
        }
        Path target = forEntry(entryId);
        if (Files.exists(target)) {
            throw new IOException(entryId + " already has a template. Delete "
                    + target.getFileName() + " first if you mean to replace it - but every machine"
                    + " cloned from it is using it right now.");
        }

        Path partial = target.resolveSibling(entryId + ".qcow2.partial");
        Files.deleteIfExists(partial);
        LOGGER.info("[os] writing the {} template; this reads the whole disk once", entryId);
        run(List.of(diagnosis.qemuImg().toString(), "convert", "-O", "qcow2",
                installedDisk.toString(), partial.toString()), "convert a finished install");
        Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
        freezeFile(target);
        LOGGER.info("[os] {} template is ready ({}); every machine from now on clones it",
                entryId, human(Files.size(target)));
        return target;
    }

    public static synchronized Path adopt(Path raw, String entryId) throws IOException {
        Hypervisor.Diagnosis diagnosis = Hypervisor.diagnose();
        if (diagnosis.qemuImg() == null) {
            throw new IOException("qemu-img is not available, so a template cannot be written");
        }
        Path target = forEntry(entryId);
        if (Files.exists(target)) {
            return target;
        }
        Path partial = target.resolveSibling(entryId + ".qcow2.partial");
        Files.deleteIfExists(partial);
        LOGGER.info("[os] converting the {} image into a template", entryId);

        run(List.of(diagnosis.qemuImg().toString(), "convert", "-O", "qcow2",
                raw.toString(), partial.toString()), "convert a downloaded image");
        Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
        freezeFile(target);
        LOGGER.info("[os] {} template is ready ({})", entryId, human(Files.size(target)));
        return target;
    }

    public static void createOverlay(Path overlay, Path template, int sizeGb) throws IOException {
        createOverlay(overlay, template, "qcow2", sizeGb);
    }

    public static void createOverlay(Path overlay, Path template, String backingFormat, int sizeGb)
            throws IOException {
        Hypervisor.Diagnosis diagnosis = Hypervisor.diagnose();
        if (diagnosis.qemuImg() == null) {
            throw new IOException("qemu-img is not available, so a disk cannot be created");
        }
        if (!Files.isRegularFile(template)) {
            throw new IOException("there is no template at " + template);
        }
        Files.createDirectories(overlay.getParent());
        List<String> command = new ArrayList<>(List.of(
                diagnosis.qemuImg().toString(), "create", "-f", "qcow2",
                "-b", template.toAbsolutePath().toString(), "-F", backingFormat,
                overlay.toAbsolutePath().toString()));
        long templateBytes = virtualSize(template);
        long wanted = sizeGb * 1024L * 1024L * 1024L;
        if (wanted > templateBytes) {
            command.add(Long.toString(wanted));
        }
        run(command, "create an overlay");
        LOGGER.info("[os] {} is a thin overlay on {}", overlay.getFileName(), template.getFileName());
    }

    public static long virtualSize(Path image) throws IOException {
        Hypervisor.Diagnosis diagnosis = Hypervisor.diagnose();
        if (diagnosis.qemuImg() == null) {
            return 0L;
        }
        String output = capture(List.of(diagnosis.qemuImg().toString(), "info",
                "--output", "json", image.toAbsolutePath().toString()));
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\"virtual-size\"\\s*:\\s*(\\d+)").matcher(output);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : 0L;
    }

    public static long actualSize(Path image) throws IOException {
        return Files.isRegularFile(image) ? Files.size(image) : 0L;
    }

    private static void freezeFile(Path template) {
        if (!template.toFile().setReadOnly()) {
            LOGGER.warn("[os] could not mark {} read-only; nothing must ever write to it",
                    template.getFileName());
        }
    }

    public static boolean thaw(String entryId) {
        Path template = forEntry(entryId);
        return Files.isRegularFile(template) && template.toFile().setWritable(true);
    }

    private static final String[] UNITS = {"KB", "MB", "GB", "TB", "PB"};

    public static String human(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double value = bytes / 1024.0;
        int unit = 0;
        while (value >= 1024.0 && unit < UNITS.length - 1) {
            value /= 1024.0;
            unit++;
        }
        return String.format(java.util.Locale.ROOT, value < 10.0 ? "%.1f %s" : "%.0f %s",
                value, UNITS[unit]);
    }

    /** Every machine disk that is a copy-on-write overlay on this template, and would break without it. */
    public static List<Path> dependents(String entryId) {
        List<Path> using = new ArrayList<>();
        for (Path root : VmStore.knownLocations()) {
            Path disks = root.resolve("disks");
            if (!Files.isDirectory(disks)) {
                continue;
            }
            try (java.util.stream.Stream<Path> listed = Files.list(disks)) {
                for (Path disk : listed.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".qcow2")).toList()) {
                    if (entryId.equals(VmStore.templateBehind(disk))) {
                        using.add(disk);
                    }
                }
            } catch (IOException e) {
                LOGGER.debug("[os] could not read {}: {}", disks, e.getMessage());
            }
        }
        return using;
    }

    private static String capture(List<String> command) throws IOException {
        return Exec.run(command, Exec.QUICK_MS).output();
    }

    private static void run(List<String> command, String what) throws IOException {
        Exec.Result result = Exec.run(command, Exec.PATIENT_MS);
        if (!result.ok()) {
            throw new IOException("qemu-img could not " + what + ": " + result.complaint());
        }
    }
}
