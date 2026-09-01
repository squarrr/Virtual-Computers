package squarrr.virtualcomputers.vm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public record OsEntry(String id, String name, Kind kind, Media media, List<String> arch,
                      Source source, Firmware firmware, int diskGb, String base,
                      String provision, String notes, String localHint) {
    public enum Kind { INSTALLER, TEMPLATE, RECIPE }

    public enum Media { ISO, DISK }

    public enum Firmware {
        BIOS, UEFI, UEFI_SECURE;

        public boolean available() {
            return switch (this) {
                case BIOS -> true;
                case UEFI, UEFI_SECURE -> Hypervisor.diagnose().firmware(this) != null;
            };
        }
    }

    public enum Archive { NONE, GZ, ZIP }

    public record Source(String url, String checksum, long size, Archive archive) {
        public boolean isLocalOnly() {
            return url == null || url.isBlank();
        }
    }

    public boolean isLocalOnly() {
        return source.isLocalOnly();
    }

    public String mediaFileName() {
        if (!isLocalOnly()) {
            String path = source.url();
            String last = path.substring(path.lastIndexOf('/') + 1);
            return switch (source.archive()) {
                case GZ -> last.endsWith(".gz") ? last.substring(0, last.length() - 3) : last;
                case ZIP -> last.endsWith(".zip") ? last.substring(0, last.length() - 4) : last;
                case NONE -> last;
            };
        }
        return null;
    }

    @Override
    public String toString() {
        return name + " (" + id + ", " + kind.name().toLowerCase(Locale.ROOT) + ")";
    }

    public static OsEntry parse(JsonObject json, String where) {
        String id = string(json, "id", where);
        if (!id.matches("[a-z0-9_]{2,48}")) {
            throw new IllegalArgumentException(where + ": id \"" + id
                    + "\" must be lowercase letters, digits and underscores");
        }
        Kind kind = enumOf(Kind.class, string(json, "kind", where), "kind", where);
        Media media = json.has("media")
                ? enumOf(Media.class, json.get("media").getAsString(), "media", where)
                : Media.ISO;
        Firmware firmware = json.has("firmware")
                ? enumOf(Firmware.class, json.get("firmware").getAsString(), "firmware", where)
                : Firmware.BIOS;

        List<String> arch = new ArrayList<>();
        if (json.has("arch")) {
            for (JsonElement element : json.getAsJsonArray("arch")) {
                arch.add(element.getAsString());
            }
        }
        if (arch.isEmpty()) {
            arch.add("x86_64");
        }

        JsonObject sourceJson = json.has("source") ? json.getAsJsonObject("source") : new JsonObject();
        String url = optional(sourceJson, "url");
        String checksum = optional(sourceJson, "checksum");
        long size = sourceJson.has("size") ? sourceJson.get("size").getAsLong() : 0L;
        Archive archive = sourceJson.has("archive")
                ? enumOf(Archive.class, sourceJson.get("archive").getAsString(), "archive", where)
                : Archive.NONE;

        if (url != null && !url.isBlank()) {
            if (checksum == null || checksum.isBlank()) {
                throw new IllegalArgumentException(where + ": \"" + id + "\" has a url but no"
                        + " checksum. An image that cannot be verified is not one this mod will"
                        + " fetch - see plan section 18.");
            }
            Checksum.parse(checksum, where);
            if (!url.startsWith("https://")) {
                throw new IllegalArgumentException(where + ": \"" + id + "\" must be fetched over"
                        + " https; got " + url);
            }
        }

        String base = optional(json, "base");
        String provision = optional(json, "provision");
        if (kind == Kind.RECIPE && (base == null || base.isBlank())) {
            throw new IllegalArgumentException(where + ": recipe \"" + id + "\" names no base entry");
        }

        int diskGb = json.has("disk_gb") ? json.get("disk_gb").getAsInt() : 32;
        if (diskGb < 1 || diskGb > 2048) {
            throw new IllegalArgumentException(where + ": \"" + id + "\" disk_gb " + diskGb
                    + " is not between 1 and 2048");
        }

        return new OsEntry(id, string(json, "name", where), kind, media, List.copyOf(arch),
                new Source(url, checksum, size, archive), firmware, diskGb, base, provision,
                optional(json, "notes"), optional(json, "local_hint"));
    }

    private static String string(JsonObject json, String field, String where) {
        if (!json.has(field)) {
            throw new IllegalArgumentException(where + ": missing \"" + field + "\"");
        }
        return json.get(field).getAsString();
    }

    private static String optional(JsonObject json, String field) {
        return json.has(field) && !json.get(field).isJsonNull() ? json.get(field).getAsString() : null;
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String value, String field, String where) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            StringBuilder allowed = new StringBuilder();
            for (E constant : type.getEnumConstants()) {
                allowed.append(allowed.isEmpty() ? "" : ", ").append(constant.name().toLowerCase(Locale.ROOT));
            }
            throw new IllegalArgumentException(where + ": " + field + " \"" + value
                    + "\" is not one of " + allowed);
        }
    }
}
