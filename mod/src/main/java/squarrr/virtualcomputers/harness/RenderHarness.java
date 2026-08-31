package squarrr.virtualcomputers.harness;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import squarrr.virtualcomputers.ComputerBlock;
import squarrr.virtualcomputers.VirtualComputers;
import squarrr.virtualcomputers.lod.LodState;
import squarrr.virtualcomputers.lod.ScreenQuad;
import squarrr.virtualcomputers.machine.DisplayGeometry;
import squarrr.virtualcomputers.screen.DisplayRenderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

public final class RenderHarness {
    private static final float MARKER_U = 0.02F;
    private static final float MARKER_V = 0.02F;

    private static final int[][] BAR_RGB = {
            {255, 255, 255}, {255, 255, 0}, {0, 255, 255}, {0, 255, 0},
            {255, 0, 255}, {255, 0, 0}, {0, 0, 255}, {0, 0, 0}};
    private static final float BARS_V = 0.20F;

    private static final int TOLERANCE = 64;

    private RenderHarness() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("vcharness").executes(context -> {
            run();
            return 1;
        });
    }

    private static void run() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        List<Target> targets = findDisplays(minecraft);
        if (targets.isEmpty()) {
            say(minecraft, "harness: no laptop within 32 blocks. Place one and look at it.");
            return;
        }

        Camera camera = minecraft.gameRenderer.mainCamera();
        int frameWidth = minecraft.getWindow().getWidth();
        int frameHeight = minecraft.getWindow().getHeight();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());
        Vector3f eye = new Vector3f((float) camera.position().x,
                (float) camera.position().y, (float) camera.position().z);

        Screenshot.takeScreenshot(minecraft.gameRenderer.mainRenderTarget(), image -> {
            try {
                List<String> report = new ArrayList<>();
                int failures = 0;
                for (Target target : targets) {
                    failures += check(target, image, viewProjection, eye, frameWidth, frameHeight, report);
                }
                Path dump = Path.of("run", "harness", "harness.png");
                Files.createDirectories(dump.getParent());
                image.writeToFile(dump);

                for (String line : report) {
                    VirtualComputers.LOGGER.info("[harness] {}", line);
                }
                VirtualComputers.LOGGER.info("[harness] frame written to {}", dump.toAbsolutePath());
                say(minecraft, failures == 0
                        ? "harness: PASS across " + targets.size() + " display(s). See the log for detail."
                        : "harness: FAIL, " + failures + " problem(s). See the log and run/harness/harness.png.");
            } catch (Exception e) {
                VirtualComputers.LOGGER.error("[harness] failed", e);
            } finally {
                image.close();
            }
        });
    }

    private static List<Target> findDisplays(Minecraft minecraft) {
        List<Target> found = new ArrayList<>();
        BlockPos origin = minecraft.player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-32, -16, -32), origin.offset(32, 16, 32))) {
            BlockState state = minecraft.level.getBlockState(pos);
            if (state.is(VirtualComputers.LAPTOP_BLOCK.get()) && state.hasProperty(ComputerBlock.FACING)) {
                Direction facing = state.getValue(ComputerBlock.FACING);
                found.add(new Target(pos.immutable(), DisplayGeometry.laptopScreen(facing), facing));
            }
        }
        return found;
    }

    private static int check(Target target, NativeImage image, Matrix4f viewProjection,
                             Vector3f eye, int frameWidth, int frameHeight, List<String> report) {
        LodState ladder = DisplayRenderer.ladderAt(target.pos);
        String where = target.pos.toShortString() + " facing " + target.facing;

        if (ladder == null) {
            report.add(where + ": never rendered — no ladder state, so extract has not run on it");
            return 1;
        }
        report.add(where + ": " + ladder);
        if (ladder.rung().isAmbient()) {
            report.add("  skipped: serving the ambient layer, so there is no pattern to check");
            return 0;
        }

        int problems = 0;

        int[] marker = sample(target, MARKER_U, MARKER_V, image, viewProjection, eye, frameWidth, frameHeight);
        if (marker == null) {
            report.add("  orientation marker projected off-screen; aim at the display and retry");
            return 1;
        }
        if (marker[0] > 150 && marker[1] < 110 && marker[2] < 110) {
            report.add(String.format("  orientation marker top-left: rgb(%d,%d,%d) OK", marker[0], marker[1], marker[2]));
        } else {
            report.add(String.format("  orientation marker top-left: rgb(%d,%d,%d) NOT RED — "
                    + "the picture is rotated, mirrored, or the UVs are wrong", marker[0], marker[1], marker[2]));
            problems++;
        }

        for (int bar = 0; bar < BAR_RGB.length; bar++) {
            float u = (bar + 0.5F) / BAR_RGB.length;
            int[] got = sample(target, u, BARS_V, image, viewProjection, eye, frameWidth, frameHeight);
            if (got == null) {
                continue;
            }
            int[] want = BAR_RGB[bar];
            if (Math.abs(got[0] - want[0]) > TOLERANCE
                    || Math.abs(got[1] - want[1]) > TOLERANCE
                    || Math.abs(got[2] - want[2]) > TOLERANCE) {
                report.add(String.format("  colour bar %d: rgb(%d,%d,%d), wanted rgb(%d,%d,%d)%s",
                        bar, got[0], got[1], got[2], want[0], want[1], want[2],
                        redBlueSwapped(got, want) ? " — red and blue are transposed" : ""));
                problems++;
            }
        }
        if (problems == 0) {
            report.add("  colour bars in order, channels correct");
        }
        return problems;
    }

    private static boolean redBlueSwapped(int[] got, int[] want) {
        return Math.abs(got[0] - want[2]) <= TOLERANCE && Math.abs(got[2] - want[0]) <= TOLERANCE
                && Math.abs(got[0] - want[0]) > TOLERANCE;
    }

    private static int[] sample(Target target, float u, float v, NativeImage image,
                                Matrix4f viewProjection, Vector3f eye, int frameWidth, int frameHeight) {
        Vector3f world = target.quad.pointAt(
                new Vector3f(target.pos.getX(), target.pos.getY(), target.pos.getZ()), u, v);

        Vector4f clip = new Vector4f(world.x - eye.x, world.y - eye.y, world.z - eye.z, 1.0F);
        viewProjection.transform(clip);
        if (clip.w <= 1.0E-4F) {
            return null;
        }
        int px = (int) ((clip.x / clip.w * 0.5 + 0.5) * frameWidth);
        int py = (int) ((0.5 - clip.y / clip.w * 0.5) * frameHeight);
        if (px < 0 || py < 0 || px >= image.getWidth() || py >= image.getHeight()) {
            return null;
        }

        int abgr = image.getPixel(px, py);
        return new int[] {abgr & 0xFF, abgr >> 8 & 0xFF, abgr >> 16 & 0xFF};
    }

    private static void say(Minecraft minecraft, String message) {
        if (minecraft.player != null) {
            minecraft.player.sendSystemMessage(Component.literal(message));
        }
        VirtualComputers.LOGGER.info("[harness] {}", message);
    }

    private record Target(BlockPos pos, ScreenQuad quad, Direction facing) {
    }
}
