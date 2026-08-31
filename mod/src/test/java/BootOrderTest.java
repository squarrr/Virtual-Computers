import squarrr.virtualcomputers.vm.Hypervisor;
import squarrr.virtualcomputers.vm.Qmp;
import squarrr.virtualcomputers.vm.VmStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import vncspike.RfbClient;

public final class BootOrderTest {
    private static int checks;
    private static int failures;

    public static void main(String[] args) throws Exception {
        Hypervisor.Diagnosis diagnosis = Hypervisor.diagnose();
        if (!diagnosis.usable()) {
            System.out.println("No usable hypervisor, so there is nothing to measure:\n"
                    + diagnosis.explanation());
            System.exit(0);
        }
        Path iso = anyBootableIso();
        if (iso == null) {
            System.out.println("No .iso in " + VmStore.root().resolve("images")
                    + "\n  This check needs bootable media to compare against. Skipped.");
            System.exit(0);
        }
        System.out.println("Booting " + iso.getFileName() + " twice, once per flag.\n");

        Run once = new Run("once=d", iso).go();
        Run always = new Run("d", iso).go();

        System.out.printf("%n  screen change across the reset:  once=d %.2f%%   d %.2f%%%n%n",
                changed(once.first, once.second) * 100, changed(always.first, always.second) * 100);

        section("The control: both flags boot the CD first");
        check("once=d reached a first screen", once.first != null);
        check("d reached a first screen", always.first != null);
        if (once.first == null || always.first == null) {
            report();
            return;
        }

        check("both reached the same first screen",
                similar(once.first, always.first), report(once.first, always.first));

        section("After a reset, the flags disagree - which is the whole point");

        check("d went back to the installer",
                once.second != null && always.second != null && similar(always.first, always.second),
                report(always.first, always.second));
        check("once=d did NOT go back to the installer",
                once.second != null && different(once.first, once.second),
                report(once.first, once.second));

        report();
    }

    private static void report() {
        System.out.printf("%n%d checks, %d failures%n", checks, failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    private static byte[] bootSector() {
        byte[] sector = new byte[512];
        byte[] code = {(byte) 0xB4, 0x0E, (byte) 0xB0, 0x58, (byte) 0xCD, 0x10, (byte) 0xEB, (byte) 0xFE};
        System.arraycopy(code, 0, sector, 0, code.length);
        sector[510] = 0x55;
        sector[511] = (byte) 0xAA;
        return sector;
    }

    private static double changed(int[] a, int[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 1.0;
        }
        int differing = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                differing++;
            }
        }
        return differing / (double) a.length;
    }

    private static boolean similar(int[] a, int[] b) {
        return changed(a, b) < 0.05;
    }

    private static boolean different(int[] a, int[] b) {
        return changed(a, b) > 0.20;
    }

    private static String report(int[] a, int[] b) {
        return String.format("%.1f%% of the screen differs", changed(a, b) * 100);
    }

    private static final class Run {
        private final String flag;
        private final Path iso;
        int[] first;
        int[] second;

        Run(String flag, Path iso) {
            this.flag = flag;
            this.iso = iso;
        }

