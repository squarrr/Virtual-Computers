package squarrr.virtualcomputers.lod;

public final class LodState {
    private static final double CLIMB_OVERSHOOT = 1.25;
    private static final double DESCEND_UNDERSHOOT = 0.75;
    private static final long CLIMB_DWELL_MS = 500;
    private static final long DESCEND_DWELL_MS = 3_000;
    private static final long CROSSFADE_MS = 200;

    private Rung current = Rung.AMBIENT;
    private Rung candidate = Rung.AMBIENT;
    private long candidateSinceMs;
    private double lastFootprintPx;

    private float liveMix;
    private long lastUpdateMs = -1;

    public Rung update(double footprintPx, long nowMs) {
        lastFootprintPx = footprintPx;
        Rung wanted = wantedRung(footprintPx);

        if (wanted != candidate) {
            candidate = wanted;
            candidateSinceMs = nowMs;
        } else if (candidate != current) {
            long dwell = candidate.ordinal() > current.ordinal() ? CLIMB_DWELL_MS : DESCEND_DWELL_MS;
            if (nowMs - candidateSinceMs >= dwell) {
                current = candidate;
            }
        }

        advanceCrossfade(nowMs);
        return current;
    }

    private Rung wantedRung(double footprintPx) {
        Rung naive = Rung.forFootprint(footprintPx);
        if (naive == current) {
            return current;
        }
        if (naive.ordinal() > current.ordinal()) {
            double bar = current == Rung.AMBIENT ? Rung.AMBIENT_FLOOR_PX : current.pixels();
            return footprintPx >= bar * CLIMB_OVERSHOOT ? naive : current;
        }

        double bar = current == Rung.AMBIENT ? Rung.AMBIENT_FLOOR_PX : current.pixels();
        return footprintPx <= bar * DESCEND_UNDERSHOOT ? naive : current;
    }

    private void advanceCrossfade(long nowMs) {
        long deltaMs = lastUpdateMs < 0 ? 0 : Math.min(200, nowMs - lastUpdateMs);
        lastUpdateMs = nowMs;
        if (current.isAmbient()) {
            liveMix = 0.0F;
        } else {
            liveMix = Math.min(1.0F, liveMix + deltaMs / (float) CROSSFADE_MS);
        }
    }

    public float liveMix() {
        return liveMix;
    }

    public Rung rung() {
        return current;
    }

    public double lastFootprintPx() {
        return lastFootprintPx;
    }

    @Override
    public String toString() {
        return String.format("%s  %.0f px  mix %.2f", current, lastFootprintPx, liveMix);
    }
}
