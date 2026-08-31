package squarrr.virtualcomputers.screen;

import squarrr.virtualcomputers.ComputerBlock;
import squarrr.virtualcomputers.LaptopBlockEntity;
import squarrr.virtualcomputers.lod.ScreenQuad;
import squarrr.virtualcomputers.machine.DisplayGeometry;
import squarrr.virtualcomputers.machine.PanelSpec;
import squarrr.virtualcomputers.vm.VmSpec;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

public class LaptopRenderer extends DisplayRenderer<LaptopBlockEntity> {
    public LaptopRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    protected @Nullable String machineIdFor(LaptopBlockEntity blockEntity) {
        return blockEntity.machineId();
    }

    @Override
    protected VmSpec specFor(LaptopBlockEntity blockEntity) {
        return VmSpec.LAPTOP;
    }

    @Override
    protected PanelSpec panelFor(LaptopBlockEntity blockEntity) {
        return PanelSpec.LAPTOP;
    }

    @Override
    protected @Nullable ScreenQuad quadFor(LaptopBlockEntity blockEntity) {
        BlockState state = blockEntity.getBlockState();
        if (!state.hasProperty(ComputerBlock.FACING)) {
            return null;
        }
        Direction facing = state.getValue(ComputerBlock.FACING);
        return DisplayGeometry.laptopScreen(facing);
    }
}
