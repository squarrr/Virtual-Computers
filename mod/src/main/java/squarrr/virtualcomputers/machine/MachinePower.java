package squarrr.virtualcomputers.machine;

import squarrr.virtualcomputers.VirtualComputers;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class MachinePower {
    private static final long ABANDONED_PAUSE_MS = 120_000;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "vc-vm-power");
        thread.setDaemon(true);
        return thread;
    });

    private static final ScheduledExecutorService TIMER =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "vc-vm-reaper");
                thread.setDaemon(true);
                return thread;
            });

    private MachinePower() {
    }

    static void submit(Runnable work) {
        WORKER.submit(() -> {
            try {
                work.run();
            } catch (RuntimeException e) {
                VirtualComputers.LOGGER.error("[vm] a power operation failed", e);
            }
        });
    }

    static ScheduledFuture<?> reapWhenAbandoned(Machine machine) {
        return TIMER.schedule(() -> {
            if (machine.state() == MachineState.SLEEPING) {
                VirtualComputers.LOGGER.info(
                        "[machine {}] paused and unclaimed for {} s; stopping it properly",
                        machine.id(), ABANDONED_PAUSE_MS / 1000);
                submit(machine::persist);
            }
        }, ABANDONED_PAUSE_MS, TimeUnit.MILLISECONDS);
    }

    static void later(Runnable work, long delayMs) {
        TIMER.schedule(() -> submit(work), delayMs, TimeUnit.MILLISECONDS);
    }

    public static boolean drain(long timeoutMs) {
        try {
            java.util.concurrent.Future<?> marker = WORKER.submit(() -> { });
            marker.get(timeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception e) {
            VirtualComputers.LOGGER.warn("[vm] power work did not finish within {} ms", timeoutMs);
            return false;
        }
    }
}
