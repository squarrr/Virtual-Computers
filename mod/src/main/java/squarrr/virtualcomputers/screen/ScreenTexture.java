package squarrr.virtualcomputers.screen;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;
import squarrr.virtualcomputers.VirtualComputers;

public final class ScreenTexture implements AutoCloseable {
    private final Identifier id;
    private final DynamicTexture texture;
    private final int width;
    private final int height;

    private long uploadedGeneration = -1;
    private double lastUploadMillis;

    public ScreenTexture(String name, int width, int height) {
        this.width = width;
        this.height = height;
        this.id = Identifier.fromNamespaceAndPath(VirtualComputers.MODID, "screen/" + name);
        this.texture = new DynamicTexture(() -> "vc screen " + name, width, height, false);
        Minecraft.getInstance().getTextureManager().register(id, texture);
    }

    public Identifier id() { return id; }

    public int width() { return width; }

    public int height() { return height; }

    public double lastUploadMillis() { return lastUploadMillis; }

    public NativeImage image() { return texture.getPixels(); }

    public boolean syncFrom(ScreenSource source) {
        long generation = source.generation();
        if (generation == uploadedGeneration) {
            return false;
        }
        long base = mappedBase();
        if (base == 0L) {
            return false;
        }

        long start = System.nanoTime();
        synchronized (source.lock()) {
            int sourceWidth = source.width();
            int sourceHeight = source.height();
            int[] src = source.pixels();
            if ((long) sourceWidth * sourceHeight > src.length) {
                return false;
            }
            writeInto(base, src, sourceWidth, sourceHeight);
        }
        texture.upload();
        uploadedGeneration = generation;
        lastUploadMillis = (System.nanoTime() - start) / 1e6;
        return true;
    }

    public void upload(int[] argb, int sourceWidth, int sourceHeight) {
        long base = mappedBase();
        if (base == 0L || (long) sourceWidth * sourceHeight > argb.length) {
            return;
        }
        writeInto(base, argb, sourceWidth, sourceHeight);
        texture.upload();
        uploadedGeneration++;
    }

    private long mappedBase() {
        NativeImage image = texture.getPixels();
        if (image == null || image.format() != NativeImage.Format.RGBA) {
            return 0L;
        }
        return image.getPointer();
    }

    private void writeInto(long base, int[] src, int sourceWidth, int sourceHeight) {
        if (sourceWidth == width && sourceHeight == height) {
            for (int i = 0, n = width * height; i < n; i++) {
                MemoryUtil.memPutInt(base + ((long) i << 2), swizzle(src[i]));
            }
            return;
        }
        if (!resample.matches(sourceWidth, sourceHeight, width, height)) {
            resample.configure(sourceWidth, sourceHeight, width, height);
            staging = new int[width * height];
        }
        resample.boxDownscale(src, staging);
        for (int i = 0, n = width * height; i < n; i++) {
            MemoryUtil.memPutInt(base + ((long) i << 2), swizzle(staging[i]));
        }
    }

    private final Resample resample = new Resample();
    private int[] staging = new int[0];

    private static int swizzle(int argb) {
        return argb & 0xFF00FF00 | (argb & 0xFF0000) >> 16 | (argb & 0xFF) << 16;
    }

    public long[] benchmark(ScreenSource source, int iterations) {
        long[] mappedNanos = new long[iterations];
        long[] setPixelNanos = new long[iterations];
        long[] uploadNanos = new long[iterations];
        NativeImage image = texture.getPixels();
        long base = image.getPointer();
        int n = width * height;

        for (int it = 0; it < iterations; it++) {
            synchronized (source.lock()) {
                int[] src = source.pixels();

                long t0 = System.nanoTime();
                for (int i = 0; i < n; i++) {
                    MemoryUtil.memPutInt(base + ((long) i << 2), swizzle(src[i]));
                }
                long t1 = System.nanoTime();

                for (int y = 0; y < height; y++) {
                    int row = y * width;
                    for (int x = 0; x < width; x++) {
                        image.setPixel(x, y, src[row + x]);
                    }
                }
                long t2 = System.nanoTime();

                mappedNanos[it] = t1 - t0;
                setPixelNanos[it] = t2 - t1;
            }
            long t3 = System.nanoTime();
            texture.upload();
            uploadNanos[it] = System.nanoTime() - t3;
        }
        java.util.Arrays.sort(mappedNanos);
        java.util.Arrays.sort(setPixelNanos);
        java.util.Arrays.sort(uploadNanos);
        return new long[] {
                mappedNanos[iterations / 2], setPixelNanos[iterations / 2], uploadNanos[iterations / 2] };
    }

    @Override
    public void close() {
        Minecraft.getInstance().getTextureManager().release(id);
    }
}
