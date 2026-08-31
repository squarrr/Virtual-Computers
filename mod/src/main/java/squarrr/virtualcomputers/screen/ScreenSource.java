package squarrr.virtualcomputers.screen;

public interface ScreenSource extends AutoCloseable {
    int width();

    int height();

    long generation();

    int[] pixels();

    Object lock();

    String status();

    @Override
    void close();
}
