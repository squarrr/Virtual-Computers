package vncspike;

public final class Framebuffer {
    private final int width;
    private final int height;
    private final int[] pixels;

    public Framebuffer(int width, int height) {
        if (width <= 0 || height <= 0 || (long) width * height > 64_000_000L) {
            throw new IllegalArgumentException("Implausible framebuffer size " + width + "x" + height);
        }
        this.width = width;
        this.height = height;
        this.pixels = new int[width * height];
    }

    public int width() { return width; }
    public int height() { return height; }
    public int[] pixels() { return pixels; }

    public void copyRect(int srcX, int srcY, int dstX, int dstY, int w, int h) {
        if (w <= 0 || h <= 0) return;
        if (srcY < dstY) {
            for (int row = h - 1; row >= 0; row--) {
                System.arraycopy(pixels, (srcY + row) * width + srcX,
                        pixels, (dstY + row) * width + dstX, w);
            }
        } else {
            for (int row = 0; row < h; row++) {
                System.arraycopy(pixels, (srcY + row) * width + srcX,
                        pixels, (dstY + row) * width + dstX, w);
            }
        }
    }

    public long checksum() {
        long h = 1125899906842597L;
        for (int i = 0; i < pixels.length; i += 7) {
            h = h * 31 + pixels[i];
        }
        return h;
    }
}
