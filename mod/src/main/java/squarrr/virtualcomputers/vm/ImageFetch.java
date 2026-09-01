package squarrr.virtualcomputers.vm;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ImageFetch {
    private static final Logger LOGGER = LoggerFactory.getLogger("vc.vm");

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    private static final int CHUNK = 1 << 20;

    private ImageFetch() {
    }

    /** No image yet for an entry the player supplies themselves. Nothing has gone wrong. */
    public static final class MediaMissing extends IOException {
        public MediaMissing(String message) {
            super(message);
        }
    }

    public static Path directory() {
        Path directory = VmStore.root().resolve("images");
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            LOGGER.error("[os] cannot create {}", directory, e);
        }
        return directory;
    }

    /** True when nothing would be downloaded, so callers can ask before starting one. */
    public static boolean mediaReady(OsEntry entry) {
        if (entry.isLocalOnly()) {
            return true;
        }
        Path media = directory().resolve(entry.mediaFileName());
        if (!Files.isRegularFile(media)) {
            return false;
        }
        try {
            Path marker = markerFor(media);
            return Files.isRegularFile(marker)
                    && Files.readString(marker, StandardCharsets.UTF_8).trim()
                            .equalsIgnoreCase(Checksum.parse(entry.source().checksum(),
                                    entry.id()).toString());
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    public static Path ensureMedia(OsEntry entry, Checksum.Progress progress) throws IOException {
        if (entry.isLocalOnly()) {
            return localMedia(entry);
        }
        Path media = directory().resolve(entry.mediaFileName());
        Checksum expected = Checksum.parse(entry.source().checksum(), entry.id());

        if (Files.isRegularFile(media) && isVerified(media, expected, entry)) {
            return media;
        }

        Path download = directory().resolve(fileNameOf(entry.source().url()) + ".partial");
        Files.deleteIfExists(download);
        LOGGER.info("[os] fetching {} from {}", entry.name(), entry.source().url());
        long got = download(entry.source().url(), download, entry.source().size(), progress);
        LOGGER.info("[os] downloaded {}; checking it is the file the entry names",
                Templates.human(got));

        String actual = expected.of(download, progress);
        if (!actual.equals(expected.hex())) {
            Files.deleteIfExists(download);

            throw new IOException(entry.name() + " did not match its checksum and has been deleted.\n"
                    + "  expected " + expected.algorithm() + " " + expected.hex() + "\n"
                    + "  got      " + expected.algorithm() + " " + actual + "\n"
                    + "  Either the download was interrupted, or the file at that URL is no longer\n"
                    + "  the one this entry was written for. Try again; if it repeats, the entry\n"
                    + "  needs a new checksum.");
        }
        LOGGER.info("[os] checksum matches");

        if (entry.source().archive() == OsEntry.Archive.NONE) {
            Files.move(download, media, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Path unpacked = media.resolveSibling(media.getFileName() + ".partial");
            Files.deleteIfExists(unpacked);
            LOGGER.info("[os] unpacking");
            unpack(download, unpacked, entry.source().archive());
            Files.move(unpacked, media, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(download);
        }
        recordVerified(media, expected);
        LOGGER.info("[os] {} is ready at {}", entry.name(), media.getFileName());
        return media;
    }

    private static Path localMedia(OsEntry entry) throws IOException {
        Path chosen = VmStore.bootMediaMatching(entry.id());
        if (chosen != null) {
            return chosen;
        }
        throw new MediaMissing(entry.name() + " has no image here yet.\n  "
                + (entry.localHint() != null ? entry.localHint()
                    : "Put a bootable image in the images folder.")
                + "\n  Folder: " + directory()
                + "\n  Drop it in and use the box again - nothing else has to be redone.");
    }

    private static long download(String url, Path target, long expectedSize,
                                 Checksum.Progress progress) throws IOException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "virtualcomputers (Minecraft mod; fetches vendor media)")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IOException("the vendor's server answered HTTP " + response.statusCode()
                        + " for " + url);
            }
            long total = response.headers().firstValueAsLong("content-length").orElse(expectedSize);
            long written = 0;
            byte[] buffer = new byte[CHUNK];
            try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(target)) {
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    written += n;
                    if (progress != null) {
                        progress.at(written, total);
                    }
                }
            }
            return written;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            deleteQuietly(target);
            throw new IOException("the download was interrupted", e);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(target);
            throw e;
        }
    }

    private static void unpack(Path archive, Path target, OsEntry.Archive kind) throws IOException {
        switch (kind) {
            case GZ -> {
                try (InputStream in = new GZIPInputStream(Files.newInputStream(archive), CHUNK);
                     OutputStream out = Files.newOutputStream(target)) {
                    in.transferTo(out);
                }
            }
            case ZIP -> {
                try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(archive.toFile())) {
                    ZipEntry biggest = null;
                    for (java.util.Enumeration<? extends ZipEntry> it = zip.entries();
                         it.hasMoreElements(); ) {
                        ZipEntry candidate = it.nextElement();
                        if (candidate.isDirectory()) {
                            continue;
                        }
                        if (biggest == null || candidate.getSize() > biggest.getSize()) {
                            biggest = candidate;
                        }
                    }
                    if (biggest == null) {
                        throw new IOException("the archive contained no file");
                    }
                    LOGGER.info("[os] expanding {} ({})", biggest.getName(),
                            Templates.human(biggest.getSize()));
                    try (InputStream in = zip.getInputStream(biggest);
                         OutputStream out = Files.newOutputStream(target)) {
                        in.transferTo(out);
                    }
                }
            }
            case NONE -> Files.copy(archive, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean isVerified(Path media, Checksum expected, OsEntry entry) throws IOException {
        Path marker = markerFor(media);
        if (Files.isRegularFile(marker)) {
            String recorded = Files.readString(marker, StandardCharsets.UTF_8).trim();
            if (recorded.equalsIgnoreCase(expected.toString())) {
                return true;
            }
            LOGGER.info("[os] {} was verified against a different checksum; fetching again",
                    media.getFileName());
            return false;
        }
        if (entry.source().archive() != OsEntry.Archive.NONE) {
            LOGGER.info("[os] {} is present but was never verified, and it was unpacked from an"
                    + " archive so there is nothing to compare it against; fetching again",
                    media.getFileName());
            return false;
        }
        LOGGER.info("[os] {} is already here; checking it", media.getFileName());
        if (expected.matches(media, null)) {
            recordVerified(media, expected);
            return true;
        }
        LOGGER.info("[os] {} is not the file this entry names; fetching the right one",
                media.getFileName());
        return false;
    }

    private static void recordVerified(Path media, Checksum checksum) {
        try {
            Files.writeString(markerFor(media), checksum.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.debug("[os] could not record the checksum beside {}", media.getFileName());
        }
    }

    private static Path markerFor(Path media) {
        return media.resolveSibling(media.getFileName() + ".verified");
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            LOGGER.debug("[os] could not remove {}", path);
        }
    }

    private static String fileNameOf(String url) {
        return url.substring(url.lastIndexOf('/') + 1);
    }
}
