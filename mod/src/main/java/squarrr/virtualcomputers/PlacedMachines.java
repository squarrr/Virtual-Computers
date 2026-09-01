package squarrr.virtualcomputers;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Which machines have a computer block loaded in the world right now. Client-side only. */
public final class PlacedMachines {

    private static final Set<String> PRESENT = ConcurrentHashMap.newKeySet();

    private PlacedMachines() {
    }

    public static void add(String machineId) {
        if (machineId != null && !machineId.isBlank()) {
            PRESENT.add(machineId);
        }
    }

    public static void remove(String machineId) {
        if (machineId != null) {
            PRESENT.remove(machineId);
        }
    }

    public static boolean inWorld(String machineId) {
        return machineId != null && PRESENT.contains(machineId);
    }

    public static void clear() {
        PRESENT.clear();
    }
}
