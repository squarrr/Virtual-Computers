package squarrr.virtualcomputers.machine;

import squarrr.virtualcomputers.VirtualComputers;
import squarrr.virtualcomputers.lod.Rung;
import squarrr.virtualcomputers.screen.RfbScreenSource;
import squarrr.virtualcomputers.screen.ScreenInput;
import squarrr.virtualcomputers.screen.ScreenSource;
import squarrr.virtualcomputers.screen.ScreenTexture;
import squarrr.virtualcomputers.screen.StandbySource;
import squarrr.virtualcomputers.vm.Checksum;
import squarrr.virtualcomputers.vm.Hypervisor;
import squarrr.virtualcomputers.vm.ImageFetch;
import squarrr.virtualcomputers.vm.OsEntry;
import squarrr.virtualcomputers.vm.OsRegistry;
import squarrr.virtualcomputers.vm.Provisioning;
import squarrr.virtualcomputers.vm.QemuVm;
import squarrr.virtualcomputers.vm.Templates;
import squarrr.virtualcomputers.vm.VmSpec;
import squarrr.virtualcomputers.vm.VmStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.resources.Identifier;

public final class Machine implements AutoCloseable {
    private final String id;
    private final VmSpec spec;

    private final boolean managed;
    private final String externalHost;
    private final int externalPort;

    private final AmbientLayer ambient;

    private volatile MachineState state;
    private volatile String fault;
    private volatile ScreenSource source;
    private volatile boolean sourceChanged;
    private QemuVm vm;

    private java.util.concurrent.ScheduledFuture<?> reap;

    private ScreenTexture live;
    private Rung servedRung = Rung.AMBIENT;
    private Rung pendingDemand = Rung.AMBIENT;
    private boolean failed;

    private static final long RELEASE_AFTER_MS = 2_000;

    private long ambientSinceMs;

    private volatile String pointerInfo = "";

    private volatile String osId;
    private volatile boolean osKnown;

    private volatile OsEntry installing;

    private volatile OsEntry awaitingConfirmation;
    private volatile long confirmationAskedAt;

    private static final long CONFIRM_WINDOW_MS = 15_000;

    Machine(String id, VmSpec spec, boolean managed, String externalHost, int externalPort) {
        this.id = id;
        this.spec = spec;
        this.managed = managed;
        this.externalHost = externalHost;
        this.externalPort = externalPort;
        this.ambient = new AmbientLayer(id);

        if (!managed) {
            this.state = MachineState.STARTING;
            this.source = new RfbScreenSource(externalHost, externalPort);
        } else {
            this.state = VmStore.hasSnapshot(id) ? MachineState.SLEEPING : MachineState.OFF;
            this.source = new StandbySource(state);
        }
    }

    public String id() {
        return id;
    }

    public ScreenSource source() {
        return source;
    }

    public MachineState state() {
        return state;
    }

    public String fault() {
        return fault;
    }

    public boolean isManaged() {
        return managed;
    }

    /** A paused guest still holds its disk open, and "Sleeping" covers both that and a saved snapshot. */
    public boolean hasLiveProcess() {
        QemuVm running = vm;
        return running != null && running.isAlive();
    }

    public ScreenInput input() {
        return source instanceof ScreenInput accepting ? accepting : null;
    }

    public synchronized boolean powerOn() {
        if (!managed || state.isPowered()) {
            return false;
        }
        QemuVm resting = vm;
        if (resting != null && resting.isAlive()) {
            cancelReap();
            setState(MachineState.RUNNING);
            MachinePower.submit(resting::resume);
            return true;
        }
        setState(MachineState.STARTING);
        fault = null;
        MachinePower.submit(() -> {
            try {
                QemuVm started = QemuVm.start(id, spec, bootPlan());
                synchronized (this) {
                    vm = started;
                    swapSource(new RfbScreenSource("127.0.0.1", started.vncPort()));
                }
                VirtualComputers.LOGGER.info("[machine {}] {} on 127.0.0.1:{}", id,
                        started.resumed() ? "resumed" : "booting", started.vncPort());
            } catch (IOException | RuntimeException e) {
                synchronized (this) {
                    vm = null;
                    fault = e.getMessage();
                    swapSource(new StandbySource(MachineState.FAILED));
                    setState(MachineState.FAILED);
                }
                VirtualComputers.LOGGER.error("[machine {}] will not start: {}", id, e.getMessage());
            }
        });
        return true;
    }

    public String osId() {
        if (!osKnown && managed) {
            osId = VmStore.osOf(id);
            osKnown = true;
        }
        return osId;
    }

