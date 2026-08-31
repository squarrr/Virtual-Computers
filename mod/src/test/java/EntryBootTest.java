import squarrr.virtualcomputers.vm.Hypervisor;
import squarrr.virtualcomputers.vm.ImageFetch;
import squarrr.virtualcomputers.vm.OsEntry;
import squarrr.virtualcomputers.vm.OsRegistry;
import squarrr.virtualcomputers.vm.QemuVm;
import squarrr.virtualcomputers.vm.Templates;
import squarrr.virtualcomputers.vm.VmSpec;
import squarrr.virtualcomputers.vm.VmStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import vncspike.RfbClient;

public final class EntryBootTest {
    private static final int TEXT_WIDTH = 720;
    private static final int TEXT_HEIGHT = 400;

    private static int checks;
    private static int failures;

    public static void main(String[] args) throws Exception {
        String id = System.getProperty("vc.entry", "tv");
        Hypervisor.Diagnosis diagnosis = Hypervisor.diagnose();
        if (!diagnosis.usable()) {
            System.out.println("No usable hypervisor:\n" + diagnosis.explanation());
            System.exit(0);
        }
        OsEntry entry = OsRegistry.get(id);
        if (entry == null) {
            System.out.println("No entry called " + id);
            System.exit(1);
        }
        String blocked = diagnosis.whyNot(entry);
        if (blocked != null) {
            System.out.println(entry.name() + " cannot run here, which is a fact rather than a"
                    + " failure:\n  " + blocked.replace("\n", "\n  "));
            System.exit(0);
        }
        Path shots = Path.of("entry-boot");
        Files.createDirectories(shots);
        System.out.println("Entry: " + entry + ", media " + entry.media());

        section("Getting it onto a machine the way a player would");

        Path media = null;
        if (entry.kind() != OsEntry.Kind.RECIPE) {
            media = ImageFetch.ensureMedia(entry, (done, total) -> { });
            check("media fetched and verified", Files.isRegularFile(media));
        }

        String machineId = "entry-boot-probe";
        Files.deleteIfExists(VmStore.diskFor(machineId));
        VmStore.dropSnapshot(machineId);

        QemuVm.BootPlan plan;
        if (entry.kind() == OsEntry.Kind.RECIPE) {
            OsEntry base = OsRegistry.get(entry.base());
            if (base == null || !Templates.exists(base.id())) {
                System.out.println("  " + entry.base() + " has no template yet, so there is nothing"
                        + " to build on. Run this for " + entry.base() + " first.");
                System.exit(0);
            }

            Templates.createOverlay(VmStore.diskFor(machineId),
                    Templates.forEntry(base.id()), entry.diskGb());
            check("cloned the base to build on", Files.isRegularFile(VmStore.diskFor(machineId)));
            plan = new QemuVm.BootPlan(entry, null, true,
                    squarrr.virtualcomputers.vm.Provisioning.seedFor(entry, machineId));
        } else if (entry.kind() == OsEntry.Kind.TEMPLATE) {
            if (!Templates.exists(entry.id())) {
                Templates.adopt(media, entry.id());
            }
            VmStore.ensureDisk(machineId, entry, entry.diskGb());
            check("cloned from the template", Files.isRegularFile(VmStore.diskFor(machineId)));
            plan = new QemuVm.BootPlan(entry, null, false, null);
        } else {
            VmStore.ensureDisk(machineId, null, entry.diskGb());
            check("blank disk to install onto", Files.isRegularFile(VmStore.diskFor(machineId)));
            plan = new QemuVm.BootPlan(entry, media, true, null);
        }

        long blank = Files.size(VmStore.diskFor(machineId));

        section("Switching it on");
        QemuVm vm = null;
        RfbClient client = null;
        try {
            vm = QemuVm.start(machineId, VmSpec.LAPTOP, plan);
            check("the machine started and opened a display", vm.vncPort() > 0);
            client = connectWhenReady(vm.vncPort(), 25_000);
            check("something is connected to it", client != null);
            if (client == null) {
                finish();
                return;
            }

            int[] early = settled(client, 25_000);
            write(shots.resolve(id + "-early.png"), client, early);
            check("it painted something that is not one flat colour", hasContent(early));

            int[] late = settled(client, 60_000);
            write(shots.resolve(id + "-late.png"), client, late);

            int width = client.framebuffer().width();
            int height = client.framebuffer().height();
            boolean text = width == TEXT_WIDTH && height == TEXT_HEIGHT;
            long grew = Files.size(VmStore.diskFor(machineId)) - blank;
            System.out.printf("          settled at %dx%d (%s); the machine's own disk grew by %s%n",
                    width, height, text ? "VGA text" : "graphical", Templates.human(Math.max(0, grew)));

            if (entry.kind() == OsEntry.Kind.TEMPLATE) {
                long start = System.currentTimeMillis();
                String console = "";
                boolean booted = false;
                while (System.currentTimeMillis() - start < 180_000) {
                    console = vm.console();
                    booted = console.contains("Reached target") || console.contains("login:")
                            || console.contains("systemd") || console.contains("Linux version");
                    if (booted || !vm.isAlive()) {
                        break;
                    }
                    sleepQuietly(3_000);
                }
                long took = (System.currentTimeMillis() - start) / 1000;
                System.out.printf("          console: %d bytes after %d s of waiting%n",
                        console.length(), took);
                if (!booted && !console.isBlank()) {
                    console.lines().limit(8).forEach(l -> System.out.println("            " + l));
                }
                check("a TEMPLATE boots an operating system, which says so on its console", booted);
            } else if (entry.kind() == OsEntry.Kind.RECIPE) {
                long start = System.currentTimeMillis();
                while (vm.isAlive() && System.currentTimeMillis() - start < 600_000) {
                    sleepQuietly(5_000);
                }
                long took = (System.currentTimeMillis() - start) / 1000;
                String console = vm.console();
                System.out.printf("          console: %d bytes; %s after %d s%n", console.length(),
                        vm.isAlive() ? "still running" : "powered itself off", took);
                check("cloud-init found the seed", console.contains("cloud-init")
                        || console.contains("Cloud-init"));
                check("the guest powered ITSELF off, which is how a finished build reports itself",
                        !vm.isAlive());
                check("the build wrote to the machine's disk",
                        Files.size(VmStore.diskFor(machineId)) - blank > 256 * 1024);
            } else {
                check("an INSTALLER reaches a screen, which may legitimately be a text menu",
                        hasContent(late));
                check("and it is still alive after a minute", vm.isAlive());
            }
        } finally {
            closeQuietly(client);
            if (vm != null) {
                vm.kill();
            }
            Files.deleteIfExists(VmStore.diskFor(machineId));
        }
        System.out.println("\n  screenshots in " + shots.toAbsolutePath());
        finish();
    }