        Run go() throws Exception {
            Path disk = Files.createTempFile("vc-boot-" + flag.replace('=', '-'), ".raw");
            Files.write(disk, bootSector());
            Hypervisor.Diagnosis diagnosis = Hypervisor.diagnose();

            String label = "vc-bootorder-" + flag.replace("=", "-");
            int qmpPort = freePort();
            List<String> command = new ArrayList<>(List.of(
                    diagnosis.qemuSystem().toString(),
                    "-name", label,
                    "-qmp", "tcp:127.0.0.1:" + qmpPort + ",server=on,wait=off",
                    "-accel", diagnosis.effectiveAccelerator(),
                    "-m", "1024",
                    "-smp", "1",
                    "-drive", "file=" + disk + ",format=raw",
                    "-cdrom", iso.toString(),
                    "-boot", flag,

                    "-vnc", "127.0.0.1:1,to=64",
                    "-monitor", "none"));

            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();

            StringBuilder output = new StringBuilder();
            java.util.concurrent.CountDownLatch announced = new java.util.concurrent.CountDownLatch(1);
            int[] port = {-1};
            Thread reader = new Thread(() -> {
                try (var in = new java.io.BufferedReader(new java.io.InputStreamReader(
                        process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        synchronized (output) {
                            output.append(line).append('\n');
                        }
                        var matcher = java.util.regex.Pattern
                                .compile("VNC server running on .*?:(\\d+)").matcher(line);
                        if (port[0] < 0 && matcher.find()) {
                            port[0] = Integer.parseInt(matcher.group(1));
                            announced.countDown();
                        }
                    }
                } catch (IOException ignored) {
                } finally {
                    announced.countDown();
                }
            });
            reader.setDaemon(true);
            reader.start();
            announced.await(20, java.util.concurrent.TimeUnit.SECONDS);

            RfbClient client = null;
            try {
                client = port[0] > 0 ? connectWhenReady(port[0], 25_000) : null;
                if (client == null) {
                    System.out.println("  " + flag + ": never opened a display. QEMU said:");
                    synchronized (output) {
                        for (String line : output.toString().split("\\R")) {
                            if (!line.isBlank()) {
                                System.out.println("      " + line);
                            }
                        }
                    }
                    return this;
                }

                first = settled(client, 14_000);

                try (Qmp qmp = Qmp.connect("127.0.0.1", qmpPort, 8_000)) {
                    qmp.execute("system_reset", null);
                }
                second = settled(client, 18_000);
            } finally {
                closeQuietly(client);
                process.destroyForcibly();

                process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
                deleteQuietly(disk);
            }
            System.out.printf("  %-7s first %s%n          after reset %s%n", flag,
                    describe(first), describe(second));
            return this;
        }
    }

    private static int freePort() throws IOException {
        try (java.net.ServerSocket socket =
                     new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private static String describe(int[] pixels) {
        if (pixels == null) {
            return "(nothing)";
        }
        long hash = 1469598103934665603L;
        for (int pixel : pixels) {
            hash = (hash ^ pixel) * 1099511628211L;
        }
        return String.format("%016x over %d px", hash, pixels.length);
    }

    private static int[] settled(RfbClient client, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int[] previous = null;
        int stable = 0;
        while (System.currentTimeMillis() < deadline) {
            int[] pixels = frame(client, 900);
            if (pixels == null) {
                continue;
            }
            if (previous != null && Arrays.equals(previous, pixels)) {
                if (++stable >= 2) {
                    return pixels;
                }
            } else {
                stable = 0;
            }
            previous = pixels;
        }
        return previous;
    }

    private static int[] frame(RfbClient client, long settleMs) {
        sleepQuietly(settleMs);
        try {
            client.requestUpdate(false);
            client.readMessage(null);
            return client.framebuffer().pixels().clone();
        } catch (IOException idle) {
            return null;
        }
    }

    private static RfbClient connectWhenReady(int vncPort, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                RfbClient client = RfbClient.connect("127.0.0.1", vncPort, 3_000);
                client.setIdleTimeout(4_000);
                return client;
            } catch (IOException notUpYet) {
                sleepQuietly(400);
            }
        }
        return null;
    }

    private static Path anyBootableIso() {
        try (Stream<Path> found = Files.list(VmStore.root().resolve("images"))) {
            return found.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".iso"))
                    .min(Comparator.comparing(p -> p.getFileName().toString()))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static void exec(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        process.waitFor();
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            path.toFile().deleteOnExit();
        }
    }

    private static void closeQuietly(RfbClient client) {
        if (client != null) {
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void section(String title) {
        System.out.println("\n" + title);
    }

    private static void check(String what, boolean ok) {
        check(what, ok, null);
    }

    private static void check(String what, boolean ok, String measured) {
        checks++;
        System.out.println((ok ? "  ok    " : "  FAIL  ") + what
                + (measured == null ? "" : "  [" + measured + "]"));
        if (!ok) {
            failures++;
        }
    }
}
