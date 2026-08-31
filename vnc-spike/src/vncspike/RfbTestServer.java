package vncspike;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class RfbTestServer implements AutoCloseable {
    private final ServerSocket serverSocket;
    private final int width;
    private final int height;
    private final Thread thread;
    private volatile boolean running = true;

    public RfbTestServer(int port, int width, int height) throws IOException {
        this.width = width;
        this.height = height;

        this.serverSocket = new ServerSocket(port, 4, InetAddress.getLoopbackAddress());
        this.thread = new Thread(this::acceptLoop, "rfb-test-server");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    public int port() { return serverSocket.getLocalPort(); }

    private void acceptLoop() {
        while (running) {
            try (Socket socket = serverSocket.accept()) {
                socket.setTcpNoDelay(true);
                serveClient(socket);
            } catch (IOException e) {
                if (running) {
                    System.out.println("  [server] client gone: " + e.getMessage());
                }
            }
        }
    }

    private void serveClient(Socket socket) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 1 << 16));

        out.write("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
        byte[] clientVersion = new byte[12];
        in.readFully(clientVersion);

        out.writeByte(1);
        out.writeByte(1);
        out.flush();
        int chosen = in.readUnsignedByte();
        if (chosen != 1) {
            out.writeInt(1);
            writeString(out, "only 'None' is supported");
            out.flush();
            return;
        }
        out.writeInt(0);
        out.flush();

        in.readUnsignedByte();
        out.writeShort(width);
        out.writeShort(height);
        PixelFormat.STANDARD_32BPP.write(out);
        writeString(out, "vnc-spike test pattern");
        out.flush();

        int[] pixels = new int[width * height];
        byte[] wire = new byte[width * height * 4];
        int frame = 0;
        InputEcho echo = new InputEcho();

        while (running && !socket.isClosed()) {
            int messageType = in.readUnsignedByte();
            switch (messageType) {
                case 0 -> {
                    in.skipNBytes(3);
                    PixelFormat requested = PixelFormat.read(in);
                    if (!requested.isStandard32bpp()) {
                        throw new IOException("client asked for " + requested
                                + "; this test server only serves 32bpp true colour");
                    }
                }
                case 2 -> {
                    in.skipNBytes(1);
                    int count = in.readUnsignedShort();
                    in.skipNBytes(count * 4L);
                }
                case 3 -> {
                    in.skipNBytes(1);
                    in.skipNBytes(8);
                    TestPattern.render(pixels, width, height, frame++);
                    echo.draw(pixels, width, height);
                    sendFullFrame(out, pixels, wire);
                }
                case 4 -> {
                    boolean down = in.readUnsignedByte() != 0;
                    in.skipNBytes(2);
                    echo.onKey(in.readInt(), down);
                }
                case 5 -> {
                    int mask = in.readUnsignedByte();
                    echo.onPointer(in.readUnsignedShort(), in.readUnsignedShort(), mask);
                }
                case 6 -> {
                    in.skipNBytes(3);
                    int length = in.readInt();
                    in.skipNBytes(length);
                }
                default -> throw new IOException("unknown client message type " + messageType);
            }
        }
    }

    private void sendFullFrame(DataOutputStream out, int[] pixels, byte[] wire) throws IOException {
        for (int i = 0, b = 0; i < pixels.length; i++, b += 4) {
            int argb = pixels[i];
            wire[b] = (byte) argb;
            wire[b + 1] = (byte) (argb >> 8);
            wire[b + 2] = (byte) (argb >> 16);
            wire[b + 3] = 0;
        }
        out.writeByte(0);
        out.writeByte(0);
        out.writeShort(1);
        out.writeShort(0);
        out.writeShort(0);
        out.writeShort(width);
        out.writeShort(height);
        out.writeInt(0);
        out.write(wire);
        out.flush();
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    @Override
    public void close() throws IOException {
        running = false;
        serverSocket.close();
    }
}
