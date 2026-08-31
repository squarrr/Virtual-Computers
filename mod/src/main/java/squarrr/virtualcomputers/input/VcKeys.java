package squarrr.virtualcomputers.input;

import squarrr.virtualcomputers.VirtualComputers;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

public final class VcKeys {
    public static final KeyMapping.Category CATEGORY =
            new KeyMapping.Category(Identifier.fromNamespaceAndPath(VirtualComputers.MODID, "general"));

    public static final KeyMapping RELEASE_FOCUS = new KeyMapping(
            "key.virtualcomputers.release_focus", GLFW.GLFW_KEY_GRAVE_ACCENT, CATEGORY);

    private VcKeys() {
    }

    public static void register(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
        event.register(RELEASE_FOCUS);
    }

    public static boolean isReleaseKey(int glfwKey) {
        return !RELEASE_FOCUS.isUnbound()
                && RELEASE_FOCUS.getKey().getValue() == glfwKey
                && RELEASE_FOCUS.getKey().getType() == com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM;
    }

    public static Component releaseKeyName() {
        return RELEASE_FOCUS.getKey().getDisplayName();
    }
}
