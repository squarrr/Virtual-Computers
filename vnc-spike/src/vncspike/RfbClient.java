package vncspike;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class RfbClient implements AutoCloseable {
    public interface FrameListener {
        void onFrame(Framebuffer fb, int frameNumber);
    }

    private static final int ENC_RAW = 0;
    private static final int ENC_COPY_RECT = 1;

    private static final int ENC_CURSOR = -239;
    private static final int ENC_DESKTOP_SIZE = -223;

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;

    private final String serverName;
    private Framebuffer framebuffer;
    private byte[] rowScratch;
    private final CursorShape cursor = new CursorShape();
    private int desktopChanges;

    private int frameNumber;

    private RfbClient(Socket socket, DataInputStream in, DataOutputStream out,
                      String serverName, Framebuffer framebuffer) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.serverName = serverName;
        this.framebuffer = framebuffer;
        this.rowScratch = new byte[framebuffer.width() * 4];
    }

    public static RfbClient connect(String host, int port, int timeoutMillis) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), timeoutMillis);
        socket.setTcpNoDelay(true);

        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 1 << 16));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 1 << 12));

        try {
            byte[] versionBytes = new byte[12];
            in.readFully(versionBytes);
            String serverVersion = new String(versionBytes, StandardCharsets.US_ASCII).trim();
            if (!serverVersion.startsWith("RFB ")) {
                throw new IOException("Not an RFB server, greeting was: " + serverVersion);
            }
            out.write("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();

            int securityTypeCount = in.readUnsignedByte();
            if (securityTypeCount == 0) {
                throw new IOException("Server refused connection: " + readString(in));
            }
            byte[] securityTypes = new byte[securityTypeCount];
            in.readFully(securityTypes);
            boolean supportsNone = false;
            for (byte t : securityTypes) {
                if (t == 1) { supportsNone = true; break; }
            }
            if (!supportsNone) {
                throw new IOException("Server requires authentication; the spike only speaks 'None'. "
                        + "Start QEMU without a VNC password.");
            }
            out.writeByte(1);
            out.flush();

            int securityResult = in.readInt();
            if (securityResult != 0) {
                throw new IOException("Security handshake failed: " + readString(in));
            }

            out.writeByte(1);
            out.flush();

            int width = in.readUnsignedShort();
            int height = in.readUnsignedShort();
            PixelFormat serverFormat = PixelFormat.read(in);
            String name = readString(in);

            System.out.printf("  connected: %s  %dx%d  server offered %s%n",
                    name.isEmpty() ? "(unnamed)" : name, width, height, serverFormat);

            out.writeByte(0);
            out.write(new byte[3]);
            PixelFormat.STANDARD_32BPP.write(out);

            out.writeByte(2);
            out.writeByte(0);
            out.writeShort(4);
            out.writeInt(ENC_RAW);
            out.writeInt(ENC_COPY_RECT);

            out.writeInt(ENC_CURSOR);
            out.writeInt(ENC_DESKTOP_SIZE);
            out.flush();

            return new RfbClient(socket, in, out, name, new Framebuffer(width, height));
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    public Framebuffer framebuffer() { return framebuffer; }

    public CursorShape cursor() { return cursor; }

    public int desktopChanges() { return desktopChanges; }
    public String serverName() { return serverName; }

    private final Object writeLock = new Object();

    public void requestUpdate(boolean incremental) throws IOException {
        synchronized (writeLock) {
            out.writeByte(3);
            out.writeByte(incremental ? 1 : 0);
            out.writeShort(0);
            out.writeShort(0);
            out.writeShort(framebuffer.width());
            out.writeShort(framebuffer.height());
            out.flush();
        }
    }

    public void keyEvent(int keysym, boolean down) throws IOException {
        synchronized (writeLock) {
            out.writeByte(4);
            out.writeByte(down ? 1 : 0);
            out.writeShort(0);
            out.writeInt(keysym);
            out.flush();
        }
    }

    public void pointerEvent(int x, int y, int buttonMask) throws IOException {
        int cx = Math.max(0, Math.min(framebuffer.width() - 1, x));
        int cy = Math.max(0, Math.min(framebuffer.height() - 1, y));
        synchronized (writeLock) {
            out.writeByte(5);
            out.writeByte(buttonMask & 0xFF);
            out.writeShort(cx);
            out.writeShort(cy);
            out.flush();
        }
    }

    public long lastWaitNanos() { return lastWaitNanos; }

    public long lastDecodeNanos() { return lastDecodeNanos; }

    private long lastWaitNanos;
    private long lastDecodeNanos;

    public void setIdleTimeout(int millis) throws IOException {
        this.idleTimeoutMillis = Math.max(0, millis);
        socket.setSoTimeout(0);
    }

    private int idleTimeoutMillis;

    public void readMessage(FrameListener listener) throws IOException {
        long requestedAt = System.nanoTime();
        int messageType;

        try {
            if (idleTimeoutMillis > 0) {
                socket.setSoTimeout(idleTimeoutMillis);
            }
            messageType = in.readUnsignedByte();
        } finally {
            if (idleTimeoutMillis > 0) {
                socket.setSoTimeout(0);
            }
        }
        long firstByteAt = System.nanoTime();
        lastWaitNanos = firstByteAt - requestedAt;
        try {
            dispatch(messageType, listener);
        } finally {
            lastDecodeNanos = System.nanoTime() - firstByteAt;
        }
    }

    private void dispatch(int messageType, FrameListener listener) throws IOException {
        switch (messageType) {
            case 0 -> {
                in.skipNBytes(1);
                int rectCount = in.readUnsignedShort();
                for (int i = 0; i < rectCount; i++) {
                    readRectangle();
                }
                if (listener != null) {
                    listener.onFrame(framebuffer, ++frameNumber);
                }
            }
            case 1 -> {
                in.skipNBytes(3);
                int count = in.readUnsignedShort();
                in.skipNBytes(count * 6L);
            }
            case 2 -> {  }
            case 3 -> {
                in.skipNBytes(3);
                readString(in);
            }
            default -> throw new IOException("Unknown server message type " + messageType
                    + " - the stream is now out of sync and cannot be recovered.");
        }
    }

    private void readRectangle() throws IOException {
        int x = in.readUnsignedShort();
        int y = in.readUnsignedShort();
        int w = in.readUnsignedShort();
        int h = in.readUnsignedShort();
        int encoding = in.readInt();
        if (System.getProperty("rfb.trace") != null) {
            System.out.printf("    rect %dx%d at %d,%d encoding %d%n", w, h, x, y, encoding);
        }

        switch (encoding) {
            case ENC_RAW -> readRaw(x, y, w, h);
            case ENC_COPY_RECT -> {
                int srcX = in.readUnsignedShort();
                int srcY = in.readUnsignedShort();
                framebuffer.copyRect(srcX, srcY, x, y, w, h);
            }

            case ENC_CURSOR -> readCursor(x, y, w, h);
            case ENC_DESKTOP_SIZE -> {
                framebuffer = new Framebuffer(w, h);
                rowScratch = new byte[w * 4];
                desktopChanges++;
            }
            default -> throw new IOException("Server used encoding " + encoding
                    + " which was never negotiated; refusing to guess at the payload length.");
        }
    }

    private void readCursor(int hotspotX, int hotspotY, int w, int h) throws IOException {
        if (w <= 0 || h <= 0) {
            cursor.set(0, 0, 0, 0, new int[0]);
            return;
        }
        byte[] raw = new byte[w * h * 4];
        in.readFully(raw);
        int maskStride = (w + 7) / 8;
        byte[] mask = new byte[maskStride * h];
        in.readFully(mask);

        int[] argb = new int[w * h];
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                int i = py * w + px;
                boolean opaque = (mask[py * maskStride + (px >> 3)] >> (7 - (px & 7)) & 1) != 0;
                argb[i] = opaque
                        ? 0xFF000000 | (raw[i * 4 + 2] & 0xFF) << 16
                                | (raw[i * 4 + 1] & 0xFF) << 8 | (raw[i * 4] & 0xFF)
                        : 0;
            }
        }
        cursor.set(w, h, hotspotX, hotspotY, argb);
    }

    private void readRaw(int x, int y, int w, int h) throws IOException {
        if (w <= 0 || h <= 0) return;
        byte[] row = (w * 4 <= rowScratch.length) ? rowScratch : new byte[w * 4];
        int[] pixels = framebuffer.pixels();
        int stride = framebuffer.width();

        for (int row_y = 0; row_y < h; row_y++) {
            in.readFully(row, 0, w * 4);
            int dst = (y + row_y) * stride + x;
            for (int i = 0, b = 0; i < w; i++, b += 4) {
                pixels[dst + i] = 0xFF000000
                        | (row[b + 2] & 0xFF) << 16
                        | (row[b + 1] & 0xFF) << 8
                        | (row[b] & 0xFF);
            }
        }
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > (1 << 20)) {
            throw new IOException("Implausible string length " + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