    public String osLabel() {
        if (installing != null) {
            return "installing " + installing.name();
        }
        OsEntry entry = OsRegistry.get(osId);
        return entry != null ? entry.name() : osId != null ? osId : "no operating system";
    }

    public boolean isInstalling() {
        return installing != null;
    }

    private void endInstallSession() {
        if (installing != null) {
            VirtualComputers.LOGGER.info("[machine {}] install media released ({})",
                    id, installing.id());
            installing = null;
        }
    }

    private QemuVm.BootPlan bootPlan() throws IOException {
        OsEntry beingInstalled = installing;
        if (beingInstalled != null) {
            Path media = ImageFetch.ensureMedia(beingInstalled, null);
            Path seed = beingInstalled.kind() == OsEntry.Kind.RECIPE
                    ? Provisioning.seedFor(beingInstalled, id) : null;
            return new QemuVm.BootPlan(beingInstalled, media, true, seed);
        }
        OsEntry entry = OsRegistry.get(osId());
        return entry == null ? QemuVm.BootPlan.none() : new QemuVm.BootPlan(entry, null, false, null);
    }

    public synchronized void install(OsEntry entry, boolean confirmed, Reporter say) {
        if (!managed) {
            say.line("This machine is an endpoint the mod does not own, so it has nothing to install onto.");
            return;
        }
        if (installing != null) {
            say.line(installing.name() + " is already being installed here.");
            return;
        }
        String blocker = Hypervisor.diagnose().whyNot(entry);
        if (blocker != null) {
            say.fault(entry.name() + " cannot be installed here.");
            for (String line : blocker.split("\\R")) {
                say.detail(line);
            }
            return;
        }

        String existing = osId();
        boolean wouldErase = existing != null || VmStore.hasDisk(id);
        if (wouldErase && !confirmed && !isConfirming(entry)) {
            awaitingConfirmation = entry;
            confirmationAskedAt = System.currentTimeMillis();
            say.fault("This machine already has " + osLabel() + " on it.");
            say.detail("Installing " + entry.name() + " erases it. Right-click again to confirm.");
            return;
        }
        awaitingConfirmation = null;

        if (state.isPowered()) {
            say.line("Stopping the machine first.");
            kill(false);
        }

        setState(MachineState.FETCHING);
        fault = null;
        MachinePower.submit(() -> provision(entry, say));
    }

    private boolean isConfirming(OsEntry entry) {
        return awaitingConfirmation != null
                && awaitingConfirmation.id().equals(entry.id())
                && System.currentTimeMillis() - confirmationAskedAt < CONFIRM_WINDOW_MS;
    }

    private void provision(OsEntry entry, Reporter say) {
        try {
            if (Templates.exists(entry.id())) {
                cloneFromTemplate(entry, say);
                return;
            }
            if (entry.kind() == OsEntry.Kind.TEMPLATE) {
                say.line("Fetching " + entry.name() + ". This is the only time anyone has to.");
                Path media = ImageFetch.ensureMedia(entry, progressTo(say));
                say.line("Building the template.");
                Templates.adopt(media, entry.id());
                cloneFromTemplate(entry, say);
                return;
            }

            if (entry.kind() == OsEntry.Kind.RECIPE) {
                OsEntry base = OsRegistry.get(entry.base());
                if (base == null) {
                    throw new IOException(entry.name() + " is built on \"" + entry.base()
                            + "\", and there is no entry by that name.");
                }
                if (!Templates.exists(base.id())) {
                    say.fault(entry.name() + " is built on " + base.name()
                            + ", which has to be installed first.");
                    say.detail("Put a " + base.name() + " box into a machine, install it, and"
                            + " freeze it as a template. Then come back to this one.");
                    setState(MachineState.OFF);
                    return;
                }
            } else if (!entry.isLocalOnly()) {
                say.line("Fetching " + entry.name() + " from " + vendorOf(entry) + ".");
            }
            Path media = entry.kind() == OsEntry.Kind.RECIPE
                    ? null : ImageFetch.ensureMedia(entry, progressTo(say));

            if (entry.kind() == OsEntry.Kind.RECIPE) {
                OsEntry base = OsRegistry.get(entry.base());
                Path disk = VmStore.diskFor(id);
                Files.deleteIfExists(disk);
                VmStore.dropSnapshot(id);
                Templates.createOverlay(disk, Templates.forEntry(base.id()), entry.diskGb());
                VmStore.setOs(id, entry.id());
                osId = entry.id();
                osKnown = true;
            } else {
                freshDisk(entry);
            }
            installing = entry;
            if (entry.kind() == OsEntry.Kind.RECIPE) {
                say.line("Building " + entry.name() + ". It will switch itself off when it is done.");
            } else {
                say.line("Booting the " + entry.name() + " installer. Right-click to sit down at it.");
                say.detail("It boots the installer once; when that finishes and reboots, it comes up"
                        + " on the system it just installed.");
                say.detail("Then sneak and right-click with the box to freeze it - every machine"
                        + " after this one takes seconds.");
                if (entry.media() == OsEntry.Media.DISK) {
                    say.detail("This one installs from a recovery disk rather than a CD, so it keeps"
                            + " booting the installer until you freeze it.");
                }
            }
            setState(MachineState.OFF);
            powerOn();
        } catch (ImageFetch.MediaMissing waiting) {
            installing = null;
            synchronized (this) {
                swapSource(new StandbySource(MachineState.OFF));
                setState(MachineState.OFF);
            }
            say.fault(entry.name() + " needs an image before it can be installed.");
            for (String line : String.valueOf(waiting.getMessage()).split("\\R")) {
                say.detail(line);
            }
            VirtualComputers.LOGGER.info("[machine {}] {} is waiting for media", id, entry.id());
        } catch (IOException | RuntimeException e) {
            installing = null;
            fault = e.getMessage();
            synchronized (this) {
                swapSource(new StandbySource(MachineState.FAILED));
                setState(MachineState.FAILED);
            }
            say.fault(entry.name() + " could not be installed.");
            for (String line : String.valueOf(e.getMessage()).split("\\R")) {
                say.detail(line);
            }

            VirtualComputers.LOGGER.error("[machine {}] install of {} failed", id, entry.id(), e);
        }
    }

