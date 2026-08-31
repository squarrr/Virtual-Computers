package squarrr.virtualcomputers.input;

import java.util.HashMap;
import java.util.Map;
import org.lwjgl.glfw.GLFW;

public final class Keysyms {
    public static final int BACKSPACE = 0xFF08;
    public static final int TAB = 0xFF09;
    public static final int RETURN = 0xFF0D;
    public static final int ESCAPE = 0xFF1B;
    public static final int INSERT = 0xFF63;
    public static final int DELETE = 0xFFFF;
    public static final int HOME = 0xFF50;
    public static final int END = 0xFF57;
    public static final int PAGE_UP = 0xFF55;
    public static final int PAGE_DOWN = 0xFF56;
    public static final int LEFT = 0xFF51;
    public static final int UP = 0xFF52;
    public static final int RIGHT = 0xFF53;
    public static final int DOWN = 0xFF54;
    public static final int SHIFT_LEFT = 0xFFE1;
    public static final int SHIFT_RIGHT = 0xFFE2;
    public static final int CONTROL_LEFT = 0xFFE3;
    public static final int CONTROL_RIGHT = 0xFFE4;
    public static final int ALT_LEFT = 0xFFE9;
    public static final int ALT_RIGHT = 0xFFEA;
    public static final int SUPER_LEFT = 0xFFEB;
    public static final int SUPER_RIGHT = 0xFFEC;
    public static final int CAPS_LOCK = 0xFFE5;

    private static final Map<Integer, Integer> SPECIAL = new HashMap<>();

    private static final Map<Integer, int[]> PUNCTUATION = new HashMap<>();

