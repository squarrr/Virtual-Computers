package squarrr.virtualcomputers.input;

import squarrr.virtualcomputers.DeviceKind;
import squarrr.virtualcomputers.DisplayInteraction;
import squarrr.virtualcomputers.VirtualComputers;
import squarrr.virtualcomputers.lod.ScreenQuad;
import squarrr.virtualcomputers.machine.DisplayGeometry;
import squarrr.virtualcomputers.machine.Machine;
import squarrr.virtualcomputers.machine.MachineState;
import squarrr.virtualcomputers.machine.Machines;
import squarrr.virtualcomputers.machine.PanelSpec;
import squarrr.virtualcomputers.vm.Hypervisor;
import squarrr.virtualcomputers.vm.OsEntry;
import squarrr.virtualcomputers.vm.OsRegistry;
import squarrr.virtualcomputers.vm.VmSpec;
import squarrr.virtualcomputers.vm.VmStore;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

public final class Focus implements DisplayInteraction {
    @Override
    public boolean use(DeviceKind kind, String machineId, BlockPos pos, Direction facing, Vec3 hit) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return false;
        }
        if (machineId == null || machineId.isBlank()) {
            return false;
        }

        Machine machine = Machines.get(machineId,
                kind == DeviceKind.DESKTOP ? VmSpec.DESKTOP : VmSpec.LAPTOP);

        if (machine.state().wantsPowerOn()) {
            return powerOn(minecraft, machine, kind);
        }
        if (machine.state() == MachineState.STARTING && kind.hasScreen()) {
            return openScreen(minecraft, machine, kind, pos, facing, hit);
        }
        if (!kind.hasScreen()) {
            return towerStatus(minecraft, machine);
        }
        return openScreen(minecraft, machine, kind, pos, facing, hit);
    }

    private boolean powerOn(Minecraft minecraft, Machine machine, DeviceKind kind) {
        Hypervisor.Diagnosis diagnosis = Hypervisor.diagnose();
        if (!diagnosis.usable()) {
            say(minecraft, ChatFormatting.RED, "This computer cannot start.");
            for (String line : diagnosis.explanation().split("\\R")) {
                say(minecraft, ChatFormatting.GRAY, line);
            }
            return true;
        }
        if (machine.state() == MachineState.FAILED && machine.fault() != null) {
            say(minecraft, ChatFormatting.RED, "That machine would not start last time:");
            for (String line : machine.fault().split("\\R")) {
                if (!line.isBlank()) {
                    say(minecraft, ChatFormatting.GRAY, "  " + line.trim());
                }
            }
            say(minecraft, ChatFormatting.GRAY, "Trying again…");
        }
        boolean resuming = machine.state() == MachineState.SLEEPING;
        if (machine.powerOn()) {
            say(minecraft, ChatFormatting.GRAY,
                    (resuming ? "Resuming " : "Starting ") + label(kind) + "…");
        }
        return true;
    }

    private boolean towerStatus(Minecraft minecraft, Machine machine) {
        if (minecraft.player != null && minecraft.player.isShiftKeyDown()) {
            say(minecraft, ChatFormatting.GRAY, "Putting the desktop to sleep…");
            machine.sleep();
            return true;
        }
        say(minecraft, ChatFormatting.GRAY, "Desktop is " + machine.state().label().toLowerCase()
                + ". Place a screen to see it; sneak and right-click to sleep it.");
        return true;
    }

    private boolean openScreen(Minecraft minecraft, Machine machine, DeviceKind kind,
                               BlockPos pos, Direction facing, Vec3 hit) {
        if (minecraft.gui.screen() != null) {
            return false;
        }
        ScreenQuad quad;
        PanelSpec panel;
        Component name;
        if (kind == DeviceKind.LAPTOP) {
            quad = DisplayGeometry.laptopScreen(facing);
            panel = PanelSpec.LAPTOP;
            name = Component.translatable("block.virtualcomputers.laptop");
        } else {
            quad = DisplayGeometry.panelScreen();
            panel = PanelSpec.PANEL;
            name = Component.translatable("block.virtualcomputers.screen");
        }
        minecraft.gui.setScreen(new MachineScreen(pos, machine, quad, name, panel, kind, hit));
        return true;
    }

    @Override
    public boolean insertMedia(DeviceKind kind, String machineId, String entryId, boolean finish) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || machineId == null || machineId.isBlank()) {
            return false;
        }
        if (!kind.hasMediaSlot()) {
            say(minecraft, ChatFormatting.GRAY,
                    "A screen has no media slot. Put the box into the tower it is showing.");
            return true;
        }

        OsEntry entry = OsRegistry.get(entryId);
        if (entry == null) {
            say(minecraft, ChatFormatting.RED, "This box refers to \"" + entryId
                    + "\", and there is no entry by that name.");
            say(minecraft, ChatFormatting.GRAY, "Drop a matching .json into "
                    + VmStore.root().resolve("os") + " and try again.");
            return true;
        }

        Machine machine = Machines.get(machineId,
                kind == DeviceKind.DESKTOP ? VmSpec.DESKTOP : VmSpec.LAPTOP);
        Machine.Reporter say = reporter(minecraft);

        if (finish) {
            machine.commitTemplate(entry, say);
        } else {
            machine.install(entry, false, say);
        }
        return true;
    }

    private static Machine.Reporter reporter(Minecraft minecraft) {
        return new Machine.Reporter() {
            @Override
            public void line(String message) {
                minecraft.execute(() -> say(minecraft, ChatFormatting.GRAY, message));
            }

            @Override
            public void detail(String message) {
                minecraft.execute(() -> say(minecraft, ChatFormatting.DARK_GRAY, "  " + message));
            }

            @Override
            public void fault(String message) {
                minecraft.execute(() -> say(minecraft, ChatFormatting.RED, message));
            }
        };
    }

    @Override
    public void broken(DeviceKind kind, String machineId) {
        if (machineId == null || machineId.isBlank()) {
            return;
        }
        Machine machine = Machines.peek(machineId);
        if (machine == null || !machine.state().isPowered()) {
            return;
        }

        if (kind.sleepsWhenBroken()) {
            VirtualComputers.LOGGER.info("[machine {}] lid closed; pausing in place", machineId);
            machine.sleep();
        } else {
            VirtualComputers.LOGGER.info("[machine {}] power pulled; nothing kept", machineId);
            machine.kill(false);
        }
    }

    private static String label(DeviceKind kind) {
        return kind == DeviceKind.DESKTOP ? "the desktop" : "the laptop";
    }

    private static void say(Minecraft minecraft, ChatFormatting colour, String message) {
        if (minecraft.player != null) {
            minecraft.player.sendSystemMessage(Component.literal(message).withStyle(colour));
        }
    }
}
