package squarrr.virtualcomputers.lod;

public enum Rung {
    AMBIENT(16, 9),
    P144(256, 144),
    P360(640, 360),
    P720(1280, 720),
    P1080(1920, 1080);

    public static final double AMBIENT_FLOOR_PX = 2_500.0;

    private final int width;
    private final int height;

    Rung(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int pixels() {
        return width * height;
    }

    public boolean isAmbient() {
        return this == AMBIENT;
    }

    public Rung up() {
        return this == P1080 ? this : values()[ordinal() + 1];
    }

    public Rung down() {
        return this == AMBIENT ? this : values()[ordinal() - 1];
    }

    public static Rung forFootprint(double footprintPx) {
        if (footprintPx < AMBIENT_FLOOR_PX) {
            return AMBIENT;
        }
        for (Rung rung : values()) {
            if (rung != AMBIENT && rung.pixels() >= footprintPx) {
                return rung;
            }
        }
        return P1080;
    }

    @Override
    public String toString() {
        return this == AMBIENT ? "ambient 16x9" : width + "x" + height;
    }
}
