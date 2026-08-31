package squarrr.virtualcomputers.screen;

import squarrr.virtualcomputers.lod.ScreenQuad;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

public class DisplayRenderState extends BlockEntityRenderState {
    public @Nullable ScreenQuad quad;

    public @Nullable ScreenQuad liveQuad;

    public @Nullable Identifier ambientTexture;

    public @Nullable Identifier liveTexture;

    public float liveMix;

    public squarrr.virtualcomputers.machine.PanelSpec panel = squarrr.virtualcomputers.machine.PanelSpec.PANEL;
}