    private void cloneFromTemplate(OsEntry entry, Reporter say) throws IOException {
        Path disk = VmStore.diskFor(id);
        Files.deleteIfExists(disk);
        VmStore.ensureDisk(id, entry, spec.diskGb());
        VmStore.setOs(id, entry.id());
        osId = entry.id();
        osKnown = true;
        installing = null;
        synchronized (this) {
            swapSource(new StandbySource(MachineState.OFF));
            setState(MachineState.OFF);
        }
        say.line(entry.name() + " is ready - cloned from the template in "
                + Templates.human(Templates.actualSize(disk)) + ". Right-click to switch it on.");
    }

    private void freshDisk(OsEntry entry) throws IOException {
        Path disk = VmStore.diskFor(id);
        if (Files.exists(disk)) {
            Files.delete(disk);
        }
        Files.deleteIfExists(VmStore.mediaOverlayFor(id));
        VmStore.dropSnapshot(id);
        VmStore.ensureDisk(id, null, entry.diskGb());
        VmStore.setOs(id, entry.id());
        osId = entry.id();
        osKnown = true;
    }

    public void commitTemplate(OsEntry entry, Reporter say) {
        if (entry == null) {
            say.fault("There is nothing on this machine to make a template of.");
            return;
        }

        if (installing != null && !installing.id().equals(entry.id())) {
            say.fault("This machine is installing " + installing.name() + ", not " + entry.name()
                    + ".");
            say.detail("Use that box to freeze it, or the wrong template gets written.");
            return;
        }
        String already = osId();
        if (already != null && !already.equals(entry.id())) {
            say.fault("This machine runs " + osLabel() + ", not " + entry.name() + ".");
            say.detail("Freezing it as " + entry.id() + " would put the wrong system under that name.");
            return;
        }
        if (Templates.exists(entry.id())) {
            say.line(entry.name() + " already has a template; this machine is already using it.");
            return;
        }
        MachinePower.submit(() -> {
            try {
                if (state.isPowered()) {
                    say.line("Shutting the machine down first - a template read from a running"
                            + " machine is a filesystem caught mid-write.");
                    persist();
                    kill(true);
                }
                say.line("Writing the " + entry.name() + " template. This reads the whole disk once.");
                Path template = Templates.freeze(VmStore.diskFor(id), entry.id());

                Path disk = VmStore.diskFor(id);
                Files.deleteIfExists(disk);
                Templates.createOverlay(disk, template, entry.diskGb());
                VmStore.setOs(id, entry.id());
                osId = entry.id();
                osKnown = true;
                installing = null;
                synchronized (this) {
                    swapSource(new StandbySource(MachineState.OFF));
                    setState(MachineState.OFF);
                }
                say.line(entry.name() + " is now a template ("
                        + Templates.human(Templates.actualSize(template))
                        + "). Every machine from here takes seconds.");
            } catch (IOException | RuntimeException e) {
                say.fault("Could not write the template: " + e.getMessage());
                VirtualComputers.LOGGER.error("[machine {}] freeze failed", id, e);
            }
        });
    }

