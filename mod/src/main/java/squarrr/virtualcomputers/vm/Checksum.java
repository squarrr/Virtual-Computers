package squarrr.virtualcomputers.vm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Set;

public record Checksum(String algorithm, String hex) {
    private static final Set<String> ALLOWED = Set.of("SHA-256", "SHA-512", "SHA-1");

    private static final int CHUNK = 1 << 20;

    public static Checksum parse(String value, String where) {
        String text = value.trim();
        String algorithm = "SHA-256";
        String hex = text;
        int colon = text.indexOf(':');
        if (colon > 0) {
            algorithm = text.substring(0, colon).trim().toUpperCase(Locale.ROOT);
            if (!algorithm.startsWith("SHA-") && algorithm.startsWith("SHA")) {
                algorithm = "SHA-" + algorithm.substring(3);
            }
            hex = text.substring(colon + 1).trim();
        }
        if (!ALLOWED.contains(algorithm)) {
            throw new IllegalArgumentException(where + ": checksum algorithm \"" + algorithm
                    + "\" is not one of " + ALLOWED);
        }
        hex = hex.toLowerCase(Locale.ROOT);
        if (!hex.matches("[0-9a-f]{32,128}")) {
            throw new IllegalArgumentException(where + ": \"" + hex + "\" is not a hex digest");
        }
        return new Checksum(algorithm, hex);
    }

    public String of(Path file, Progress progress) throws IOException {
        MessageDigest digest = digest();
        long read = 0;
        long total = Files.size(file);
        byte[] buffer = new byte[CHUNK];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
                read += n;
                if (progress != null) {
                    progress.at(read, total);
                }
            }
        }
        StringBuilder out = new StringBuilder();
        for (byte b : digest.digest()) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }

    public boolean matches(Path file, Progress progress) throws IOException {
        return hex.equals(of(file, progress));
    }

    private MessageDigest digest() {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " is not available", e);
        }
    }

    @Override
    public String toString() {
        return algorithm.toLowerCase(Locale.ROOT).replace("-", "") + ":" + hex;
    }

    @FunctionalInterface
    public interface Progress {
        void at(long done, long total);
    }
}
