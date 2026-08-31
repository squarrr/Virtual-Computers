package squarrr.virtualcomputers.vm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class QemuVm {
    private static final Logger LOGGER = LoggerFactory.getLogger("vc.vm");

    private static final int MAX_DISPLAY = 64;

    private static final Pattern VNC_LINE =
            Pattern.compile("VNC server running on\\s+\\S*?:(\\d+)\\s*$");

    private static final int KEPT_LINES = 20;

    private final String machineId;
    private final VmSpec spec;
    private final Process process;
    private final int qmpPort;
    private final boolean resumed;

    private final Deque<String> recentOutput = new ArrayDeque<>();
    private final CountDownLatch announced = new CountDownLatch(1);
    private volatile int vncPort = -1;

    private QemuVm(String machineId, VmSpec spec, Process process, int qmpPort, boolean resumed) {
        this.machineId = machineId;
        this.spec = spec;
        this.process = process;
        this.qmpPort = qmpPort;
        this.resumed = resumed;
    }

    public static QemuVm start(String machineId, VmSpec spec, Path bootMedia) throws IOException {
        return start(machineId, spec, new BootPlan(null, bootMedia, bootMedia != null));
    }

    public record BootPlan(OsEntry entry, Path media, boolean bootFromMedia, Path seed) {
        public BootPlan(OsEntry entry, Path media, boolean bootFromMedia) {
            this(entry, media, bootFromMedia, null);
        }

        public static BootPlan none() {
            return new BootPlan(null, null, false, null);
        }
    }

    public static QemuVm start(String machineId, VmSpec spec, BootPlan plan) throws IOException {
        Hypervisor.Diagnosis diagnosis = Hypervisor.diagnose();
        if (!diagnosis.usable()) {
            throw new IOException(diagnosis.explanation() != null
                    ? diagnosis.explanation() : "no usable hypervisor");
        }
        OsEntry entry = plan.entry();
        if (entry != null) {
            String fault = diagnosis.whyNot(entry);
            if (fault != null) {
                throw new IOException(fault);
            }
        }
        VmStore.ensureDisk(machineId, entry, spec.diskGb());
        boolean resume = VmStore.hasSnapshot(machineId);
        int qmpPort = freePort();

        List<String> command = new ArrayList<>();
        command.add(diagnosis.qemuSystem().toString());
        command.add("-name");
        command.add("vc-" + machineId);
        command.add("-accel");
        command.add(diagnosis.effectiveAccelerator());
        command.add("-m");
        command.add(Integer.toString(spec.memoryMb()));

        int cores = Math.max(1, Math.min(spec.vcpu(), diagnosis.maxVcpus()));
        if (cores < spec.vcpu()) {
            LOGGER.info("[vm {}] {} cannot run {} vCPUs; using {}", machineId,
                    diagnosis.effectiveAccelerator(), spec.vcpu(), cores);
        }
        command.add("-smp");
        command.add(Integer.toString(cores));

        if (entry != null && entry.firmware() != OsEntry.Firmware.BIOS) {
            Path code = diagnosis.firmware(entry.firmware());
            Path vars = VmStore.firmwareVarsFor(machineId);
            command.add("-drive");
            command.add("if=pflash,format=raw,readonly=on,file=" + code);
            command.add("-drive");
            command.add("if=pflash,format=raw,file=" + vars);
            if (entry.firmware() == OsEntry.Firmware.UEFI_SECURE) {
                command.add("-machine");
                command.add("q35,smm=on");
                command.add("-global");
                command.add("driver=cfi.pflash01,property=secure,value=on");
            }
        }

        boolean diskMedia = plan.bootFromMedia() && plan.media() != null
                && entry != null && entry.media() == OsEntry.Media.DISK;
        if (diskMedia) {
            command.add("-drive");
            command.add("file=" + plan.media() + ",format=raw,index=0,media=disk");
        }
        command.add("-drive");
        command.add("file=" + VmStore.diskFor(machineId) + ",format=qcow2"
                + (diskMedia ? ",index=1" : ""));

        if (plan.seed() != null) {
            command.add("-drive");
            command.add(Provisioning.driveArgument(plan.seed()));
        }

        command.add("-device");
        command.add("qemu-xhci");
        command.add("-device");
        command.add("usb-tablet");

        if (resume) {
            command.add("-loadvm");
            command.add(VmStore.SNAPSHOT);
        } else if (plan.media() != null && !diskMedia) {
            command.add("-cdrom");
            command.add(plan.media().toString());
            if (plan.bootFromMedia()) {
                command.add("-boot");
                command.add("once=d");
            }
        }

        command.add("-vnc");
        command.add("127.0.0.1:1,to=" + MAX_DISPLAY);
        command.add("-qmp");
        command.add("tcp:127.0.0.1:" + qmpPort + ",server=on,wait=off");

        Path console = consoleLogFor(machineId);
        try {
            Files.createDirectories(console.getParent());
            Files.deleteIfExists(console);
            command.add("-serial");
            command.add("file:" + console);
        } catch (IOException e) {
            LOGGER.warn("[vm {}] no console log: {}", machineId, e.getMessage());
        }

        LOGGER.info("[vm {}] {} {} ({})", machineId,
                resume ? "resuming" : "cold boot", spec, diagnosis.effectiveAccelerator());
        LOGGER.debug("[vm {}] {}", machineId, String.join(" ", command));

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        QemuVm vm = new QemuVm(machineId, spec, process, qmpPort, resume);
        vm.pumpOutput();

        if (!vm.awaitDisplay(20_000)) {
            vm.kill();
            throw new IOException("QEMU would not open a display.\n" + vm.diagnostic());
        }
        LOGGER.info("[vm {}] display on 127.0.0.1:{}", machineId, vm.vncPort);
        return vm;
    }

    private void pumpOutput() {
        Thread reader = new Thread(() -> {
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    remember(line);
                    Matcher matcher = VNC_LINE.matcher(line.trim());
                    if (vncPort < 0 && matcher.find()) {
                        vncPort = Integer.parseInt(matcher.group(1));
                        announced.countDown();
                    }
                }
            } catch (IOException closed) {
            } finally {
                announced.countDown();
            }
        }, "vc-vm-out-" + machineId);
        reader.setDaemon(true);
        reader.start();

        Thread watcher = new Thread(() -> {
            try {
                int status = process.waitFor();
                announced.countDown();
                if (status != 0) {
                    LOGGER.warn("[vm {}] QEMU exited with status {}:\n{}", machineId, status, diagnostic());
                } else {
                    LOGGER.info("[vm {}] QEMU exited", machineId);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "vc-vm-watch-" + machineId);
        watcher.setDaemon(true);
        watcher.start();
    }

    private synchronized void remember(String line) {
        recentOutput.addLast(line);
        while (recentOutput.size() > KEPT_LINES) {
            recentOutput.removeFirst();
        }
    }

    public static Path consoleLogFor(String machineId) {
        return VmStore.root().resolve("consoles").resolve(machineId + ".log");
    }

    public String console() {
        try {
            Path log = consoleLogFor(machineId);
            return Files.isRegularFile(log) ? Files.readString(log, StandardCharsets.UTF_8) : "";
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    public synchronized String diagnostic() {
        return recentOutput.isEmpty() ? "QEMU said nothing at all." : String.join("\n", recentOutput);
    }

    private boolean awaitDisplay(long timeoutMs) {
        try {
            announced.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return vncPort > 0 && process.isAlive();
    }

    public int vncPort() {
        return vncPort;
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    public boolean resumed() {
        return resumed;
    }

    public boolean pause() {
        if (!process.isAlive()) {
            return false;
        }
        try (Qmp qmp = Qmp.connect("127.0.0.1", qmpPort, 5_000)) {
            qmp.execute("stop", null);
            LOGGER.info("[vm {}] paused", machineId);
            return true;
        } catch (IOException e) {
            LOGGER.warn("[vm {}] could not pause: {}", machineId, e.getMessage());
            return false;
        }
    }

    public boolean resume() {
        if (!process.isAlive()) {
            return false;
        }
        try (Qmp qmp = Qmp.connect("127.0.0.1", qmpPort, 5_000)) {
            qmp.execute("cont", null);
            LOGGER.info("[vm {}] resumed", machineId);
            return true;
        } catch (IOException e) {
            LOGGER.warn("[vm {}] could not resume: {}", machineId, e.getMessage());
            return false;
        }
    }

    public PointerDevice pointerDevice() {
        if (!process.isAlive()) {
            return null;
        }
        try (Qmp qmp = Qmp.connect("127.0.0.1", qmpPort, 3_000)) {
            com.google.gson.JsonElement result = qmp.executeRaw("query-mice", null);
            if (!result.isJsonArray()) {
                return null;
            }
            for (com.google.gson.JsonElement element : result.getAsJsonArray()) {
                com.google.gson.JsonObject mouse = element.getAsJsonObject();
                if (mouse.has("current") && mouse.get("current").getAsBoolean()) {
                    return new PointerDevice(
                            mouse.has("name") ? mouse.get("name").getAsString() : "unknown",
                            mouse.has("absolute") && mouse.get("absolute").getAsBoolean());
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("[vm {}] could not query pointer devices: {}", machineId, e.getMessage());
        }
        return null;
    }

    public record PointerDevice(String name, boolean absolute) {
        @Override
        public String toString() {
            return name + (absolute ? " (absolute)" : " (RELATIVE - the cursor will drift)");
        }
    }

    public String status() {
        if (!process.isAlive()) {
            return "stopped";
        }
        try (Qmp qmp = Qmp.connect("127.0.0.1", qmpPort, 2_000)) {
            com.google.gson.JsonObject result = qmp.execute("query-status", null);
            return result.has("status") ? result.get("status").getAsString() : "unknown";
        } catch (IOException e) {
            return "unreachable";
        }
    }

    public Persisted persist(long graceMs) {
        if (!process.isAlive()) {
            return Persisted.ALREADY_STOPPED;
        }
        try (Qmp qmp = Qmp.connect("127.0.0.1", qmpPort, 5_000)) {
            if (Hypervisor.diagnose().supportsSnapshots()) {
                qmp.humanMonitor("delvm " + VmStore.SNAPSHOT);
                String complaint = qmp.humanMonitor("savevm " + VmStore.SNAPSHOT);
                if (complaint.isBlank()) {
                    LOGGER.info("[vm {}] snapshot saved", machineId);
                    quitAndWait(qmp);
                    return Persisted.SNAPSHOT;
                }
                LOGGER.warn("[vm {}] savevm refused: {}", machineId, complaint.trim());
            }

            qmp.execute("cont", null);
            qmp.execute("system_powerdown", null);
            if (process.waitFor(graceMs, TimeUnit.MILLISECONDS)) {
                LOGGER.info("[vm {}] guest shut down cleanly", machineId);
                return Persisted.CLEAN_SHUTDOWN;
            }
            LOGGER.warn("[vm {}] guest ignored the power button after {} ms; stopping it anyway",
                    machineId, graceMs);
        } catch (IOException e) {
            LOGGER.warn("[vm {}] could not persist ({}); stopping it anyway", machineId, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        kill();
        return Persisted.KILLED;
    }

    public enum Persisted {
        SNAPSHOT,

        CLEAN_SHUTDOWN,

        KILLED,

        ALREADY_STOPPED
    }

    public void kill() {
        if (!process.isAlive()) {
            return;
        }
        try (Qmp qmp = Qmp.connect("127.0.0.1", qmpPort, 1_500)) {
            quitAndWait(qmp);
        } catch (IOException e) {
            LOGGER.debug("[vm {}] QMP unreachable, destroying the process", machineId);
        }
        if (process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    private void quitAndWait(Qmp qmp) {
        try {
            qmp.execute("quit", null);
        } catch (IOException e) {
            LOGGER.debug("[vm {}] quit closed the monitor, as it does", machineId);
        }
        try {
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket()) {
            probe.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1);
            return probe.getLocalPort();
        }
    }
}