    private Checksum.Progress progressTo(Reporter say) {
        return new Checksum.Progress() {
            private long lastReport;

            @Override
            public void at(long done, long total) {
                long now = System.currentTimeMillis();
                if (now - lastReport < 10_000) {
                    return;
                }
                lastReport = now;
                try {
                    say.line(total > 0
                            ? String.format("  %s of %s (%d%%)", Templates.human(done),
                                    Templates.human(total), done * 100 / total)
                            : "  " + Templates.human(done));
                } catch (RuntimeException e) {
                    VirtualComputers.LOGGER.warn("[machine {}] could not report progress", id, e);
                }
            }
        };
    }

    private static String vendorOf(OsEntry entry) {
        return entry.source().url().replaceFirst("^https://([^/]+)/.*$", "$1");
    }

    public interface Reporter {
        void line(String message);

        default void detail(String message) {
            line(message);
        }

        default void fault(String message) {
            line(message);
        }
    }

    public synchronized void sleep() {
        if (!managed) {
            return;
        }
        QemuVm running = vm;
        if (running == null || !running.isAlive()) {
            return;
        }
        setState(MachineState.SLEEPING);
        endInstallSession();
        MachinePower.submit(running::pause);
        cancelReap();
        reap = MachinePower.reapWhenAbandoned(this);
    }

    public synchronized QemuVm.Persisted persist() {
        if (!managed) {
            return QemuVm.Persisted.ALREADY_STOPPED;
        }
        QemuVm running = vm;
        if (running == null) {
            return QemuVm.Persisted.ALREADY_STOPPED;
        }
        vm = null;
        cancelReap();
        endInstallSession();
        QemuVm.Persisted outcome = running.persist(20_000);
        boolean resumable = outcome == QemuVm.Persisted.SNAPSHOT;
        swapSource(new StandbySource(resumable ? MachineState.SLEEPING : MachineState.OFF));
        setState(resumable ? MachineState.SLEEPING : MachineState.OFF);
        return outcome;
    }

    public synchronized void kill(boolean wait) {
        if (!managed) {
            return;
        }
        QemuVm running = vm;
        vm = null;
        cancelReap();
        endInstallSession();
        swapSource(new StandbySource(MachineState.OFF));
        setState(MachineState.OFF);

        Runnable work = () -> {
            if (running != null) {
                running.kill();
            }
            VmStore.dropSnapshot(id);
        };
        if (wait) {
            work.run();
        } else {
            MachinePower.submit(work);
        }
    }

    private void swapSource(ScreenSource replacement) {
        ScreenSource old = source;
        source = replacement;
        sourceChanged = true;
        if (old != null) {
            old.close();
        }
    }

    private void cancelReap() {
        if (reap != null) {
            reap.cancel(false);
            reap = null;
        }
    }

    private void setState(MachineState newState) {
        state = newState;
        if (source instanceof StandbySource standby) {
            standby.setState(newState);
        }
    }

    public void request(Rung rung) {
        if (rung.ordinal() > pendingDemand.ordinal()) {
            pendingDemand = rung;
        }
    }

    public void prepareFrame(long nowMs) {
        if (failed) {
            return;
        }

        ScreenSource current = source;
        try {
            if (sourceChanged) {
                sourceChanged = false;
                ambient.reset();
                if (live != null) {
                    live.close();
                    live = null;
                }
            }
            observeStartup(current);
            measureFps(nowMs, current);
            ambient.refresh(current, nowMs);

            Rung wanted = pendingDemand;
            pendingDemand = Rung.AMBIENT;

            if (wanted.isAmbient()) {
                if (live != null) {
                    if (ambientSinceMs == 0L) {
                        ambientSinceMs = nowMs;
                    } else if (nowMs - ambientSinceMs >= RELEASE_AFTER_MS) {
                        VirtualComputers.LOGGER.info(
                                "[machine {}] no display has wanted a picture for {} ms; releasing"
                                + " the {}x{} texture", id, nowMs - ambientSinceMs,
                                live.width(), live.height());
                        live.close();
                        live = null;
                        ambientSinceMs = 0L;
                    }
                }
                servedRung = wanted;
                return;
            }
            ambientSinceMs = 0L;

            int[] size = fitToRung(wanted, current);
            if (live == null || live.width() != size[0] || live.height() != size[1]) {
                if (live != null) {
                    VirtualComputers.LOGGER.info("[machine {}] rung {} -> {}, texture {}x{} -> {}x{}",
                            id, servedRung, wanted, live.width(), live.height(), size[0], size[1]);
                    live.close();
                }
                live = new ScreenTexture(id, size[0], size[1]);
            }
            servedRung = wanted;
            live.syncFrom(current);
        } catch (Exception e) {
            failed = true;
            VirtualComputers.LOGGER.error("[machine {}] texture path failed, disabling", id, e);
        }
    }

