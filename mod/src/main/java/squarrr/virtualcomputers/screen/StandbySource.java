package squarrr.virtualcomputers.screen;

import squarrr.virtualcomputers.machine.MachineState;

public final class StandbySource implements ScreenSource {
    private static final int WIDTH = 256;
    private static final int HEIGHT = 144;

    private final Object lock = new Object();
    private final int[] pixels = new int[WIDTH * HEIGHT];

    private MachineState state = MachineState.OFF;
    private volatile long generation;

    public StandbySource(MachineState state) {
        paint(state);
    }

    public void setState(MachineState state) {
        if (this.state != state) {
            paint(state);
        }
    }

    public MachineState state() {
        return state;
    }

    private void paint(MachineState newState) {
        synchronized (lock) {
            state = newState;
            int ground = switch (newState) {
                case FAILED -> 0xFF2A1210;
                case STARTING -> 0xFF101C2C;
                case SLEEPING -> 0xFF0C1016;
                default -> 0xFF0A0C0F;
            };
            int scan = switch (newState) {
                case FAILED -> 0xFF3A1A16;
                case STARTING -> 0xFF16283E;
                case SLEEPING -> 0xFF141A22;
                default -> 0xFF111418;
            };
            for (int y = 0; y < HEIGHT; y++) {
                int row = y * WIDTH;
                int colour = (y & 3) == 0 ? scan : ground;
                java.util.Arrays.fill(pixels, row, row + WIDTH, colour);
            }
            drawStandbyLamp(newState);
        }
        generation++;
    }

    private void drawStandbyLamp(MachineState newState) {
        int lamp = switch (newState) {
            case SLEEPING -> 0xFFE0A040;
            case STARTING -> 0xFF56C878;
            case FAILED -> 0xFFE05050;
            default -> 0xFF303840;
        };
        int centreX = WIDTH - 16;
        int centreY = HEIGHT - 12;
        for (int y = centreY - 2; y <= centreY + 2; y++) {
            for (int x = centreX - 2; x <= centreX + 2; x++) {
                int dx = x - centreX;
                int dy = y - centreY;
                if (dx * dx + dy * dy <= 4 && y >= 0 && y < HEIGHT && x >= 0 && x < WIDTH) {
                    pixels[y * WIDTH + x] = lamp;
                }
            }
        }
    }

    @Override public int width() { return WIDTH; }
    @Override public int height() { return HEIGHT; }
    @Override public long generation() { return generation; }
    @Override public int[] pixels() { return pixels; }
    @Override public Object lock() { return lock; }
    @Override public String status() { return state.label().toLowerCase(java.util.Locale.ROOT); }

    @Override
    public void close() {
    }
}
