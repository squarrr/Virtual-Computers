package squarrr.virtualcomputers.vm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OsRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger("vc.vm");

    private static final String BUILT_IN_PATH = "/virtualcomputers/os/";

    public static final List<String> BOXED = List.of(
            "windows_10", "windows_11", "linux", "tv", "chrome_os", "custom");

    public static final List<String> EXTRA = List.of("debian_cloud", "tv_kodi");

    public static final List<String> BUILT_IN =
            java.util.stream.Stream.concat(BOXED.stream(), EXTRA.stream()).toList();

    private static Map<String, OsEntry> entries;

    private OsRegistry() {
    }

    public static synchronized Map<String, OsEntry> all() {
        if (entries == null) {
            entries = load();
        }
        return entries;
    }

    public static OsEntry get(String id) {
        return id == null ? null : all().get(id);
    }

    public static synchronized void forget() {
        entries = null;
    }

    private static Map<String, OsEntry> load() {
        Map<String, OsEntry> found = new LinkedHashMap<>();
        for (String id : BUILT_IN) {
            String resource = BUILT_IN_PATH + id + ".json";
            try (InputStream in = OsRegistry.class.getResourceAsStream(resource)) {
                if (in == null) {
                    LOGGER.error("[os] built-in entry {} is missing from the jar", resource);
                    continue;
                }
                JsonObject json = JsonParser.parseReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                OsEntry entry = OsEntry.parse(json, resource);
                found.put(entry.id(), entry);
            } catch (IOException | RuntimeException e) {
                LOGGER.error("[os] built-in entry {} is not usable: {}", resource, e.getMessage());
            }
        }

        Path directory = VmStore.root().resolve("os");
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            LOGGER.warn("[os] cannot create {}", directory, e);
            return found;
        }
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> json = files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".json"))
                    .sorted()
                    .toList();
            for (Path file : json) {
                try {
                    JsonObject object = JsonParser.parseString(
                            Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                    OsEntry entry = OsEntry.parse(object, file.getFileName().toString());
                    OsEntry replaced = found.put(entry.id(), entry);
                    LOGGER.info("[os] {} entry \"{}\" from {}",
                            replaced == null ? "added" : "overrode built-in", entry.id(),
                            file.getFileName());
                } catch (IOException | RuntimeException e) {
                    LOGGER.error("[os] ignoring {}: {}", file.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[os] cannot read {}", directory, e);
        }

        List<String> unresolved = new ArrayList<>();
        for (OsEntry entry : found.values()) {
            if (entry.kind() == OsEntry.Kind.RECIPE && !found.containsKey(entry.base())) {
                unresolved.add(entry.id() + " -> " + entry.base());
            }
        }
        if (!unresolved.isEmpty()) {
            LOGGER.warn("[os] recipes naming a base that does not exist: {}", unresolved);
        }
        return found;
    }

    public static List<String> describe() {
        List<String> lines = new ArrayList<>();
        for (OsEntry entry : all().values()) {
            String where = entry.isLocalOnly()
                    ? "your own media"
                    : entry.source().url().replaceFirst("^https://([^/]+)/.*$", "$1");
            lines.add(String.format("%-11s %-34s %-9s %s", entry.id(), entry.name(),
                    entry.kind().name().toLowerCase(), where));
        }
        return lines;
    }
}
