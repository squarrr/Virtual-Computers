package squarrr.virtualcomputers.vm;

import java.io.IOException;
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

    public static String osOf(String machineId) {
        Path disk = diskFor(machineId);
        if (!Files.isRegularFile(disk)) {
            return null;
        }
        Hypervisor.Diagnosis diagnosis = Hypervisor.diagnose();
        if (diagnosis.qemuImg() == null) {
            return null;
        }
        try {
            Process process = new ProcessBuilder(diagnosis.qemuImg().toString(), "info",
                    "--output", "json", disk.toAbsolutePath().toString())
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            process.waitFor();
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("\"backing-filename\"\\s*:\\s*\"(.*?)\"").matcher(output);
            if (!matcher.find()) {
                return null;
            }
            String backing = matcher.group(1).replace("\\\\", "\\");
            String name = Path.of(backing).getFileName().toString();
            return name.endsWith(".qcow2") ? name.substring(0, name.length() - 6) : name;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
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
        try {
            Process process = new ProcessBuilder(
                    diagnosis.qemuImg().toString(), "snapshot", "-l", disk.toString())
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            process.waitFor();
            for (String line : output.split("\\R")) {
                String[] fields = line.trim().split("\\s+");
                if (fields.length >= 2 && SNAPSHOT.equals(fields[1])) {
                    return true;
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[vm] could not list snapshots on {}", disk, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            int status = process.waitFor();
            if (status != 0) {
                throw new IOException("qemu-img could not " + what + ": " + output.trim());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while trying to " + what, e);
        }
    }
}
