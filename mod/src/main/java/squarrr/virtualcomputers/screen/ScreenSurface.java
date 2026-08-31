package squarrr.virtualcomputers.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import squarrr.virtualcomputers.lod.ScreenQuad;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import org.joml.Vector3f;

public final class ScreenSurface {
    public static final int FULL_BRIGHT = 0xF000F0;

    private ScreenSurface() {
    }

    public static void draw(SubmitNodeCollector collector, PoseStack poseStack,
                            ScreenQuad quad, ScreenQuad liveQuad,
                            Identifier ambientTexture, Identifier liveTexture, float liveMix) {
        collector.submitCustomGeometry(poseStack, RenderTypes.entitySolid(ambientTexture),
                (pose, buffer) -> emit(pose, buffer, quad, 0xFFFFFFFF));
        if (liveTexture != null && liveMix > 0.0F) {
            int alpha = (int) (Math.min(1.0F, liveMix) * 255.0F) & 0xFF;

            if (alpha >= 255) {
                collector.submitCustomGeometry(poseStack, RenderTypes.entitySolid(liveTexture),
                        (pose, buffer) -> emit(pose, buffer, liveQuad, 0xFFFFFFFF));
            } else {
                collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucent(liveTexture),
                        (pose, buffer) -> emit(pose, buffer, liveQuad, alpha << 24 | 0xFFFFFF));
            }
        }
    }

    private static void emit(PoseStack.Pose pose, VertexConsumer buffer, ScreenQuad quad, int argb) {
        Vector3f front = quad.frontNormal();
        Vector3f back = new Vector3f(front).negate();
        for (int i = 3; i >= 0; i--) {
            vertex(pose, buffer, quad.corner(i), ScreenQuad.UV[i][0], ScreenQuad.UV[i][1], argb, front);
        }
        for (int i = 0; i < 4; i++) {
            vertex(pose, buffer, quad.corner(i), ScreenQuad.UV[i][0], ScreenQuad.UV[i][1], argb, back);
        }
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buffer, Vector3f at,
                               float u, float v, int argb, Vector3f normal) {
        buffer.addVertex(pose, at.x, at.y, at.z)
                .setColor(argb)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(FULL_BRIGHT)
                .setNormal(pose, normal.x, normal.y, normal.z);
    }
}
