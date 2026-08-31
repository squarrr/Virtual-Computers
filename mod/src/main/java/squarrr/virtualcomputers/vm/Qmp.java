package squarrr.virtualcomputers.vm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Qmp implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("vc.vm");

    private final Socket socket;
    private final BufferedReader reader;
    private final OutputStream out;

    private Qmp(Socket socket) throws IOException {
        this.socket = socket;
        this.reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.out = socket.getOutputStream();
    }

    public static Qmp connect(String host, int port, long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        IOException last = null;
        while (System.currentTimeMillis() < deadline) {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(host, port), 1000);
                socket.setSoTimeout(15_000);
                Qmp qmp = new Qmp(socket);
                qmp.handshake();
                return qmp;
            } catch (IOException e) {
                last = e;
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
                try {
                    Thread.sleep(120);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while waiting for QMP", interrupted);
                }
            }
        }
        throw last != null ? last : new IOException("QMP did not accept a connection in time");
    }

    private void handshake() throws IOException {
        String greeting = reader.readLine();
        if (greeting == null) {
            throw new IOException("QMP closed before it greeted us");
        }
        execute("qmp_capabilities", null);
    }

    public JsonObject execute(String command, JsonObject arguments) throws IOException {
        JsonElement returned = executeRaw(command, arguments);
        return returned.isJsonObject() ? returned.getAsJsonObject() : new JsonObject();
    }

    public synchronized JsonElement executeRaw(String command, JsonObject arguments) throws IOException {
        JsonObject request = new JsonObject();
        request.addProperty("execute", command);
        if (arguments != null) {
            request.add("arguments", arguments);
        }
        out.write((request + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();

        while (true) {
            String line = reader.readLine();
            if (line == null) {
                throw new IOException("QMP closed while waiting for '" + command + "'");
            }
            JsonObject response;
            try {
                response = JsonParser.parseString(line).getAsJsonObject();
            } catch (RuntimeException e) {
                continue;
            }
            if (response.has("error")) {
                JsonObject error = response.getAsJsonObject("error");
                String description = error.has("desc") ? error.get("desc").getAsString() : error.toString();
                throw new IOException("QEMU refused '" + command + "': " + description);
            }
            if (response.has("return")) {
                return response.get("return");
            }

            if (response.has("event")) {
                LOGGER.debug("[qmp] event {}", response.get("event").getAsString());
            }
        }
    }

    public String humanMonitor(String commandLine) throws IOException {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("command-line", commandLine);
        JsonElement result = executeRaw("human-monitor-command", arguments);
        return result.isJsonPrimitive() ? result.getAsString() : "";
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
