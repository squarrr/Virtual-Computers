package vncspike;

public final class InputEcho {
    private static final int[] GLYPHS = {
            0b111101101101111,
            0b010110010010111,
            0b111001111100111,
            0b111001111001111,
            0b101101111001001,
            0b111100111001111,
            0b111100111101111,
            0b111001001001001,
            0b111101111101111,
            0b111101111001111,
            0b111101111101101,
            0b110101110101110,
            0b111100100100111,
            0b110101101101110,
            0b111100111100111,
            0b111100111100100,
    };

    private static final int SCALE = 3;
    private static final int GLYPH_WIDTH = 3 * SCALE;
    private static final int GLYPH_HEIGHT = 5 * SCALE;
    private static final int GLYPH_GAP = SCALE;

    private volatile int pointerX = -1;
    private volatile int pointerY = -1;
    private volatile int buttonMask;
    private volatile int lastKeysym = -1;
    private volatile boolean keyDown;
    private volatile int keyCount;

    public void onPointer(int x, int y, int mask) {
        pointerX = x;
        pointerY = y;
        buttonMask = mask;
    }

    public void onKey(int keysym, boolean down) {
        lastKeysym = keysym;
        keyDown = down;
        if (down) {
            keyCount++;
        }
    }

    public boolean hasInput() {
        return pointerX >= 0 || lastKeysym >= 0;
    }

    public void draw(int[] pixels, int width, int height) {
        if (!hasInput()) {
            return;
        }
        int panelY = height * 62 / 100;
        drawKeyReadout(pixels, width, height, width * 6 / 100, panelY);
        drawButtonLamps(pixels, width, height, width * 6 / 100, panelY + GLYPH_HEIGHT + 6 * SCALE);
        drawCrosshair(pixels, width, height);
    }

    private void drawKeyReadout(int[] pixels, int width, int height, int x, int y) {
        if (lastKeysym < 0) {
            return;
        }

        int colour = keyDown ? 0xFF6BE675 : 0xFF4A6E50;
        for (int digit = 0; digit < 4; digit++) {
            int nibble = lastKeysym >> ((3 - digit) * 4) & 0xF;
            drawGlyph(pixels, width, height, x + digit * (GLYPH_WIDTH + GLYPH_GAP), y, nibble, colour);
        }

        int tallyX = x + 4 * (GLYPH_WIDTH + GLYPH_GAP) + GLYPH_WIDTH;
        for (int i = 0; i < Math.min(16, keyCount); i++) {
            fill(pixels, width, height, tallyX + i * (SCALE + 1), y + GLYPH_HEIGHT - SCALE, SCALE, SCALE, 0xFF3D6E9E);
        }
    }

    private void drawButtonLamps(int[] pixels, int width, int height, int x, int y) {
        int size = 4 * SCALE;
        int[] bits = {1, 1 << 1, 1 << 2};
        for (int i = 0; i < bits.length; i++) {
            boolean held = (buttonMask & bits[i]) != 0;
            fill(pixels, width, height, x + i * (size + SCALE), y, size, size,
                    held ? 0xFFF0A244 : 0xFF303840);
        }
    }

    private void drawCrosshair(int[] pixels, int width, int height) {
        if (pointerX < 0) {
            return;
        }
        int cx = Math.max(0, Math.min(width - 1, pointerX));
        int cy = Math.max(0, Math.min(height - 1, pointerY));
        int arm = 10;
        int colour = buttonMask != 0 ? 0xFFF0A244 : 0xFFFFFFFF;

        for (int d = -arm; d <= arm; d++) {
            if (Math.abs(d) > 2) {
                plot(pixels, width, height, cx + d, cy, colour);
                plot(pixels, width, height, cx, cy + d, colour);
            }
        }

        for (int d = -arm; d <= arm; d++) {
            if (Math.abs(d) > 2) {
                plot(pixels, width, height, cx + d, cy - 1, 0xFF101010);
                plot(pixels, width, height, cx + d, cy + 1, 0xFF101010);
                plot(pixels, width, height, cx - 1, cy + d, 0xFF101010);
                plot(pixels, width, height, cx + 1, cy + d, 0xFF101010);
            }
        }
    }

    private static void drawGlyph(int[] pixels, int width, int height, int x, int y, int nibble, int colour) {
        int bits = GLYPHS[nibble];
        for (int row = 0; row < 5; row++) {
            for (int column = 0; column < 3; column++) {
                if ((bits >> (14 - row * 3 - column) & 1) != 0) {
                    fill(pixels, width, height, x + column * SCALE, y + row * SCALE, SCALE, SCALE, colour);
                }
            }
        }
    }

    private static void fill(int[] pixels, int width, int height, int x, int y, int w, int h, int colour) {
        for (int dy = 0; dy < h; dy++) {
            for (int dx = 0; dx < w; dx++) {
                plot(pixels, width, height, x + dx, y + dy, colour);
            }
        }
    }

    private static void plot(int[] pixels, int width, int height, int x, int y, int colour) {
        if (x >= 0 && y >= 0 && x < width && y < height) {
            pixels[y * width + x] = colour;
        }
    }
}
