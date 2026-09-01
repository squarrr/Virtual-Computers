package squarrr.virtualcomputers.vm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs a helper and always comes back. readAllBytes on a leaked pipe does not. */
public final class Exec {
    private static final Logger LOGGER = LoggerFactory.getLogger("vc.vm");

    public static final long QUICK_MS = 60_000;

    public static final long PATIENT_MS = 3_600_000;

    private Exec() {
    }

    public record Result(int status, String output, boolean timedOut) {
        public boolean ok() {
            return status == 0 && !timedOut;
        }

        public String complaint() {
            return timedOut ? "it did not finish in time and was stopped" : output.trim();
        }
    }

    public static Result run(List<String> command, long timeoutMs) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getOutputStream().close();
        StringBuffer collected = new StringBuffer();
        Thread pump = new Thread(() -> {
            try (InputStream out = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = out.read(buffer)) >= 0) {
                    collected.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                }
            } catch (IOException closed) {
            }
        }, "vc-exec");
        pump.setDaemon(true);
        pump.start();
        try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                LOGGER.warn("[vm] {} did not finish within {} ms; stopping it",
                        command.get(0), timeoutMs);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                pump.join(2_000);
                return new Result(-1, collected.toString(), true);
            }
            pump.join(2_000);
            return new Result(process.exitValue(), collected.toString(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("interrupted while running " + command.get(0), e);
        }
    }

    public static String capture(List<String> command, long timeoutMs) {
        try {
            return run(command, timeoutMs).output();
        } catch (IOException e) {
            return "";
        }
    }
}
