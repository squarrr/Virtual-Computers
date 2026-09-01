package squarrr.virtualcomputers.vm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VmStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("vc.vm");

    public static final String SNAPSHOT = "vc";

    private static Path root;

    private VmStore() {
    }

    public static synchronized Path root() {
        if (root == null) {
            String override = System.getProperty("vc.data");
            root = override != null && !override.isBlank()
                    ? Path.of(override)
                    : Path.of(System.getProperty("user.dir", ".")).resolve("virtualcomputers");
            try {
                Files.createDirectories(root.resolve("disks"));
                Files.createDirectories(root.resolve("images"));
            } catch (IOException e) {
                LOGGER.error("[vm] cannot create the data directory at {}", root, e);
            }
        }
        return root;
    }

    /** Every data directory this machine knows of, so other instances' VMs can be accounted for. */
    public static Path locationIndex() {
        String override = System.getProperty("vc.locations");
        return override != null && !override.isBlank()
                ? Path.of(override)
                : Path.of(System.getProperty("user.home", "."), ".virtualcomputers", "locations.txt");
    }

    private static void rememberLocation(Path directory) {
        Path index = locationIndex();
        String line = directory.toAbsolutePath().normalize().toString();
        try {
            Files.createDirectories(index.getParent());
            List<String> known = Files.isRegularFile(index)
                    ? Files.readAllLines(index, StandardCharsets.UTF_8) : List.of();
            if (known.stream().noneMatch(s -> s.trim().equalsIgnoreCase(line))) {
                Files.writeString(index, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            LOGGER.debug("[vm] could not record this data directory: {}", e.getMessage());
        }
    }

    public enum Sharing { UNSET, ALLOWED, DECLINED }

    private static Path sharingFile() {
        return root().resolve("cross-instance.txt");
    }

    /** Whether this instance may keep a list of data folders outside its own, so other instances show up. */
    public static Sharing sharing() {
        try {
            Path file = sharingFile();
            if (!Files.isRegularFile(file)) {
                return Sharing.UNSET;
            }
            return "yes".equalsIgnoreCase(Files.readString(file, StandardCharsets.UTF_8).trim())
                    ? Sharing.ALLOWED : Sharing.DECLINED;
        } catch (IOException | RuntimeException e) {
            return Sharing.UNSET;
        }
    }

    public static void setSharing(boolean allowed) {
        try {
            Files.writeString(sharingFile(), allowed ? "yes" : "no", StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("[vm] could not record the cross-instance choice: {}", e.getMessage());
        }
        if (allowed) {
            rememberLocation(root());
        }
    }

    public static List<Path> knownLocations() {
        java.util.LinkedHashSet<Path> found = new java.util.LinkedHashSet<>();
        found.add(root().toAbsolutePath().normalize());
        if (sharing() != Sharing.ALLOWED) {
            return List.copyOf(found);
        }
        boolean stale = false;
        try {
            Path index = locationIndex();
            if (Files.isRegularFile(index)) {
                for (String line : Files.readAllLines(index, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    Path candidate = Path.of(trimmed).toAbsolutePath().normalize();
                    if (Files.isDirectory(candidate)) {
                        found.add(candidate);
                    } else {
                        stale = true;
                    }
                }
                if (stale) {
                    forgetMissingLocations(index, found);
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("[vm] could not read the location index: {}", e.getMessage());
        }
        return List.copyOf(found);
    }

    private static void forgetMissingLocations(Path index, java.util.Collection<Path> alive) {
        try {
            StringBuilder rebuilt = new StringBuilder();
            for (Path path : alive) {
                rebuilt.append(path).append(System.lineSeparator());
            }
            Files.writeString(index, rebuilt.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.debug("[vm] could not prune the location index: {}", e.getMessage());
        }
    }

    public static Path diskFor(String machineId) {
        return root().resolve("disks").resolve(machineId + ".qcow2");
    }

    public static Path firmwareVarsFor(String machineId) throws IOException {
        Path vars = root().resolve("firmware").resolve(machineId + "_VARS.fd");
        if (Files.isRegularFile(vars)) {
            return vars;
        }
        Path template = Hypervisor.firmware("vars");
        if (template == null) {
            throw new IOException("this QEMU install ships no UEFI variable template"
                    + " (edk2-i386-vars.fd or OVMF_VARS.fd), so a UEFI guest cannot keep its"
                    + " boot settings");
        }
        Files.createDirectories(vars.getParent());
        Files.copy(template, vars);

        vars.toFile().setWritable(true);
        LOGGER.info("[vm] {} got its own UEFI variable store", machineId);
        return vars;
    }

    public static boolean hasDisk(String machineId) {
        return Files.isRegularFile(diskFor(machineId));
    }

    public static void ensureDisk(String machineId, OsEntry entry, int fallbackGb) throws IOException {
        Path disk = diskFor(machineId);
        if (Files.isRegularFile(disk)) {
            return;
        }
        if (entry != null && Templates.exists(entry.id())) {
            Templates.createOverlay(disk, Templates.forEntry(entry.id()), entry.diskGb());
            return;
        }
        ensureDisk(machineId, entry != null ? entry.diskGb() : fallbackGb);
    }

    /** A machine's own copy-on-write layer over shared installer media. */
    public static Path mediaOverlayFor(String machineId) {
        return root().resolve("media").resolve(machineId + ".qcow2");
    }

    public static Path osMarkerFor(Path disk) {
        return disk.resolveSibling(disk.getFileName() + ".os");
    }

    /** Records which operating system went onto a disk, because a disk installed onto records nothing. */
    public static void setOs(String machineId, String entryId) {
        try {
            Files.writeString(osMarkerFor(diskFor(machineId)), entryId, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("[vm] could not record the operating system for {}: {}", machineId,
                    e.getMessage());
        }
    }

    public static String osOf(String machineId) {
        return osOfDisk(diskFor(machineId));
    }

    public static String osOfDisk(Path disk) {
        Path marker = osMarkerFor(disk);
        if (Files.isRegularFile(marker)) {
            try {
                String recorded = Files.readString(marker, StandardCharsets.UTF_8).trim();
                if (!recorded.isEmpty()) {
                    return recorded;
                }
            } catch (IOException ignored) {
                // fall through to the backing file
            }
        }
        return templateBehind(disk);
    }

    public static boolean hasSnapshotOnDisk(Path disk) {
        return inspect(disk).contains("\"snapshots\"");
    }

    private static String inspect(Path disk) {
        if (!Files.isRegularFile(disk)) {
            return "";
        }
        Hypervisor.Diagnosis diagnosis = Hypervisor.diagnose();
        if (diagnosis.qemuImg() == null) {
            return "";
        }
        return Exec.capture(List.of(diagnosis.qemuImg().toString(), "info",
                "--output", "json", disk.toAbsolutePath().toString()), Exec.QUICK_MS);
    }

    /** The template a disk is an overlay on, or null when it was installed onto directly. */
    public static String templateBehind(Path disk) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"backing-filename\"\\s*:\\s*\"(.*?)\"").matcher(inspect(disk));
        if (!matcher.find()) {
            return null;
        }
        String backing = matcher.group(1).replace("\\\\", "\\");
        String name = Path.of(backing).getFileName().toString();
        return name.endsWith(".qcow2") ? name.substring(0, name.length() - 6) : name;
    }

    public static void ensureDisk(String machineId, int gigabytes) throws IOException {
        Path disk = diskFor(machineId);
        if (Files.isRegularFile(disk)) {
            return;
        }
        Hypervisor.Diagnosis diagnosis = Hypervisor.diagnose();
        if (diagnosis.qemuImg() == null) {
            throw new IOException("qemu-img is not available, so a disk cannot be created");
        }
        Files.createDirectories(disk.getParent());
        List<String> command = List.of(diagnosis.qemuImg().toString(), "create",
                "-f", "qcow2", disk.toString(), gigabytes + "G");
        run(command, "create a disk");
        LOGGER.info("[vm] created {} ({} GB, sparse)", disk.getFileName(), gigabytes);
    }

    public static boolean hasSnapshot(String machineId) {
        Path disk = diskFor(machineId);
        if (!Files.isRegularFile(disk)) {
            return false;
        }
        Hypervisor.Diagnosis diagnosis = Hypervisor.diagnose();
        if (diagnosis.qemuImg() == null) {
            return false;
        }
        String listed = Exec.capture(List.of(diagnosis.qemuImg().toString(), "snapshot", "-l",
                disk.toString()), Exec.QUICK_MS);
        for (String line : listed.split("\\R")) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length >= 2 && SNAPSHOT.equals(fields[1])) {
                return true;
            }
        }
        return false;
    }

    public static void dropSnapshot(String machineId) {
        Hypervisor.Diagnosis diagnosis = Hypervisor.diagnose();
        if (diagnosis.qemuImg() == null || !hasDisk(machineId)) {
            return;
        }
        try {
            run(List.of(diagnosis.qemuImg().toString(), "snapshot", "-d", SNAPSHOT,
                    diskFor(machineId).toString()), "delete a snapshot");
        } catch (IOException e) {
            LOGGER.debug("[vm] no snapshot to drop on {}", machineId);
        }
    }

    public static Path bootMediaMatching(String entryId) {
        String override = System.getProperty("vc.iso");
        if (override != null && !override.isBlank()) {
            Path direct = Path.of(override);
            return Files.isRegularFile(direct) ? direct : null;
        }
        List<Path> candidates;
        try (Stream<Path> found = Files.list(root().resolve("images"))) {
            candidates = found.filter(Files::isRegularFile)
                    .filter(p -> {
                        String lower = p.getFileName().toString().toLowerCase();
                        return lower.endsWith(".iso") || lower.endsWith(".img");
                    })
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return null;
        }
        if (candidates.isEmpty()) {
            return null;
        }

        if ("custom".equals(entryId)) {
            return candidates.get(0);
        }
        List<String> tokens = new java.util.ArrayList<>(List.of(entryId.split("_")));
        tokens.add(entryId.replace("_", ""));
        tokens.add(entryId.replace("windows", "win").replace("_", ""));

        Path best = null;
        int bestScore = 0;
        for (Path candidate : candidates) {
            String lower = candidate.getFileName().toString().toLowerCase();
            int score = 0;
            for (String token : tokens) {
                if (token.length() > 1 && lower.contains(token)) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    public static Path bootMedia() {
        String override = System.getProperty("vc.iso");
        if (override != null && !override.isBlank()) {
            Path direct = Path.of(override);
            return Files.isRegularFile(direct) ? direct : null;
        }
        Path images = root().resolve("images");
        try (Stream<Path> found = Files.list(images)) {
            return found.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".iso"))
                    .min(Comparator.comparing(p -> p.getFileName().toString()))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static void run(List<String> command, String what) throws IOException {
        Exec.Result result = Exec.run(command, Exec.PATIENT_MS);
        if (!result.ok()) {
            throw new IOException("qemu-img could not " + what + ": " + result.complaint());
        }
    }
}
