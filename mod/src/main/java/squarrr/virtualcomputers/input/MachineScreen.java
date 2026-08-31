package squarrr.virtualcomputers.input;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import squarrr.virtualcomputers.DeviceKind;
import squarrr.virtualcomputers.VirtualComputers;
import squarrr.virtualcomputers.lod.ScreenQuad;
import squarrr.virtualcomputers.machine.Machine;
import squarrr.virtualcomputers.screen.ScreenInput;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

public class MachineScreen extends Screen {
    private static @Nullable MachineScreen active;

    private final BlockPos pos;
    private final Machine machine;
    private final ScreenQuad quad;
    private final Component deviceName;
    private final squarrr.virtualcomputers.machine.PanelSpec panel;
    private final DeviceKind kind;

    private final Map<Integer, Integer> sentKeysyms = new HashMap<>();

    private int buttonMask;
    private float cursorU = 0.5F;
    private float cursorV = 0.5F;

    private boolean cursorCaptured;

    private boolean resyncPointer = true;
    private double lastRawX;
    private double lastRawY;

    public MachineScreen(BlockPos pos, Machine machine, ScreenQuad quad, Component deviceName,
                         squarrr.virtualcomputers.machine.PanelSpec panel, DeviceKind kind, @Nullable Vec3 hitLocation) {
        super(Component.translatable("screen.virtualcomputers.focused"));
        this.pos = pos;
        this.machine = machine;
        this.quad = quad;
        this.deviceName = deviceName;
        this.panel = panel;
        this.kind = kind;
        seedCursorFrom(hitLocation);
    }

    private ScreenQuad pictureQuad() {
        return quad.fitPreservingAspect(machine.source().width(), machine.source().height());
    }

    private void seedCursorFrom(@Nullable Vec3 hitLocation) {
        if (hitLocation == null) {
            return;
        }

        float[] uv = pictureQuad().uvOfPoint(
                new Vector3f(pos.getX(), pos.getY(), pos.getZ()),
                new Vector3f((float) hitLocation.x, (float) hitLocation.y, (float) hitLocation.z));
        if (uv != null) {
            cursorU = uv[0];
            cursorV = uv[1];
        }
    }

    public static @Nullable MachineScreen active() {
        return active;
    }

    public BlockPos focusedPos() {
        return pos;
    }

    @Override
    protected void init() {
        active = this;
        captureCursor();
        sendPointer();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
    }

    private void captureCursor() {
        if (cursorCaptured || minecraft == null || !minecraft.isWindowActive()) {
            return;
        }
        Window window = minecraft.getWindow();
        InputConstants.grabOrReleaseMouse(window, GLFW.GLFW_CURSOR_DISABLED,
                window.getScreenWidth() / 2.0, window.getScreenHeight() / 2.0);

        window.updateRawMouseInput(false);

        cursorCaptured = true;
        resyncPointer = true;
    }

    private void releaseCursor() {
        if (!cursorCaptured || minecraft == null) {
            return;
        }
        Window window = minecraft.getWindow();
        InputConstants.grabOrReleaseMouse(window, GLFW.GLFW_CURSOR_NORMAL,
                window.getScreenWidth() / 2.0, window.getScreenHeight() / 2.0);

        window.updateRawMouseInput(minecraft.options.rawMouseInput().get());
        cursorCaptured = false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);

        Component leave = VcKeys.RELEASE_FOCUS.isUnbound()
                ? Component.translatable("hud.virtualcomputers.unbound")
                : Component.translatable("hud.virtualcomputers.release", VcKeys.releaseKeyName());

        String pointer = String.format("%d,%d %s", guestX(), guestY(), machine.pointerInfo());

        int[] textureSize = machine.liveTextureSize();
        int guestWidth = machine.source().width();
        int guestHeight = machine.source().height();
        boolean matchesPanel = guestWidth == panel.width() && guestHeight == panel.height();
        String video = String.format("panel %s  guest %dx%d%s  tex %s  %s  %.0f fps  %.1f ms",
                panel,
                guestWidth, guestHeight, matchesPanel ? "" : " (!)",
                textureSize == null ? "-" : textureSize[0] + "x" + textureSize[1],
                machine.servedRung(), machine.sourceFps(), machine.lastUploadMillis());

        Component heading = Component.literal(
                deviceName.getString() + "  -  " + machine.state().label());

        int boxWidth = Math.max(Math.max(font.width(leave), font.width(heading)),
                Math.max(font.width(pointer) + 34, font.width(video))) + 12;

        graphics.fill(4, 4, 4 + boxWidth, 52, 0xA0101418);
        graphics.text(font, heading, 10, 9, 0xFFE8EEF2);
        graphics.text(font, leave, 10, 20, 0xFFF0A244);
        graphics.text(font, pointer, 10, 31, 0xFF8A97A2);
        graphics.text(font, video, 10, 42, 0xFF8A97A2);

