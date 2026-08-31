package squarrr.virtualcomputers.screen;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import squarrr.virtualcomputers.VirtualComputers;
import vncspike.CursorShape;
import vncspike.Framebuffer;
import vncspike.RfbClient;

public final class RfbScreenSource implements ScreenSource, ScreenInput {
    private final String host;
    private final int port;
    private final Object lock = new Object();
    private final Thread thread;

    private final Set<Integer> heldKeys = ConcurrentHashMap.newKeySet();

    private volatile int width = 640;
    private volatile int height = 480;

    private int[] clean = new int[640 * 480];

    private volatile int[] pixels = new int[640 * 480];
    private int cursorX = -1;
    private int cursorY = -1;

    private int[] cursorDrawn;
    private volatile long generation;
    private volatile String status = "connecting";
    private volatile boolean running = true;
    private volatile RfbClient client;

    public RfbScreenSource(String host, int port) {
        this.host = host;
        this.port = port;
        paintNoSignal();
        this.thread = new Thread(this::run, "vc-rfb-" + host + ":" + port);
        this.thread.setDaemon(true);
        this.thread.start();
    }

    private void paintNoSignal() {
        int[] px = pixels;
        int w = width;
        for (int i = 0; i < px.length; i++) {
            int x = i % w, y = i / w;
            px[i] = (((x + y) / 24) & 1) == 0 ? 0xFF10243C : 0xFF16304F;
        }
    }

    private void run() {
        while (running) {
            try (RfbClient connection = RfbClient.connect(host, port, 5000)) {
                Framebuffer initial = connection.framebuffer();
                synchronized (lock) {
                    width = initial.width();
                    height = initial.height();
                    clean = new int[width * height];
                    pixels = new int[width * height];
                    cursorDrawn = null;
                }
                heldKeys.clear();
                client = connection;
                status = "connected " + width + "x" + height;

                connection.requestUpdate(false);
                while (running) {
                    connection.readMessage(null);
                    connection.requestUpdate(true);

                    Framebuffer fb = connection.framebuffer();
                    synchronized (lock) {
                        if (fb.width() != width || fb.height() != height) {
                            width = fb.width();
                            height = fb.height();
                            clean = new int[width * height];
                            pixels = new int[width * height];
                            cursorDrawn = null;
                            VirtualComputers.LOGGER.info("[screen] guest resized to {}x{}", width, height);
                        }
                        System.arraycopy(fb.pixels(), 0, clean, 0, clean.length);
                        System.arraycopy(clean, 0, pixels, 0, pixels.length);
                        cursorDrawn = null;
                        drawCursor(connection.cursor());
                    }
                    generation++;
                }
            } catch (Exception e) {
                client = null;
                cursorDrawn = null;
                status = "no signal (" + e.getMessage() + ")";
                VirtualComputers.LOGGER.warn("[screen] {}:{} — {}", host, port, status);
                synchronized (lock) {
                    if (pixels.length != width * height) {
                        pixels = new int[width * height];
                        clean = new int[width * height];
                    }
                    paintNoSignal();
                }
                generation++;
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        client = null;
    }

    @Override public int width() { return width; }
    @Override public int height() { return height; }
    @Override public long generation() { return generation; }
    @Override public int[] pixels() { return pixels; }
    @Override public Object lock() { return lock; }
    @Override public String status() { return status; }

    @Override
    public void keyEvent(int keysym, boolean down) {
        if (down) {
            heldKeys.add(keysym);
        } else {
            heldKeys.remove(keysym);
        }
        send(connection -> connection.keyEvent(keysym, down));
    }

    @Override
    public void pointerEvent(int x, int y, int buttonMask) {
        send(connection -> connection.pointerEvent(x, y, buttonMask));

        RfbClient connection = client;
        if (connection == null) {
            return;
        }
        synchronized (lock) {
            cursorX = x;
            cursorY = y;
            restoreUnderCursor();
            drawCursor(connection.cursor());
        }
        generation++;
    }

    private void restoreUnderCursor() {
        if (cursorDrawn == null) {
            return;
        }
        for (int y = cursorDrawn[1]; y < cursorDrawn[3]; y++) {
            int row = y * width;
            System.arraycopy(clean, row + cursorDrawn[0], pixels, row + cursorDrawn[0],
                    cursorDrawn[2] - cursorDrawn[0]);
        }
        cursorDrawn = null;
    }

    private void drawCursor(CursorShape cursor) {
        if (cursorX >= 0) {
            cursorDrawn = cursor.drawInto(pixels, width, height, cursorX, cursorY);
        }
    }

    @Override
    public void releaseAllKeys() {
        for (Integer keysym : heldKeys) {
            send(connection -> connection.keyEvent(keysym, false));
        }
        heldKeys.clear();
    }

    private void send(RfbWrite write) {
        RfbClient connection = client;
        if (connection == null) {
            return;
        }
        try {
            write.accept(connection);
        } catch (IOException e) {
            status = "input failed (" + e.getMessage() + ")";
        }
    }

    private interface RfbWrite {
        void accept(RfbClient connection) throws IOException;
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
    }
}
