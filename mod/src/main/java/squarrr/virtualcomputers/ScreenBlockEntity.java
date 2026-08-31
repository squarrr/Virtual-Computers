package squarrr.virtualcomputers;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class ScreenBlockEntity extends BlockEntity {
    public ScreenBlockEntity(BlockPos pos, BlockState state) {
        super(VirtualComputers.SCREEN_BE.get(), pos, state);
    }
}
