package squarrr.virtualcomputers.machine;

import squarrr.virtualcomputers.lod.Rung;

public record PanelSpec(int width, int height, int refreshHz, Rung ceiling) {
    public static final PanelSpec LAPTOP = new PanelSpec(1920, 1080, 60, Rung.P1080);

    public static final PanelSpec PANEL = new PanelSpec(1920, 1080, 60, Rung.P1080);

    public float aspect() {
        return width / (float) height;
    }

    @Override
    public String toString() {
        return width + "x" + height + "@" + refreshHz;
    }
}
