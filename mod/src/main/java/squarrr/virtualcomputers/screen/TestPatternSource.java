package squarrr.virtualcomputers.screen;

import vncspike.TestPattern;

public final class TestPatternSource implements ScreenSource {
    private final int width;
    private final int height;
    private final int[] pixels;
    private final Object lock = new Object();
    private final Thread thread;

    private volatile long generation;
    private volatile boolean running = true;

    public TestPatternSource(int width, int height, int fps) {
        this.width = width;
        this.height = height;
        this.pixels = new int[width * height];
        long periodNanos = 1_000_000_000L / Math.max(1, fps);

        this.thread = new Thread(() -> {
            int frame = 0;
            while (running) {
                synchronized (lock) {
                    TestPattern.render(pixels, width, height, frame++);
                }
                generation++;

                try {
                    Thread.sleep(periodNanos / 1_000_000L, (int) (periodNanos % 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "vc-test-pattern");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    @Override public int width() { return width; }
    @Override public int height() { return height; }
    @Override public long generation() { return generation; }
    @Override public int[] pixels() { return pixels; }
    @Override public Object lock() { return lock; }
    @Override public String status() { return "test pattern"; }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
    }
}