    static {
        SPECIAL.put(GLFW.GLFW_KEY_BACKSPACE, BACKSPACE);
        SPECIAL.put(GLFW.GLFW_KEY_TAB, TAB);
        SPECIAL.put(GLFW.GLFW_KEY_ENTER, RETURN);
        SPECIAL.put(GLFW.GLFW_KEY_KP_ENTER, RETURN);
        SPECIAL.put(GLFW.GLFW_KEY_ESCAPE, ESCAPE);
        SPECIAL.put(GLFW.GLFW_KEY_INSERT, INSERT);
        SPECIAL.put(GLFW.GLFW_KEY_DELETE, DELETE);
        SPECIAL.put(GLFW.GLFW_KEY_HOME, HOME);
        SPECIAL.put(GLFW.GLFW_KEY_END, END);
        SPECIAL.put(GLFW.GLFW_KEY_PAGE_UP, PAGE_UP);
        SPECIAL.put(GLFW.GLFW_KEY_PAGE_DOWN, PAGE_DOWN);
        SPECIAL.put(GLFW.GLFW_KEY_LEFT, LEFT);
        SPECIAL.put(GLFW.GLFW_KEY_UP, UP);
        SPECIAL.put(GLFW.GLFW_KEY_RIGHT, RIGHT);
        SPECIAL.put(GLFW.GLFW_KEY_DOWN, DOWN);
        SPECIAL.put(GLFW.GLFW_KEY_LEFT_SHIFT, SHIFT_LEFT);
        SPECIAL.put(GLFW.GLFW_KEY_RIGHT_SHIFT, SHIFT_RIGHT);
        SPECIAL.put(GLFW.GLFW_KEY_LEFT_CONTROL, CONTROL_LEFT);
        SPECIAL.put(GLFW.GLFW_KEY_RIGHT_CONTROL, CONTROL_RIGHT);
        SPECIAL.put(GLFW.GLFW_KEY_LEFT_ALT, ALT_LEFT);
        SPECIAL.put(GLFW.GLFW_KEY_RIGHT_ALT, ALT_RIGHT);
        SPECIAL.put(GLFW.GLFW_KEY_LEFT_SUPER, SUPER_LEFT);
        SPECIAL.put(GLFW.GLFW_KEY_RIGHT_SUPER, SUPER_RIGHT);
        SPECIAL.put(GLFW.GLFW_KEY_CAPS_LOCK, CAPS_LOCK);
        SPECIAL.put(GLFW.GLFW_KEY_SPACE, (int) ' ');

        for (int i = 0; i <= 11; i++) {
            SPECIAL.put(GLFW.GLFW_KEY_F1 + i, 0xFFBE + i);
        }
        for (int i = 0; i <= 9; i++) {
            SPECIAL.put(GLFW.GLFW_KEY_KP_0 + i, 0xFFB0 + i);
        }
        SPECIAL.put(GLFW.GLFW_KEY_KP_DECIMAL, 0xFFAE);
        SPECIAL.put(GLFW.GLFW_KEY_KP_DIVIDE, 0xFFAF);
        SPECIAL.put(GLFW.GLFW_KEY_KP_MULTIPLY, 0xFFAA);
        SPECIAL.put(GLFW.GLFW_KEY_KP_SUBTRACT, 0xFFAD);
        SPECIAL.put(GLFW.GLFW_KEY_KP_ADD, 0xFFAB);

        PUNCTUATION.put(GLFW.GLFW_KEY_APOSTROPHE, new int[] {'\'', '"'});
        PUNCTUATION.put(GLFW.GLFW_KEY_COMMA, new int[] {',', '<'});
        PUNCTUATION.put(GLFW.GLFW_KEY_MINUS, new int[] {'-', '_'});
        PUNCTUATION.put(GLFW.GLFW_KEY_PERIOD, new int[] {'.', '>'});
        PUNCTUATION.put(GLFW.GLFW_KEY_SLASH, new int[] {'/', '?'});
        PUNCTUATION.put(GLFW.GLFW_KEY_SEMICOLON, new int[] {';', ':'});
        PUNCTUATION.put(GLFW.GLFW_KEY_EQUAL, new int[] {'=', '+'});
        PUNCTUATION.put(GLFW.GLFW_KEY_LEFT_BRACKET, new int[] {'[', '{'});
        PUNCTUATION.put(GLFW.GLFW_KEY_BACKSLASH, new int[] {'\\', '|'});
        PUNCTUATION.put(GLFW.GLFW_KEY_RIGHT_BRACKET, new int[] {']', '}'});
        PUNCTUATION.put(GLFW.GLFW_KEY_GRAVE_ACCENT, new int[] {'`', '~'});
        PUNCTUATION.put(GLFW.GLFW_KEY_0, new int[] {'0', ')'});
        PUNCTUATION.put(GLFW.GLFW_KEY_1, new int[] {'1', '!'});
        PUNCTUATION.put(GLFW.GLFW_KEY_2, new int[] {'2', '@'});
        PUNCTUATION.put(GLFW.GLFW_KEY_3, new int[] {'3', '#'});
        PUNCTUATION.put(GLFW.GLFW_KEY_4, new int[] {'4', '$'});
        PUNCTUATION.put(GLFW.GLFW_KEY_5, new int[] {'5', '%'});
        PUNCTUATION.put(GLFW.GLFW_KEY_6, new int[] {'6', '^'});
        PUNCTUATION.put(GLFW.GLFW_KEY_7, new int[] {'7', '&'});
        PUNCTUATION.put(GLFW.GLFW_KEY_8, new int[] {'8', '*'});
        PUNCTUATION.put(GLFW.GLFW_KEY_9, new int[] {'9', '('});
    }

    private Keysyms() {
    }

    public static int forGlfwKey(int glfwKey, boolean shift) {
        Integer special = SPECIAL.get(glfwKey);
        if (special != null) {
            return special;
        }
        int[] pair = PUNCTUATION.get(glfwKey);
        if (pair != null) {
            return shift ? pair[1] : pair[0];
        }
        if (glfwKey >= GLFW.GLFW_KEY_A && glfwKey <= GLFW.GLFW_KEY_Z) {
            return shift ? glfwKey : glfwKey + 32;
        }
        return 0;
    }
}
