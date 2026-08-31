package vncspike;

public final class TestPattern {
    private static final int[] BAR_COLOURS = {
            0xFFFFFF,
            0xFFFF00,
            0x00FFFF,
            0x00FF00,
            0xFF00FF,
            0xFF0000,
            0x0000FF,
            0x000000,
    };

    private TestPattern() {}

    public static void render(int[] pixels, int width, int height, int frame) {
        int barsBottom = height * 40 / 100;
        int rampBottom = height * 55 / 100;

        for (int y = 0; y < height; y++) {
            int rowStart = y * width;
            if (y < barsBottom) {
                for (int x = 0; x < width; x++) {
                    pixels[rowStart + x] = 0xFF000000 | BAR_COLOURS[x * BAR_COLOURS.length / width];
                }
            } else if (y < rampBottom) {
                for (int x = 0; x < width; x++) {
                    int v = x * 255 / Math.max(1, width - 1);
                    pixels[rowStart + x] = 0xFF000000 | v << 16 | v << 8 | v;
                }
            } else {
                java.util.Arrays.fill(pixels, rowStart, rowStart + width, 0xFF101418);
            }
        }

        int boxSize = Math.max(16, height / 12);
        int travel = Math.max(1, width - boxSize);
        int boxX = (frame * 4) % travel;
        int boxY = rampBottom + (height - rampBottom - boxSize) / 2;
        fill(pixels, width, height, boxX, boxY, boxSize, boxSize, 0xFFFFFFFF);

        int tickWidth = Math.max(2, width / 120);
        int ticks = frame % (width / tickWidth);
        for (int t = 0; t < ticks; t++) {
            fill(pixels, width, height, t * tickWidth, height - 6, tickWidth - 1, 5, 0xFF00FF88);
        }

        int marker = Math.max(12, height / 24);
        fill(pixels, width, height, 0, 0, marker, marker, 0xFFFF0000);
    }

    private static void fill(int[] pixels, int width, int height, int x, int y, int w, int h, int argb) {
        int x0 = Math.max(0, x), y0 = Math.max(0, y);
        int x1 = Math.min(width, x + w), y1 = Math.min(height, y + h);
        for (int py = y0; py < y1; py++) {
            java.util.Arrays.fill(pixels, py * width + x0, py * width + x1, argb);
        }
    }
}
