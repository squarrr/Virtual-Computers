package squarrr.virtualcomputers.vm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Everything this mod has put on the disk, across every instance that has ever run it. */
public record StorageReport(List<Location> locations, long total) {

    public record Location(Path root, boolean current, List<Disk> disks, List<Group> groups, long total) { }

    public record Disk(String id, String hostname, String os, boolean installed,
                       boolean hasSnapshot, long bytes, long addedEpochMs) { }

    public record Group(Kind kind, String label, Path directory, int count, long bytes,
                        List<Item> items) { }

    public record Item(String name, Path path, long bytes) { }

    public enum Kind { TEMPLATES, MEDIA, SCRATCH, FIRMWARE, CONSOLES, SEEDS }

    private static final long INSTALLED_ABOVE = 32L * 1024 * 1024;

    private static final Pattern LOGIN = Pattern.compile("(?m)^([A-Za-z0-9][A-Za-z0-9._-]*) login:");

    public static StorageReport collect() {
        List<Location> found = new ArrayList<>();
        long total = 0;
        Path current = VmStore.root().toAbsolutePath().normalize();
        for (Path root : VmStore.knownLocations()) {
            Location location = scan(root, root.equals(current));
            if (location.total() > 0 || location.current()) {
                found.add(location);
                total += location.total();
            }
        }
        found.sort(Comparator.comparing(Location::current).reversed()
                .thenComparing(l -> l.root().toString()));
        return new StorageReport(List.copyOf(found), total);
    }

    private static Location scan(Path root, boolean current) {
        List<Disk> disks = new ArrayList<>();
        for (Path disk : list(root.resolve("disks"), ".qcow2")) {
            String id = stripExtension(disk.getFileName().toString());
            long bytes = sizeOf(disk);
            disks.add(new Disk(id, hostnameOf(root, id), VmStore.osOfDisk(disk),
                    bytes > INSTALLED_ABOVE, VmStore.hasSnapshotOnDisk(disk), bytes,
                    createdAt(disk)));
        }
        disks.sort(Comparator.comparingLong(Disk::addedEpochMs));

        List<Group> groups = new ArrayList<>();
        addFiles(groups, Kind.TEMPLATES, "Installed operating systems",
                root.resolve("templates"), ".qcow2");
        addFiles(groups, Kind.MEDIA, "Downloaded media", root.resolve("images"), null);
        addFiles(groups, Kind.SCRATCH, "Installer scratch space", root.resolve("media"), ".qcow2");
        addFiles(groups, Kind.FIRMWARE, "Firmware settings", root.resolve("firmware"), null);
        addFiles(groups, Kind.CONSOLES, "Guest console logs", root.resolve("consoles"), null);
        addFolders(groups, Kind.SEEDS, "Provisioning seeds", root.resolve("provision"));

        long total = disks.stream().mapToLong(Disk::bytes).sum()
                + groups.stream().mapToLong(Group::bytes).sum();
        return new Location(root, current, List.copyOf(disks), List.copyOf(groups), total);
    }

    private static void addFiles(List<Group> into, Kind kind, String label, Path directory,
                                 String suffix) {
        if (!Files.isDirectory(directory)) {
            return;
        }
        List<Item> items = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String name = p.getFileName().toString();
                if (name.endsWith(".verified")) {
                    continue;
                }
                if (suffix != null && !name.endsWith(suffix)) {
                    continue;
                }
                items.add(new Item(suffix != null ? stripExtension(name) : name, p, sizeOf(p)));
            }
        } catch (IOException e) {
            return;
        }
        collect(into, kind, label, directory, items);
    }

    private static void addFolders(List<Group> into, Kind kind, String label, Path directory) {
        if (!Files.isDirectory(directory)) {
            return;
        }
        List<Item> items = new ArrayList<>();
        try (Stream<Path> listed = Files.list(directory)) {
            for (Path p : listed.filter(Files::isDirectory).toList()) {
                items.add(new Item(p.getFileName().toString(), p, treeSize(p)));
            }
        } catch (IOException e) {
            return;
        }
        collect(into, kind, label, directory, items);
    }

    private static void collect(List<Group> into, Kind kind, String label, Path directory,
                                List<Item> items) {
        if (items.isEmpty()) {
            return;
        }
        items.sort(Comparator.comparingLong(Item::bytes).reversed());
        long bytes = items.stream().mapToLong(Item::bytes).sum();
        into.add(new Group(kind, label, directory, items.size(), bytes, List.copyOf(items)));
    }

    public static long treeSize(Path path) {
        if (Files.isRegularFile(path)) {
            return sizeOf(path);
        }
        try (Stream<Path> walk = Files.walk(path)) {
            return walk.filter(Files::isRegularFile).mapToLong(StorageReport::sizeOf).sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String hostnameOf(Path root, String machineId) {
        Path log = root.resolve("consoles").resolve(machineId + ".log");
        if (!Files.isRegularFile(log)) {
            return null;
        }
        try {
            byte[] all = Files.readAllBytes(log);
            int from = Math.max(0, all.length - 8192);
            String tail = new String(all, from, all.length - from, StandardCharsets.UTF_8);
            Matcher matcher = LOGIN.matcher(tail);
            String last = null;
            while (matcher.find()) {
                last = matcher.group(1);
            }
            return last;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static List<Path> list(Path directory, String suffix) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(suffix))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0L;
        }
    }

    private static long createdAt(Path file) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            long created = attributes.creationTime().toMillis();
            return created > 0 ? created : attributes.lastModifiedTime().toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}
