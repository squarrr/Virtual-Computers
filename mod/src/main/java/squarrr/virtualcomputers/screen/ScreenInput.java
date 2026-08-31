package squarrr.virtualcomputers.screen;

public interface ScreenInput {
    int BUTTON_LEFT = 1;
    int BUTTON_MIDDLE = 1 << 1;
    int BUTTON_RIGHT = 1 << 2;
    int WHEEL_UP = 1 << 3;
    int WHEEL_DOWN = 1 << 4;

    void keyEvent(int keysym, boolean down);

    void pointerEvent(int x, int y, int buttonMask);

    void releaseAllKeys();
}
