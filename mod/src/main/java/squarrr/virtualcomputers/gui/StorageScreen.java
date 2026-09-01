package squarrr.virtualcomputers.gui;

import squarrr.virtualcomputers.PlacedMachines;
import squarrr.virtualcomputers.machine.Machine;
import squarrr.virtualcomputers.machine.MachinePower;
import squarrr.virtualcomputers.machine.Machines;
import squarrr.virtualcomputers.vm.Cleanup;
import squarrr.virtualcomputers.vm.OsEntry;
import squarrr.virtualcomputers.vm.OsRegistry;
import squarrr.virtualcomputers.vm.StorageReport;
import squarrr.virtualcomputers.vm.Templates;
import squarrr.virtualcomputers.vm.VmStore;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class StorageScreen extends Screen {

    private static final int CONTENT = 430;
    private static final int ROW = 12;
    private static final int TOP = 46;

    private static final int NAME_WIDTH = 160;

    private static final int HEADING = 0xFFE8EEF2;
    private static final int BODY = 0xFFB6C0C9;
    private static final int DIM = 0xFF7A848D;
    private static final int ACCENT = 0xFFF0A244;
    private static final int WARNING = 0xFFE86A5C;

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault());

    private sealed interface Target {
        record Computer(StorageReport.Location location, StorageReport.Disk disk) implements Target { }

        record Whole(StorageReport.Group group) implements Target { }

        record File(StorageReport.Group group, StorageReport.Item item) implements Target { }
    }

    private record Row(String left, String os, String when, String size, int colour, boolean rule,
                       Target target) {
        Row(String left, String os, String when, String size, int colour, boolean rule) {
            this(left, os, when, size, colour, rule, null);
        }
    }

    private final Screen parent;
    private volatile StorageReport report;
    private List<Row> rows = List.of();
    private int scroll;
    private int selected = -1;
    private String notice;
    private Button deleteButton;

    public StorageScreen(Screen parent) {
        this(parent, null);
    }

    private StorageScreen(Screen parent, String notice) {
        super(Component.translatable("screen.virtualcomputers.storage"));
        this.parent = parent;
        this.notice = notice;
    }

    @Override
    protected void init() {
        deleteButton = addRenderableWidget(Button.builder(
                        Component.translatable("menu.virtualcomputers.delete"), b -> deleteSelected())
                .bounds(width / 2 - 152, height - 28, 150, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(width / 2 + 2, height - 28, 150, 20).build());
        deleteButton.active = false;

        if (VmStore.sharing() == VmStore.Sharing.UNSET) {
            askAboutOtherInstances();
            return;
        }
        if (report == null) {
            MachinePower.submit(() -> {
                StorageReport collected = StorageReport.collect();
                if (minecraft != null) {
                    minecraft.execute(() -> {
                        report = collected;
                        rows = build(collected);
                        syncDeleteButton();
                    });
                }
            });
        } else {
            rows = build(report);
            syncDeleteButton();
        }
    }

    private void askAboutOtherInstances() {
        if (minecraft == null) {
            return;
        }
        String message = "Computers you make in other modpacks live in that pack's own folder, and"
                + " this one cannot see them.\n\n"
                + "To find them, the mod can keep a plain list of folder paths at\n"
                + VmStore.locationIndex() + "\n\n"
                + "That is the only thing it would write outside this pack, it holds nothing but"
                + " paths, and you can delete it whenever you like. Say no and this screen simply"
                + " shows this pack only.";
        minecraft.gui.setScreen(new ConfirmScreen(
                yes -> {
                    VmStore.setSharing(yes);
                    if (minecraft != null) {
                        minecraft.gui.setScreen(new StorageScreen(parent));
                    }
                },
                Component.literal("Look in your other modpacks?"),
                Component.literal(message),
                Component.literal("Yes, keep the list"),
                Component.literal("No, this pack only")));
    }

    private List<Row> build(StorageReport collected) {
        List<Row> out = new ArrayList<>();
        for (StorageReport.Location location : collected.locations()) {
            String where = location.root().toString();
            out.add(new Row(where + (location.current() ? "  (this instance)" : ""), "", "",
                    Templates.human(location.total()), location.current() ? ACCENT : HEADING, true));

            if (location.disks().isEmpty()) {
                out.add(new Row("  no computers here", "", "", "", DIM, false));
            } else {
                out.add(new Row("  Computers", "Operating system", "State", "Size", DIM, false));
                for (StorageReport.Disk disk : location.disks()) {
                    String name = disk.hostname() != null ? disk.hostname() : shortId(disk.id());
                    boolean here = location.current() && PlacedMachines.inWorld(disk.id());
                    String added = disk.addedEpochMs() > 0
                            ? WHEN.format(Instant.ofEpochMilli(disk.addedEpochMs())) : "";
                    out.add(new Row(fit("    " + name + (here ? "  (this world)" : "")),
                            operatingSystem(disk), state(location, disk),
                            Templates.human(disk.bytes()), here ? ACCENT : BODY, false,
                            new Target.Computer(location, disk)));
                    if (!added.isEmpty()) {
                        out.add(new Row("      added " + added, "", "", "", DIM, false));
                    }
                }
            }

            if (!location.groups().isEmpty()) {
                out.add(new Row("  Everything else", "", "", "", DIM, false));
                for (StorageReport.Group group : location.groups()) {
                    out.add(new Row("    " + group.label(),
                            group.count() + (group.count() == 1 ? " file" : " files"), "",
                            Templates.human(group.bytes()), BODY, false,
                            new Target.Whole(group)));
                    for (StorageReport.Item item : group.items()) {
                        out.add(new Row(fit("      " + itemName(group, item)), "", "",
                                Templates.human(item.bytes()), DIM, false,
                                new Target.File(group, item)));
                    }
                }
            }
            out.add(new Row("", "", "", "", DIM, false));
        }
        if (out.isEmpty()) {
            out.add(new Row("Nothing on disk yet.", "", "", "", DIM, false));
        }
        return List.copyOf(out);
    }

    private static String itemName(StorageReport.Group group, StorageReport.Item item) {
        if (group.kind() == StorageReport.Kind.TEMPLATES) {
            OsEntry entry = OsRegistry.get(item.name());
            return entry != null ? entry.name() : item.name();
        }
        return item.name();
    }

    private String fit(String text) {
        if (font == null || font.width(text) <= NAME_WIDTH) {
            return text;
        }
        StringBuilder trimmed = new StringBuilder(text);
        while (trimmed.length() > 4 && font.width(trimmed + "…") > NAME_WIDTH) {
            trimmed.setLength(trimmed.length() - 1);
        }
        return trimmed + "…";
    }

    private static String operatingSystem(StorageReport.Disk disk) {
        if (disk.os() != null) {
            OsEntry entry = OsRegistry.get(disk.os());
            return entry != null ? entry.name() : disk.os();
        }
        return disk.installed() ? "unknown (installed before this was recorded)" : "empty";
    }

    private static String state(StorageReport.Location location, StorageReport.Disk disk) {
        if (location.current()) {
            Machine machine = Machines.peek(disk.id());
            if (machine != null) {
                return machine.state().label();
            }
        } else {
            return "another pack";
        }
        return disk.hasSnapshot() ? "Sleeping" : "Off";
    }

    private static String shortId(String id) {
        return id.length() > 13 ? id.substring(0, 13) + "…" : id;
    }

    private Target selectedTarget() {
        return selected >= 0 && selected < rows.size() ? rows.get(selected).target() : null;
    }

    private void syncDeleteButton() {
        if (deleteButton != null) {
            deleteButton.active = selectedTarget() != null;
        }
    }

    private Cleanup.Plan planFor(Target target) {
        return switch (target) {
            case Target.Computer computer -> {
                Machine live = computer.location().current()
                        ? Machines.peek(computer.disk().id()) : null;
                Cleanup.Plan plan = Cleanup.forMachine(computer.location().root(),
                        computer.disk().id(), nameOf(computer));
                String busy = live == null ? null
                        : live.hasLiveProcess()
                                ? "That computer is still running. Switch it off first."
                                : live.state().isBusy()
                                        ? "That computer is in the middle of an install." : null;
                yield busy == null ? plan
                        : new Cleanup.Plan(plan.what(), plan.files(), plan.bytes(), busy);
            }
            case Target.Whole whole -> Cleanup.forGroup(whole.group());
            case Target.File file -> Cleanup.forItem(file.group(), file.item());
        };
    }

    private static String nameOf(Target.Computer computer) {
        return computer.disk().hostname() != null
                ? computer.disk().hostname() : shortId(computer.disk().id());
    }

    private void deleteSelected() {
        Target target = selectedTarget();
        if (target == null || minecraft == null) {
            return;
        }
        Cleanup.Plan plan = planFor(target);
        if (!plan.allowed()) {
            notice = plan.refusal() != null ? plan.refusal() : "There is nothing there to delete.";
            return;
        }
        notice = null;

        StringBuilder message = new StringBuilder();
        message.append("This permanently deletes ").append(plan.files().size())
                .append(plan.files().size() == 1 ? " file" : " files")
                .append(" and frees ").append(Templates.human(plan.bytes())).append(".\n\n");
        if (target instanceof Target.Computer computer) {
            message.append("Everything installed on that computer goes with it, and it cannot be"
                    + " undone.\n\n");
            if (PlacedMachines.inWorld(computer.disk().id())) {
                message.append("That computer is placed in the world you are in. The block stays"
                        + " where it is and comes back as an empty machine.\n\n");
            }
        } else if (target instanceof Target.File file
                && file.group().kind() == StorageReport.Kind.MEDIA) {
            message.append("It can be downloaded again from the same place, or dropped back into"
                    + " the images folder yourself.\n\n");
        } else if (target instanceof Target.File file
                && file.group().kind() == StorageReport.Kind.TEMPLATES) {
            message.append("Nothing is built on it right now, but installing it again means"
                    + " sitting through the install once more.\n\n");
        }
        message.append(plan.files().size() == 1 ? plan.files().get(0).toString()
                : plan.files().get(0) + "\nand " + (plan.files().size() - 1) + " more");

        minecraft.gui.setScreen(new ConfirmScreen(
                yes -> {
                    if (yes) {
                        perform(target, plan);
                    } else if (minecraft != null) {
                        minecraft.gui.setScreen(this);
                    }
                },
                Component.literal("Delete " + plan.what() + "?"),
                Component.literal(message.toString()),
                Component.literal("Delete"),
                Component.literal("Keep it")));
    }

    private void perform(Target target, Cleanup.Plan plan) {
        MachinePower.submit(() -> {
            String outcome;
            try {
                outcome = "Deleted " + plan.what() + ", freeing "
                        + Templates.human(Cleanup.delete(plan)) + ".";
            } catch (IOException | RuntimeException e) {
                outcome = "Could not delete " + plan.what() + ": " + e.getMessage();
            }
            String said = outcome;
            if (minecraft == null) {
                return;
            }
            minecraft.execute(() -> {
                if (target instanceof Target.Computer computer && computer.location().current()) {
                    Machines.forget(computer.disk().id());
                }
                if (minecraft != null) {
                    minecraft.gui.setScreen(new StorageScreen(parent, said));
                }
            });
        });
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);

        int left = width / 2 - CONTENT / 2;
        graphics.text(font, title, left, 16, HEADING);

        String summary = report == null ? "Looking..."
                : Templates.human(report.total()) + " in total, across "
                        + report.locations().size()
                        + (report.locations().size() == 1 ? " place" : " places");
        graphics.text(font, summary, left, 28, DIM);
        if (notice != null) {
            graphics.text(font, notice, left, 36, WARNING);
        } else if (report != null) {
            graphics.text(font, "Click anything below to select it, then Delete.", left, 36, DIM);
        }

        int bottom = height - 36;
        int y = TOP - scroll;
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            if (y >= TOP - ROW && y < bottom) {
                if (row.rule()) {
                    graphics.fill(left, y - 3, left + CONTENT, y - 2, 0x40FFFFFF);
                }
                if (index == selected) {
                    graphics.fill(left - 4, y - 2, left + CONTENT + 4, y + ROW - 3, 0x33FFFFFF);
                }
                graphics.text(font, row.left(), left, y, row.colour());
                if (!row.os().isEmpty()) {
                    graphics.text(font, row.os(), left + 168, y, row.colour());
                }
                if (!row.when().isEmpty()) {
                    graphics.text(font, row.when(), left + 330, y, row.colour());
                }
                if (!row.size().isEmpty()) {
                    graphics.text(font, row.size(),
                            left + CONTENT - font.width(row.size()), y, row.colour());
                }
            }
            y += ROW;
        }

        if (maxScroll() > 0) {
            graphics.text(font, "scroll for more", left, bottom + 2, DIM);
        }
    }

    private int maxScroll() {
        int visible = height - 36 - TOP;
        return Math.max(0, rows.size() * ROW - visible);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        int left = width / 2 - CONTENT / 2;
        double x = event.x();
        double y = event.y();
        if (x < left - 4 || x > left + CONTENT + 4 || y < TOP - 2 || y >= height - 36) {
            return false;
        }
        int index = (int) Math.floor((y - (TOP - scroll)) / (double) ROW);
        if (index < 0 || index >= rows.size() || rows.get(index).target() == null) {
            return false;
        }
        selected = index == selected ? -1 : index;
        notice = null;
        syncDeleteButton();
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_DELETE && selectedTarget() != null) {
            deleteSelected();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) (dy * ROW * 2)));
        return true;
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.gui.setScreen(parent);
        }
    }
}
