package squarrr.virtualcomputers.vm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Cleanup {
    private static final Logger LOGGER = LoggerFactory.getLogger("vc.vm");

    private Cleanup() {
    }

    public record Plan(String what, List<Path> files, long bytes, String refusal) {
        public boolean allowed() {
            return refusal == null && !files.isEmpty();
        }
    }

    public static Plan forMachine(Path root, String machineId, String what) {
        List<Path> files = new ArrayList<>();
        add(files, root.resolve("disks").resolve(machineId + ".qcow2"));
        add(files, VmStore.osMarkerFor(root.resolve("disks").resolve(machineId + ".qcow2")));
        add(files, root.resolve("media").resolve(machineId + ".qcow2"));
        add(files, root.resolve("consoles").resolve(machineId + ".log"));
        add(files, root.resolve("firmware").resolve(machineId + "_VARS.fd"));
        return plan(what, files, files.isEmpty() ? "there is nothing left of it on the disk" : null);
    }

    public static Plan forItem(StorageReport.Group group, StorageReport.Item item) {
        List<Path> files = new ArrayList<>();
        add(files, item.path());
        if (group.kind() == StorageReport.Kind.MEDIA) {
            add(files, item.path().resolveSibling(item.path().getFileName() + ".verified"));
        }
        String refusal = group.kind() == StorageReport.Kind.TEMPLATES
                ? stillNeeded(item.name()) : null;
        return plan(item.name(), files, refusal);
    }

    public static Plan forGroup(StorageReport.Group group) {
        List<Path> files = new ArrayList<>();
        String refusal = null;
        for (StorageReport.Item item : group.items()) {
            Plan one = forItem(group, item);
            if (one.refusal() != null && refusal == null) {
                refusal = item.name() + ": " + one.refusal();
            }
            files.addAll(one.files());
        }
        return plan(group.label().toLowerCase(java.util.Locale.ROOT), files, refusal);
    }

    /** A template with overlays on it is load-bearing: deleting it stops every one of them booting. */
    private static String stillNeeded(String entryId) {
        List<Path> using = Templates.dependents(entryId);
        if (using.isEmpty()) {
            return null;
        }
        return (using.size() == 1 ? "One computer is" : using.size() + " computers are")
                + " built on this and would stop booting without it."
                + " Delete " + (using.size() == 1 ? "it" : "them") + " first.";
    }

    private static Plan plan(String what, List<Path> files, String refusal) {
        long bytes = files.stream().mapToLong(StorageReport::treeSize).sum();
        return new Plan(what, List.copyOf(files), bytes, refusal);
    }

    private static void add(List<Path> files, Path candidate) {
        if (Files.exists(candidate)) {
            files.add(candidate);
        }
    }

    public static long delete(Plan plan) throws IOException {
        if (plan.refusal() != null) {
            throw new IOException(plan.refusal());
        }
        long freed = 0;
        for (Path path : plan.files()) {
            freed += StorageReport.treeSize(path);
            removeTree(path);
        }
        LOGGER.info("[vm] deleted {} ({})", plan.what(), Templates.human(freed));
        return freed;
    }

    private static void removeTree(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        if (Files.isRegularFile(path)) {
            forceDelete(path);
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            List<Path> deepestFirst = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path each : deepestFirst) {
                forceDelete(each);
            }
        }
    }

    /** Templates are marked read-only when frozen, and Windows refuses to delete those. */
    private static void forceDelete(Path path) throws IOException {
        path.toFile().setWritable(true);
        Files.deleteIfExists(path);
    }
}
