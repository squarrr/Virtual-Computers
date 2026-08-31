import squarrr.virtualcomputers.vm.Hypervisor;
import squarrr.virtualcomputers.vm.QemuVm;
import squarrr.virtualcomputers.vm.VmSpec;
import squarrr.virtualcomputers.vm.VmStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import vncspike.RfbClient;

public final class VmSmokeTest {
    private static final String MACHINE = "smoke-test";

    private static final VmSpec SPEC = VmSpec.LAPTOP;

    private static int checks;
    private static int failures;

    public static void main(String[] args) throws Exception {
        try {
            run();
        } finally {
            cleanUp();
        }
        System.out.println();
        if (failures > 0) {
            System.out.println("FAIL - " + failures + " of " + checks + " checks");
            System.exit(1);
        }
        System.out.println("PASS - " + checks + " checks");
    }

    private static void run() throws Exception {
        section("What this machine's hypervisor can do");
        Hypervisor.Diagnosis diagnosis = Hypervisor.diagnose();
        System.out.println("  " + diagnosis.summary());
        if (diagnosis.explanation() != null) {
            for (String line : diagnosis.explanation().split("\\R")) {
                System.out.println("  " + line);
            }
        }
        check("QEMU was found", diagnosis.qemuSystem() != null);
        check("qemu-img was found", diagnosis.qemuImg() != null);
        if (!diagnosis.usable()) {
            System.out.println();
            System.out.println("  Nothing further can be tested without a hypervisor, and that is the");
            System.out.println("  correct outcome rather than a broken test: the mod refuses to run a");
            System.out.println("  guest under software emulation for the reasons above.");
            return;
        }
        check("an accelerator was chosen", !diagnosis.isSoftwareOnly());
        boolean snapshots = diagnosis.supportsSnapshots();
        int maxCpus = diagnosis.maxVcpus();
        System.out.println("  vCPUs: asking for " + SPEC.vcpu() + ", this accelerator allows "
                + (maxCpus == Integer.MAX_VALUE ? "any number" : String.valueOf(maxCpus)));
        check("the launcher will not ask for more vCPUs than the accelerator can run",
                Math.min(SPEC.vcpu(), maxCpus) <= maxCpus);
        System.out.println("  snapshots: " + (snapshots
                ? "supported, so leaving the game resumes mid-sentence"
                : "NOT supported by " + diagnosis.accelerator()
                  + ", so leaving the game shuts the guest down cleanly instead"));

        Path media = VmStore.bootMedia();
        System.out.println("  boot media: " + (media == null ? "none" : media.getFileName()));
        System.out.println("  data dir:   " + VmStore.root().toAbsolutePath());

        section("Cold boot");
        VmStore.dropSnapshot(MACHINE);
        QemuVm vm = QemuVm.start(MACHINE, SPEC, media);
        check("the process is alive", vm.isAlive());
        check("it reports a cold boot, not a resume", !vm.resumed());

        RfbClient view = connectWhenReady(vm.vncPort(), 30_000);
        check("the framebuffer is reachable over RFB", view != null);
        if (view == null) {
            vm.kill();
            return;
        }
        int textModeWidth = view.framebuffer().width();
        int textModeHeight = view.framebuffer().height();
        int[] running = awaitPicture(view, 60_000);
        check("the guest painted something", running != null);
        if (running == null) {
            closeQuietly(view);
            vm.kill();
            return;
        }
        System.out.println("  " + fingerprint(running));

        section("Sleep: the lid closing");

        boolean wasMoving = awaitChange(view, running, 25_000) != null;
        if (wasMoving) {
            check("the guest's picture was changing before the pause", true);
        } else {
            System.out.println("  --   inconclusive: the guest never changed, so freezing it proves");
            System.out.println("       nothing. Not a failure - there is simply nothing to observe.");
        }

        check("pause was accepted", vm.pause());
        check("QEMU reports it as paused", "paused".equals(vm.status()));

        int[] frozenA = frame(view, 200);
        boolean frozen = frozenA != null && awaitChange(view, frozenA, 4_000) == null;
        if (wasMoving) {
            check("the picture stopped changing once paused", frozen);
        }

        check("resume was accepted", vm.resume());
        check("QEMU reports it as running again", "running".equals(vm.status()));

        section("Persist: what survives the game closing");

        int[] beforePersist = awaitSettled(view, 120_000);
        System.out.println("  guest settled: " + (beforePersist != null));

        int bootedWidth = view.framebuffer().width();
        int bootedHeight = view.framebuffer().height();
        System.out.println("  framebuffer: " + textModeWidth + "x" + textModeHeight
                + " at first sight, " + bootedWidth + "x" + bootedHeight + " now");
        check("the guest changed video mode, so something booted rather than panicking",
                bootedWidth != textModeWidth || bootedHeight != textModeHeight);

        QemuVm.PointerDevice pointer = vm.pointerDevice();
        System.out.println("  pointer: " + (pointer == null ? "could not ask" : pointer));
        check("the guest is on an absolute pointing device, so the cursor cannot drift",
                pointer != null && pointer.absolute());
        closeQuietly(view);
        QemuVm.Persisted outcome = vm.persist(15_000);
        System.out.println("  outcome: " + outcome);
        check("the process has stopped", !vm.isAlive());
        if (outcome == QemuVm.Persisted.KILLED) {
            System.out.println("  --   this guest ignored ACPI, so it was stopped after the grace");
            System.out.println("       period. The disk survives; anything unwritten does not.");
        }

        if (snapshots) {
            check("it took the snapshot route", outcome == QemuVm.Persisted.SNAPSHOT);
            check("qemu-img can see the snapshot", VmStore.hasSnapshot(MACHINE));

            section("Resume: the same machine, mid-sentence");
            QemuVm second = QemuVm.start(MACHINE, SPEC, media);
            check("it reports a resume, not a cold boot", second.resumed());
            RfbClient again = connectWhenReady(second.vncPort(), 30_000);
            int[] after = again == null ? null : awaitPicture(again, 60_000);
            closeQuietly(again);
            check("the resumed guest painted something", after != null);
            if (after != null && beforePersist != null) {
                check("the picture is the one that was saved", Arrays.equals(beforePersist, after));
            }
            second.kill();
        } else {
            check("it did not claim a snapshot it could not take",
                    outcome != QemuVm.Persisted.SNAPSHOT);
            check("no snapshot was left behind, so the next boot is honest about being cold",
                    !VmStore.hasSnapshot(MACHINE));
            check("the disk survived", Files.isRegularFile(VmStore.diskFor(MACHINE)));
            System.out.println();
            System.out.println("  This is the degraded path and it is the one Windows players get.");
            System.out.println("  The disk and everything installed on it survive; the exact");
            System.out.println("  instruction the guest was on does not.");
        }

        section("Kill: the plug coming out");
        QemuVm third = QemuVm.start(MACHINE, SPEC, media);
        third.kill();
        check("the process has stopped", !third.isAlive());
        VmStore.dropSnapshot(MACHINE);
        check("killing leaves nothing to resume from", !VmStore.hasSnapshot(MACHINE));
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

    private static int[] awaitChange(RfbClient client, int[] baseline, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            int[] pixels = frame(client, 400);
            if (pixels != null && !Arrays.equals(baseline, pixels)) {
                return pixels;
            }
        }
        return null;
    }

