package squarrr.virtualcomputers.machine;

import squarrr.virtualcomputers.VirtualComputers;
import squarrr.virtualcomputers.vm.VmSpec;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Machines {
    public static final String DESKTOP_ID = VirtualComputers.DESKTOP_MACHINE_ID;

    private static final Map<String, Machine> BY_ID = new ConcurrentHashMap<>();
    private static final Map<String, VmSpec> SPECS = new ConcurrentHashMap<>();

    private Machines() {
    }

    public static Machine get(String id, VmSpec spec) {
        SPECS.put(id, spec);
        return BY_ID.computeIfAbsent(id, key -> Machine.create(key, spec));
    }

    public static Machine peek(String id) {
        return BY_ID.get(id);
    }

    public static Machine desktop() {
        return get(DESKTOP_ID, VmSpec.DESKTOP);
    }

    public static boolean isEmpty() {
        return BY_ID.isEmpty();
    }

    public static void prepareFrame(long nowMs) {
        for (Machine machine : BY_ID.values()) {
            machine.prepareFrame(nowMs);
        }
    }

    public static void persistAllAndWait() {
        int snapshots = 0;
        int shutdowns = 0;
        int killed = 0;
        for (Machine machine : BY_ID.values()) {
            if (!machine.isManaged()) {
                continue;
            }
            switch (machine.persist()) {
                case SNAPSHOT -> snapshots++;
                case CLEAN_SHUTDOWN -> shutdowns++;
                case KILLED -> killed++;
                case ALREADY_STOPPED -> { }
            }
        }
        if (snapshots + shutdowns + killed > 0) {
            VirtualComputers.LOGGER.info(
                    "[vm] on the way out: {} snapshotted, {} shut down cleanly, {} killed",
                    snapshots, shutdowns, killed);
        }
        MachinePower.drain(30_000);
    }

    public static void closeAll() {
        persistAllAndWait();
        BY_ID.values().forEach(Machine::close);
        BY_ID.clear();
        SPECS.clear();
    }

    public static Iterable<Machine> all() {
        return BY_ID.values();
    }
}
