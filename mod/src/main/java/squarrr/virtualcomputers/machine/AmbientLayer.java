package squarrr.virtualcomputers.machine;

import squarrr.virtualcomputers.screen.ScreenSource;
import squarrr.virtualcomputers.screen.ScreenTexture;

public final class AmbientLayer implements AutoCloseable {
    public static final int GRID_WIDTH = 16;
    public static final int GRID_HEIGHT = 9;

    private static final long PERIOD_MS = 500;
    private static final int UPSCALE = 4;

    private final int[] grid = new int[GRID_WIDTH * GRID_HEIGHT];
    private final int[] smooth = new int[GRID_WIDTH * UPSCALE * GRID_HEIGHT * UPSCALE];
    private final ScreenTexture texture;
    private final squarrr.virtualcomputers.screen.Resample resample = new squarrr.virtualcomputers.screen.Resample();

    private long lastRefreshMs = Long.MIN_VALUE;
    private long seenGeneration = -1;

    public AmbientLayer(String name) {
        this.texture = new ScreenTexture(name + "-ambient", GRID_WIDTH * UPSCALE, GRID_HEIGHT * UPSCALE);
    }

    public net.minecraft.resources.Identifier textureId() {
        return texture.id();
    }

    public int[] grid() {
        return grid;
    }

    public void reset() {
        lastRefreshMs = Long.MIN_VALUE;
        seenGeneration = -1;
    }

    public void refresh(ScreenSource source, long nowMs) {
        if (nowMs - lastRefreshMs < PERIOD_MS) {
            return;
        }
        long generation = source.generation();
        if (generation == seenGeneration && lastRefreshMs != Long.MIN_VALUE) {
            return;
        }
        lastRefreshMs = nowMs;
        seenGeneration = generation;

        synchronized (source.lock()) {
            int sw = source.width(), sh = source.height();
            int[] src = source.pixels();
            if ((long) sw * sh > src.length) {
                return;
            }
            if (!resample.matches(sw, sh, GRID_WIDTH, GRID_HEIGHT)) {
                resample.configure(sw, sh, GRID_WIDTH, GRID_HEIGHT);
            }
            resample.boxDownscale(src, grid);
        }
        interpolate();
        texture.upload(smooth, GRID_WIDTH * UPSCALE, GRID_HEIGHT * UPSCALE);
    }

    private void interpolate() {
        int outWidth = GRID_WIDTH * UPSCALE;
        int outHeight = GRID_HEIGHT * UPSCALE;
        for (int y = 0; y < outHeight; y++) {
            float gy = (y + 0.5F) / UPSCALE - 0.5F;
            int y0 = clamp((int) Math.floor(gy), GRID_HEIGHT);
            int y1 = clamp(y0 + 1, GRID_HEIGHT);
            float fy = gy - (float) Math.floor(gy);
            for (int x = 0; x < outWidth; x++) {
                float gx = (x + 0.5F) / UPSCALE - 0.5F;
                int x0 = clamp((int) Math.floor(gx), GRID_WIDTH);
                int x1 = clamp(x0 + 1, GRID_WIDTH);
                float fx = gx - (float) Math.floor(gx);
                smooth[y * outWidth + x] = mix(
                        mix(grid[y0 * GRID_WIDTH + x0], grid[y0 * GRID_WIDTH + x1], fx),
                        mix(grid[y1 * GRID_WIDTH + x0], grid[y1 * GRID_WIDTH + x1], fx),
                        fy);
            }
        }
    }

    private static int clamp(int value, int size) {
        return value < 0 ? 0 : Math.min(value, size - 1);
    }

    private static int mix(int a, int b, float t) {
        int r = (int) ((a >> 16 & 0xFF) + ((b >> 16 & 0xFF) - (a >> 16 & 0xFF)) * t);
        int g = (int) ((a >> 8 & 0xFF) + ((b >> 8 & 0xFF) - (a >> 8 & 0xFF)) * t);
        int bl = (int) ((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }

    @Override
    public void close() {
        texture.close();
    }
}