    private void observeStartup(ScreenSource current) {
        if (state == MachineState.STARTING && current instanceof RfbScreenSource
                && current.status().startsWith("connected")) {
            state = MachineState.RUNNING;
            askAboutThePointer();
            return;
        }
        if (!state.isPowered() || vm == null || vm.isAlive()) {
            return;
        }
        synchronized (this) {
            QemuVm dead = vm;
            if (dead == null || dead.isAlive()) {
                return;
            }
            vm = null;

            if (state == MachineState.STARTING) {
                fault = dead.diagnostic();
                swapSource(new StandbySource(MachineState.FAILED));
                setState(MachineState.FAILED);
                VirtualComputers.LOGGER.error("[machine {}] died before it had a screen:\n{}", id, fault);
            } else {
                boolean snapshot = VmStore.hasSnapshot(id);
                swapSource(new StandbySource(snapshot ? MachineState.SLEEPING : MachineState.OFF));
                setState(snapshot ? MachineState.SLEEPING : MachineState.OFF);
                VirtualComputers.LOGGER.info("[machine {}] the guest powered itself off", id);
            }
        }
    }

    private void askAboutThePointer() {
        if (!managed) {
            return;
        }
        for (long delay : new long[] {8_000, 40_000}) {
            MachinePower.later(() -> {
                QemuVm running = vm;
                if (running == null || !running.isAlive()) {
                    return;
                }
                QemuVm.PointerDevice device = running.pointerDevice();
                if (device != null) {
                    pointerInfo = device.absolute() ? "absolute" : "RELATIVE (will drift)";
                }
            }, delay);
        }
    }

    public String pointerInfo() {
        return pointerInfo;
    }

    public boolean isUsable() {
        return !failed;
    }

    private int[] fitToRung(Rung rung, ScreenSource current) {
        int sourceWidth = Math.max(1, current.width());
        int sourceHeight = Math.max(1, current.height());
        double scale = Math.min(1.0, Math.sqrt((double) rung.pixels() / (sourceWidth * (double) sourceHeight)));
        return new int[] {
                Math.max(1, (int) Math.round(sourceWidth * scale)),
                Math.max(1, (int) Math.round(sourceHeight * scale)) };
    }

    public double sourceFps() {
        return measuredFps;
    }

    private long fpsWindowStartMs;
    private long fpsWindowStartGeneration;
    private double measuredFps;

    private void measureFps(long nowMs, ScreenSource current) {
        long generation = current.generation();
        if (fpsWindowStartMs == 0L) {
            fpsWindowStartMs = nowMs;
            fpsWindowStartGeneration = generation;
            return;
        }
        long elapsed = nowMs - fpsWindowStartMs;
        if (elapsed >= 1000L) {
            measuredFps = Math.max(0.0, (generation - fpsWindowStartGeneration) * 1000.0 / elapsed);
            fpsWindowStartMs = nowMs;
            fpsWindowStartGeneration = generation;
        }
    }

    public int[] liveTextureSize() {
        return live == null ? null : new int[] {live.width(), live.height()};
    }

    public double lastUploadMillis() {
        return live == null ? 0.0 : live.lastUploadMillis();
    }

    public Identifier liveTextureId() {
        return live == null ? null : live.id();
    }

    public Identifier ambientTextureId() {
        return ambient.textureId();
    }

    public AmbientLayer ambient() {
        return ambient;
    }

    public Rung servedRung() {
        return servedRung;
    }

    public String status() {
        return state.label() + " | " + source.status()
                + " | " + servedRung
                + (live == null ? "" : String.format(" %dx%d, upload %.2f ms",
                        live.width(), live.height(), live.lastUploadMillis()));
    }

    @Override
    public void close() {
        source.close();
        ambient.close();
        if (live != null) {
            live.close();
        }
    }

    static Machine create(String id, VmSpec spec) {
        String endpoint = System.getProperty("vc.vnc");
        if (endpoint != null && endpoint.contains(":")) {
            int split = endpoint.lastIndexOf(':');
            return new Machine(id, spec, false, endpoint.substring(0, split),
                    Integer.parseInt(endpoint.substring(split + 1)));
        }
        return new Machine(id, spec, true, null, 0);
    }
}