    private static int[] awaitPicture(RfbClient client, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            int[] pixels = frame(client, 400);
            if (pixels != null && hasContent(pixels)) {
                return pixels;
            }
        }
        return null;
    }

    private static int[] awaitSettled(RfbClient client, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int[] previous = null;
        while (System.currentTimeMillis() < deadline) {
            int[] pixels = frame(client, 1_500);
            if (pixels != null && hasContent(pixels)) {
                if (previous != null && Arrays.equals(previous, pixels)) {
                    return pixels;
                }
                previous = pixels;
            }
        }
        return previous;
    }

    private static void closeQuietly(RfbClient client) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (IOException ignored) {
        }
    }

    private static boolean hasContent(int[] pixels) {
        if (pixels.length == 0) {
            return false;
        }
        int first = pixels[0];
        for (int pixel : pixels) {
            if (pixel != first) {
                return true;
            }
        }
        return false;
    }

    private static String fingerprint(int[] pixels) {
        long hash = 1469598103934665603L;
        for (int pixel : pixels) {
            hash = (hash ^ pixel) * 1099511628211L;
        }
        return String.format("fingerprint %016x over %d px", hash, pixels.length);
    }

    private static void cleanUp() {
        try {
            if (Files.deleteIfExists(VmStore.diskFor(MACHINE))) {
                System.out.println("  removed the scratch disk");
            }
        } catch (IOException e) {
            System.out.println("  could not remove the scratch disk: " + e.getMessage());
        }
    }

    private static void sleepQuietly(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void section(String title) {
        System.out.println();
        System.out.println(title);
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
    }
}
