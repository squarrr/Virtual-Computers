package squarrr.virtualcomputers;

import squarrr.virtualcomputers.machine.Machines;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

public class DesktopBlockEntity extends ComputerBlockEntity {
    public DesktopBlockEntity(BlockPos pos, BlockState state) {
        super(VirtualComputers.DESKTOP_BE.get(), pos, state);
    }

    @Override
    public String machineId() {
        return Machines.DESKTOP_ID;
    }

    @Override
    public void adoptFrom(ItemStack stack) {
    }

    @Override
    public ItemStack withIdentity(ItemStack stack) {
        return stack;
    }
}
