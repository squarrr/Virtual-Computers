package vncspike;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class Spike {
    private static final int IDLE_TIMEOUT_MILLIS = 5_000;

    public static void main(String[] args) throws Exception {
        String host = null;
        int port = 5900;
        int frames = 30;
        Path outDir = Path.of("frames");
        int width = 640, height = 480;
        int servePort = -1;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--frames" -> frames = Integer.parseInt(args[++i]);
                case "--out" -> outDir = Path.of(args[++i]);
                case "--size" -> {
                    String[] wh = args[++i].split("x");
                    width = Integer.parseInt(wh[0]);
                    height = Integer.parseInt(wh[1]);
                }
                case "--serve" -> {
                    servePort = (i + 1 < args.length && !args[i + 1].startsWith("-"))
                            ? Integer.parseInt(args[++i]) : 5902;
                }
                case "--help", "-h" -> { printUsage(); return; }
                default -> {
                    System.err.println("Unknown argument: " + args[i]);
                    printUsage();
                    System.exit(2);
                }
            }
        }

        if (servePort >= 0) {
            try (RfbTestServer only = new RfbTestServer(servePort, width, height)) {
                System.out.printf("Serving the %dx%d test pattern on 127.0.0.1:%d%n",
                        width, height, only.port());
                System.out.println();
                System.out.println("  Put it on a block:");
                System.out.printf("    .\\mod\\run-client.cmd 127.0.0.1:%d%n", only.port());
                System.out.println();
                System.out.println("Ctrl+C to stop.");
                Thread.currentThread().join();
            }
            return;
        }

        boolean selfTest = (host == null);
        RfbTestServer server = null;
        if (selfTest) {
            server = new RfbTestServer(0, width, height);
            host = "127.0.0.1";
            port = server.port();
            System.out.println("Built-in test server on 127.0.0.1:" + port + " (loopback only)");
        }

        System.out.println("Connecting to " + host + ":" + port + " ...");
        Files.createDirectories(outDir);

        try (java.net.Socket probe = selfTest ? null : new java.net.Socket()) {
            if (probe != null) {
                probe.connect(new java.net.InetSocketAddress(host, port), 3000);
            }
        } catch (IOException e) {
            System.out.println();
            System.out.println("Nothing is listening on " + host + ":" + port + ".");
            if (port >= 5900 && port <= 5910) {
                System.out.println("  QEMU's -vnc :" + (port - 5900) + " is port " + port + ".");
                System.out.println("  Start a VM first:  .\\scripts\\run-vm.cmd");
            }
            System.out.println("  Or omit --host to run against the built-in test server.");
            System.exit(1);
        }

        List<String> failures = new ArrayList<>();
        long[] decodeNanos = new long[frames];
        Set<Long> checksums = new LinkedHashSet<>();
        int captured = 0;

        try (RfbClient client = RfbClient.connect(host, port, 5000)) {
            Framebuffer fb = client.framebuffer();
            System.out.printf("  framebuffer: %dx%d  (%.1f MB per frame as ARGB)%n",
                    fb.width(), fb.height(), fb.width() * fb.height() * 4 / 1e6);
            System.out.println();

            client.setIdleTimeout(IDLE_TIMEOUT_MILLIS);

            final int frameCount = frames;
            final Path dir = outDir;
            long[] waitNanos = new long[frameCount];
            boolean wentQuiet = false;
            long wallStart = System.nanoTime();
            for (int i = 0; i < frameCount; i++) {
                client.requestUpdate(i > 0);
                final int index = i;
                try {
                    client.readMessage((frame, number) -> {
                        if (index == 0 || index == frameCount - 1 || index == frameCount / 2) {
                            writePng(frame, dir.resolve(String.format("frame-%03d.png", index)));
                        }
                    });
                } catch (java.net.SocketTimeoutException e) {
                    wentQuiet = true;
                    break;
                }
                waitNanos[i] = client.lastWaitNanos();
                decodeNanos[i] = client.lastDecodeNanos();
                checksums.add(fb.checksum());
                captured++;
            }
            long wallNanos = System.nanoTime() - wallStart;
            if (wentQuiet) {
                System.out.printf("Screen stopped changing after %d frame(s); stopped waiting at %d ms.%n",
                        captured, IDLE_TIMEOUT_MILLIS);
                System.out.println("  That is the server being idle, not a fault - RFB answers an");
                System.out.println("  incremental request only when something actually moves.");
                System.out.println();
                decodeNanos = java.util.Arrays.copyOf(decodeNanos, Math.max(1, captured));
                waitNanos = java.util.Arrays.copyOf(waitNanos, Math.max(1, captured));
            }

            System.out.println("RESULTS");
            System.out.printf("  frames decoded      %d%n", captured);
            System.out.printf("  distinct frames     %d%s%n", checksums.size(),
                    checksums.size() == 1 ? "   <-- stream is static!" : "");
            System.out.printf("  decode, median      %.2f ms   (ours)%n", median(decodeNanos) / 1e6);
            System.out.printf("  decode, worst       %.2f ms%n", max(decodeNanos) / 1e6);
            System.out.printf("  server wait, median %.2f ms   (theirs)%n", median(waitNanos) / 1e6);
            System.out.printf("  throughput          %.1f MB/s decoded%n",
                    (double) captured * fb.width() * fb.height() * 4
                            / (sum(decodeNanos) / 1e9) / 1e6);
            System.out.printf("  wall clock          %.2f ms total, %.1f fps%n",
                    wallNanos / 1e6, captured * 1e9 / wallNanos);
            System.out.printf("  PNGs written to     %s%n", outDir.toAbsolutePath());
            System.out.println();

            if (checksums.size() < 2 && !wentQuiet) {
                failures.add("Every frame was identical - the stream is not live.");
            }
            if (selfTest) {
                checkPattern(fb, failures);
                checkInputEcho(client, fb, failures);
            }
        } finally {
            if (server != null) server.close();
        }

        System.out.println(failures.isEmpty()
                ? "PASS - transport proven. Pixels arrive, frames change"
                  + (selfTest ? ", channels and orientation are correct, and input reaches the far end." : ".")
                : "FAIL");
        for (String f : failures) System.out.println("  - " + f);
        if (!failures.isEmpty()) System.exit(1);
    }

    private static void checkPattern(Framebuffer fb, List<String> failures) {
        int w = fb.width(), h = fb.height();
        int[] px = fb.pixels();

        int redBarX = (int) ((5 + 0.5) * w / 8);
        int barsY = h * 20 / 100;
        int sample = px[barsY * w + redBarX];
        int r = sample >> 16 & 0xFF, g = sample >> 8 & 0xFF, b = sample & 0xFF;
        if (!(r > 200 && g < 60 && b < 60)) {
            failures.add(String.format(
                    "Red bar decoded as r=%d g=%d b=%d. %s", r, g, b,
                    (b > 200 && r < 60) ? "Red and blue are transposed." : "Channel decoding is wrong."));
        }

        int corner = px[2 * w + 2];
        if ((corner >> 16 & 0xFF) < 200 || (corner & 0xFF) > 60) {
            failures.add("Top-left orientation marker is missing - the image may be flipped.");
        }
        int bottomLeft = px[(h - 3) * w + 2];
        if ((bottomLeft >> 16 & 0xFF) > 200 && (bottomLeft & 0xFF) < 60) {
            failures.add("Orientation marker found bottom-left - the image is vertically flipped.");
        }
    }

    private static void checkInputEcho(RfbClient client, Framebuffer fb, List<String> failures)
            throws IOException {
        int w = fb.width(), h = fb.height();
        int cx = w / 2, cy = h / 2;

        client.pointerEvent(cx, cy, 1);
        client.keyEvent(0x0061, true);
        client.requestUpdate(false);
        client.readMessage(null);

        int[] px = fb.pixels();

        int arm = px[cy * w + cx + 6];
        if (arm != 0xFFF0A244) {
            failures.add(String.format(
                    "Pointer echo missing at (%d,%d): expected the crosshair's %06X, found %06X. %s",
                    cx + 6, cy, 0xF0A244, arm & 0xFFFFFF,
                    px[(cy + 6) * w + cx] == 0xFFF0A244 ? "It is at the transposed position - x and y are swapped."
                            : "The pointer event did not arrive, or its coordinates are wrong."));
        }

        int panelX = w * 6 / 100, panelY = h * 62 / 100;
        int green = 0;
        for (int y = panelY; y < Math.min(h, panelY + 16); y++) {
            for (int x = panelX; x < Math.min(w, panelX + 60); x++) {
                if (px[y * w + x] == 0xFF6BE675) {
                    green++;
                }
            }
        }
        if (green < 20) {
            failures.add("Key echo missing: the keysym readout is not lit, so KeyEvent did not arrive.");
        }
    }

    private static void writePng(Framebuffer fb, Path path) {
        try {
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                    fb.width(), fb.height(), java.awt.image.BufferedImage.TYPE_INT_RGB);
            img.setRGB(0, 0, fb.width(), fb.height(), fb.pixels(), 0, fb.width());
            ImageIO.write(img, "png", new File(path.toString()));
        } catch (IOException e) {
            System.err.println("  could not write " + path + ": " + e.getMessage());
        }
    }

    private static long median(long[] values) {
        long[] copy = values.clone();
        java.util.Arrays.sort(copy);
        return copy.length == 0 ? 0 : copy[copy.length / 2];
    }

    private static long max(long[] values) {
        long m = 0;
        for (long v : values) m = Math.max(m, v);
        return m;
    }

    private static long sum(long[] values) {
        long s = 0;
        for (long v : values) s += v;
        return Math.max(1, s);
    }

    private static void printUsage() {
        System.out.println("""
                vnc-spike - phase 0, step 2: prove the transport

                  --host <addr>   connect to an external VNC server (default: built-in test server)
                  --port <n>      port (default 5900; QEMU's ':1' is 5901)
                  --frames <n>    frames to pull (default 30)
                  --out <dir>     where to write sample PNGs (default ./frames)
                  --size <WxH>    built-in server resolution (default 640x480)
                  --serve [port]  serve the test pattern only and stay up (default 5902),
                                  so the mod can be pointed at real VNC with content that
                                  is not almost entirely black
                """);
    }
}
