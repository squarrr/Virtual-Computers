package squarrr.virtualcomputers.screen;

import squarrr.virtualcomputers.ScreenBlockEntity;
import squarrr.virtualcomputers.lod.ScreenQuad;
import squarrr.virtualcomputers.machine.DisplayGeometry;
import squarrr.virtualcomputers.machine.Machines;
import squarrr.virtualcomputers.machine.PanelSpec;
import squarrr.virtualcomputers.vm.VmSpec;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

public class ScreenRenderer extends DisplayRenderer<ScreenBlockEntity> {
    public ScreenRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    protected String machineIdFor(ScreenBlockEntity blockEntity) {
        return Machines.DESKTOP_ID;
    }

    @Override
    protected VmSpec specFor(ScreenBlockEntity blockEntity) {
        return VmSpec.DESKTOP;
    }

    @Override
    protected PanelSpec panelFor(ScreenBlockEntity blockEntity) {
        return PanelSpec.PANEL;
    }

    @Override
    protected ScreenQuad quadFor(ScreenBlockEntity blockEntity) {
        return DisplayGeometry.panelScreen();
    }
}