        int lampX = 10 + font.width(pointer) + 8;
        lamp(graphics, lampX, "L", ScreenInput.BUTTON_LEFT);
        lamp(graphics, lampX + 10, "M", ScreenInput.BUTTON_MIDDLE);
        lamp(graphics, lampX + 20, "R", ScreenInput.BUTTON_RIGHT);
    }

    private void lamp(GuiGraphicsExtractor graphics, int x, String label, int bit) {
        graphics.text(font, label, x, 31, (buttonMask & bit) != 0 ? 0xFF6BE675 : 0xFF3A434C);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (VcKeys.isReleaseKey(event.key())) {
            onClose();
            return true;
        }
        if (event.isEscape() && VcKeys.RELEASE_FOCUS.isUnbound()) {
            onClose();
            return true;
        }
        ScreenInput input = machine.input();
        if (input != null) {
            int keysym = Keysyms.forGlfwKey(event.key(), event.hasShiftDown());
            if (keysym != 0) {
                sentKeysyms.put(event.key(), keysym);
                input.keyEvent(keysym, true);
            }
        }
        return true;
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        ScreenInput input = machine.input();
        Integer keysym = sentKeysyms.remove(event.key());
        if (input != null && keysym != null) {
            input.keyEvent(keysym, false);
        }
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return true;
    }

    @Override
    public void mouseMoved(double ignoredX, double ignoredY) {
        if (!cursorCaptured || minecraft == null) {
            return;
        }
        double[] rawX = new double[1];
        double[] rawY = new double[1];
        GLFW.glfwGetCursorPos(minecraft.getWindow().handle(), rawX, rawY);

        if (resyncPointer) {
            lastRawX = rawX[0];
            lastRawY = rawY[0];
            resyncPointer = false;
            return;
        }
        double dx = rawX[0] - lastRawX;
        double dy = rawY[0] - lastRawY;
        lastRawX = rawX[0];
        lastRawY = rawY[0];

        int guestWidth = Math.max(1, machine.source().width());
        int guestHeight = Math.max(1, machine.source().height());

        cursorU = clamp01((float) (cursorU + dx / guestWidth));
        cursorV = clamp01((float) (cursorV + dy / guestHeight));
        sendPointer();
    }

    private static float clamp01(float value) {
        return value < 0.0F ? 0.0F : Math.min(value, 1.0F);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!cursorCaptured) {
            captureCursor();
            return true;
        }
        buttonMask |= buttonBit(event.button());
        sendPointer();
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        buttonMask &= ~buttonBit(event.button());
        sendPointer();
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0.0) {
            return true;
        }

        int wheel = scrollY > 0 ? ScreenInput.WHEEL_UP : ScreenInput.WHEEL_DOWN;
        ScreenInput input = machine.input();
        if (input != null) {
            input.pointerEvent(guestX(), guestY(), buttonMask | wheel);
            input.pointerEvent(guestX(), guestY(), buttonMask);
        }
        return true;
    }

    private static int buttonBit(int glfwButton) {
        return switch (glfwButton) {
            case 0 -> ScreenInput.BUTTON_LEFT;
            case 1 -> ScreenInput.BUTTON_RIGHT;
            case 2 -> ScreenInput.BUTTON_MIDDLE;
            default -> 0;
        };
    }

    private int guestX() {
        return (int) (cursorU * (machine.source().width() - 1));
    }

    private int guestY() {
        return (int) (cursorV * (machine.source().height() - 1));
    }

    private void sendPointer() {
        ScreenInput input = machine.input();
        if (input != null) {
            input.pointerEvent(guestX(), guestY(), buttonMask);
        }
    }

    @Override
    public void tick() {
        if (minecraft == null || minecraft.player == null || minecraft.level == null) {
            onClose();
            return;
        }

        if (!minecraft.isWindowActive()) {
            releaseCursor();
        } else if (!cursorCaptured) {
            captureCursor();
        }

        if (minecraft.player.hurtTime > 0) {
            onClose();
            return;
        }
        if (!minecraft.level.getBlockState(pos).is(expectedBlock())) {
            onClose();
            return;
        }
        if (!machine.state().isPowered()) {
            onClose();
            return;
        }
        if (minecraft.player.position().distanceToSqr(Vec3.atCenterOf(pos)) > REACH_SQR) {
            onClose();
        }
    }

    private static final double REACH_SQR = 5.0 * 5.0;

    @Override
    public void onClose() {
        ScreenInput input = machine.input();
        if (input != null) {
            input.releaseAllKeys();
            input.pointerEvent(guestX(), guestY(), 0);
        }
        buttonMask = 0;
        sentKeysyms.clear();
        active = null;
        releaseCursor();

        super.onClose();
    }

    private net.minecraft.world.level.block.Block expectedBlock() {
        return kind == DeviceKind.LAPTOP
                ? VirtualComputers.LAPTOP_BLOCK.get()
                : VirtualComputers.SCREEN_BLOCK.get();
    }
}
