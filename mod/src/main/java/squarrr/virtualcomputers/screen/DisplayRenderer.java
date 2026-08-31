package squarrr.virtualcomputers.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import squarrr.virtualcomputers.lod.LodState;
import squarrr.virtualcomputers.lod.Rung;
import squarrr.virtualcomputers.lod.ScreenQuad;
import squarrr.virtualcomputers.machine.Machine;
import squarrr.virtualcomputers.machine.Machines;
import squarrr.virtualcomputers.machine.PanelSpec;
import squarrr.virtualcomputers.vm.VmSpec;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

public abstract class DisplayRenderer<T extends BlockEntity>
        implements BlockEntityRenderer<T, DisplayRenderState> {
    private static final Map<BlockPos, LodState> LADDER = new HashMap<>();

    private static final float LIVE_LAYER_LIFT = 0.0005F;

    protected abstract @Nullable ScreenQuad quadFor(T blockEntity);

    protected abstract @Nullable String machineIdFor(T blockEntity);

    protected VmSpec specFor(T blockEntity) {
        return VmSpec.LAPTOP;
    }

    protected PanelSpec panelFor(T blockEntity) {
        return PanelSpec.PANEL;
    }

    @Override
    public DisplayRenderState createRenderState() {
        return new DisplayRenderState();
    }

    @Override
    public void extractRenderState(T blockEntity, DisplayRenderState state, float partialTicks,
                                   Vec3 cameraPosition, ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(blockEntity, state, partialTicks, cameraPosition, breakProgress);

        state.quad = null;
        state.liveTexture = null;
        state.ambientTexture = null;

        ScreenQuad quad = quadFor(blockEntity);
        String machineId = machineIdFor(blockEntity);
        if (quad == null || machineId == null) {
            return;
        }
        Machine machine = Machines.get(machineId, specFor(blockEntity));

        Minecraft minecraft = Minecraft.getInstance();
        Camera camera = minecraft.gameRenderer.mainCamera();

        double footprint = camera.isInitialized()
                ? quad.footprintPixels(
                        blockOrigin(blockEntity.getBlockPos()),
                        camera.getViewRotationProjectionMatrix(new Matrix4f()),
                        new Vector3f((float) camera.position().x, (float) camera.position().y,
                                (float) camera.position().z),
                        minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight())
                : 0.0;

        LodState ladder = LADDER.computeIfAbsent(blockEntity.getBlockPos().immutable(), pos -> new LodState());
        PanelSpec panel = panelFor(blockEntity);

        Rung rung = ladder.update(footprint, System.currentTimeMillis());
        if (rung.ordinal() > panel.ceiling().ordinal()) {
            rung = panel.ceiling();
        }
        machine.request(rung);
        state.panel = panel;

        if (!machine.isUsable()) {
            return;
        }
        state.quad = quad;

        state.liveQuad = quad
                .fitPreservingAspect(machine.source().width(), machine.source().height())
                .raisedBy(LIVE_LAYER_LIFT);
        state.ambientTexture = machine.ambientTextureId();
        state.liveTexture = machine.liveTextureId();

        state.liveMix = state.liveTexture == null ? 0.0F : ladder.liveMix();
    }

    @Override
    public void submit(DisplayRenderState state, PoseStack poseStack,
                       SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.quad == null || state.ambientTexture == null) {
            return;
        }
        ScreenSurface.draw(collector, poseStack, state.quad,
                state.liveQuad == null ? state.quad : state.liveQuad,
                state.ambientTexture, state.liveTexture, state.liveMix);
    }

    static Vector3f blockOrigin(BlockPos pos) {
        return new Vector3f(pos.getX(), pos.getY(), pos.getZ());
    }

    public static @Nullable LodState ladderAt(BlockPos pos) {
        return LADDER.get(pos);
    }

    public static void forgetAll() {
        LADDER.clear();
    }
}