    private static void finish() {
        System.out.printf("%n%d checks, %d failures%n", checks, failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void write(Path path, RfbClient client, int[] pixels) {
        if (pixels == null) {
            return;
        }
        try {
            int w = client.framebuffer().width();
            int h = client.framebuffer().height();
            if (w * h != pixels.length) {
                return;
            }
            var image = new java.awt.image.BufferedImage(w, h,
                    java.awt.image.BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, w, h, pixels, 0, w);
            javax.imageio.ImageIO.write(image, "png", path.toFile());
            System.out.println("          wrote " + path.getFileName() + " (" + w + "x" + h + ")");
        } catch (IOException e) {
            System.out.println("          could not write " + path + ": " + e.getMessage());
        }
    }

    private static int[] settled(RfbClient client, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int[] previous = null;
        int stable = 0;
        while (System.currentTimeMillis() < deadline) {
            int[] pixels = frame(client, 1_000);
            if (pixels == null) {
                continue;
            }
            if (previous != null && previous.length == pixels.length
                    && Arrays.equals(previous, pixels)) {
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

    private static boolean hasContent(int[] pixels) {
        if (pixels == null || pixels.length == 0) {
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
        checks++;
        System.out.println((ok ? "  ok    " : "  FAIL  ") + what);
        if (!ok) {
            failures++;
        }
    }
}
