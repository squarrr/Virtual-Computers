package squarrr.virtualcomputers;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public class LaptopBlockEntity extends ComputerBlockEntity {
    public LaptopBlockEntity(BlockPos pos, BlockState state) {
        super(VirtualComputers.LAPTOP_BE.get(), pos, state);
    }
}
