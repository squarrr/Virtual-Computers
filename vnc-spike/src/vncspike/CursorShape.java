package vncspike;

public final class CursorShape {
    private int width;
    private int height;
    private int hotspotX;
    private int hotspotY;

    private int[] pixels = new int[0];
    private int generation;

    public int width() { return width; }
    public int height() { return height; }
    public int hotspotX() { return hotspotX; }
    public int hotspotY() { return hotspotY; }
    public int[] pixels() { return pixels; }

    public int generation() { return generation; }

    public boolean isPresent() { return width > 0 && height > 0; }

    void set(int width, int height, int hotspotX, int hotspotY, int[] pixels) {
        this.width = width;
        this.height = height;
        this.hotspotX = hotspotX;
        this.hotspotY = hotspotY;
        this.pixels = pixels;
        this.generation++;
    }

    public int[] drawInto(int[] target, int targetWidth, int targetHeight, int x, int y) {
        if (!isPresent()) {
            return null;
        }
        int left = x - hotspotX;
        int top = y - hotspotY;
        int x0 = Math.max(0, left);
        int y0 = Math.max(0, top);
        int x1 = Math.min(targetWidth, left + width);
        int y1 = Math.min(targetHeight, top + height);
        if (x0 >= x1 || y0 >= y1) {
            return null;
        }
        for (int py = y0; py < y1; py++) {
            int sourceRow = (py - top) * width - left;
            int targetRow = py * targetWidth;
            for (int px = x0; px < x1; px++) {
                int argb = pixels[sourceRow + px];
                if ((argb & 0xFF000000) != 0) {
                    target[targetRow + px] = 0xFF000000 | argb & 0xFFFFFF;
                }
            }
        }
        return new int[] {x0, y0, x1, y1};
    }
}
