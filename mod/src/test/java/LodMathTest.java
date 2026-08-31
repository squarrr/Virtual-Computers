import squarrr.virtualcomputers.lod.LodState;
import squarrr.virtualcomputers.lod.Rung;
import squarrr.virtualcomputers.lod.ScreenQuad;
import squarrr.virtualcomputers.screen.Resample;
import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public final class LodMathTest {
    private static final List<String> FAILURES = new ArrayList<>();
    private static int checks;

    public static void main(String[] args) {
        ladderThresholdsMatchThePlan();
        ladderPicksTheSmallestSufficientRung();
        hysteresisDoesNotOscillateOnABoundary();
        climbIsEagerAndDescentIsLazy();
        footprintReproducesThePlansWorkedExample();
        zoomLandsOnTheSameRungAsStandingClose();
        offScreenAndBehindAreBothZero();
        pointerRoundTripsThroughTheQuad();
        cornersRunClockwiseAsTheReaderSeesThem();
        resampleMatchesTheObviousImplementation();
        aspectFitAddsBarsRatherThanStretching();
        liveLayerSitsInFrontOfAmbient();
        pointerMeasuresAgainstThePictureNotThePanel();

        System.out.println();
        if (FAILURES.isEmpty()) {
            System.out.println("PASS - " + checks + " checks");
            return;
        }
        System.out.println("FAIL");
        FAILURES.forEach(f -> System.out.println("  - " + f));
        System.exit(1);
    }

    private static void ladderThresholdsMatchThePlan() {
        section("Ladder rungs are their own thresholds");
        expect(Rung.P144.pixels() == 36_864, "256x144 is 36,864 px");
        expect(Rung.P360.pixels() == 230_400, "640x360 is 230,400 px");
        expect(Rung.P720.pixels() == 921_600, "1280x720 is 921,600 px");
        expect(Rung.P1080.pixels() == 2_073_600, "1920x1080 is 2,073,600 px");
        expect(Rung.AMBIENT.pixels() == 144, "the ambient grid is 16x9 = 144 px");
    }

    private static void ladderPicksTheSmallestSufficientRung() {
        section("Smallest rung whose pixel count covers the footprint");
        expect(Rung.forFootprint(0) == Rung.AMBIENT, "nothing visible -> ambient");
        expect(Rung.forFootprint(2_499) == Rung.AMBIENT, "just under the floor -> ambient");
        expect(Rung.forFootprint(2_500) == Rung.P144, "at the floor -> the smallest real rung");
        expect(Rung.forFootprint(36_864) == Rung.P144, "exactly 144p's pixel count -> 144p");
        expect(Rung.forFootprint(36_865) == Rung.P360, "one px more -> up a rung");
        expect(Rung.forFootprint(230_400) == Rung.P360, "exactly 360p -> 360p");
        expect(Rung.forFootprint(921_600) == Rung.P720, "exactly 720p -> 720p");
        expect(Rung.forFootprint(921_601) == Rung.P1080, "past 720p -> 1080p");
        expect(Rung.forFootprint(50_000_000) == Rung.P1080, "absurdly large -> capped at 1080p");
    }

    private static void hysteresisDoesNotOscillateOnABoundary() {
        section("A footprint sitting on a threshold does not chatter");
        LodState ladder = new LodState();
        long now = 0;

        for (int i = 0; i < 40; i++) {
            ladder.update(150_000, now += 100);
        }
        expect(ladder.rung() == Rung.P360, "settles at 640x360");

        Rung start = ladder.rung();
        int changes = 0;
        for (int i = 0; i < 200; i++) {
            double jitter = 230_400 + (i % 2 == 0 ? 400 : -400);
            Rung before = ladder.rung();
            ladder.update(jitter, now += 50);
            if (ladder.rung() != before) {
                changes++;
            }
        }
        expect(changes == 0, "ten seconds of jitter across the boundary caused "
                + changes + " renegotiations, wanted 0");
        expect(ladder.rung() == start, "stayed on the rung it started on");
    }

    private static void climbIsEagerAndDescentIsLazy() {
        section("Climb after ~500 ms, descend after ~3 s");
        LodState ladder = new LodState();
        long now = 0;
        for (int i = 0; i < 40; i++) {
            ladder.update(150_000, now += 100);
        }
        expect(ladder.rung() == Rung.P360, "starting at 640x360");

        long climbStart = now;
        while (ladder.rung() == Rung.P360 && now - climbStart < 5_000) {
            ladder.update(500_000, now += 50);
        }
        long climbMs = now - climbStart;
        expect(ladder.rung() == Rung.P720, "climbed to 1280x720");
        expect(climbMs >= 450 && climbMs <= 750, "climb took " + climbMs + " ms, wanted about 500");

        long descendStart = now;
        while (ladder.rung() == Rung.P720 && now - descendStart < 10_000) {
            ladder.update(50_000, now += 50);
        }
        long descendMs = now - descendStart;
        expect(ladder.rung() != Rung.P720, "descended off 720p");
        expect(descendMs >= 2_900 && descendMs <= 3_300, "descent took " + descendMs
                + " ms, wanted about 3000 - being briefly too sharp is cheaper than being blurry");
        expect(descendMs > climbMs * 3, "descent is markedly lazier than the climb");
    }

    private static ScreenQuad television() {
        float halfWidth = 2.5F, halfHeight = 2.8125F / 2.0F;
        float z = 0.0F;
        return new ScreenQuad(
                new Vector3f(halfWidth, halfHeight, z),
                new Vector3f(-halfWidth, halfHeight, z),
                new Vector3f(-halfWidth, -halfHeight, z),
                new Vector3f(halfWidth, -halfHeight, z));
    }

    private static double footprintAt(double distance, float fovDegrees) {
        int width = 1920, height = 1080;
        Matrix4f projection = new Matrix4f().perspective(
                (float) Math.toRadians(fovDegrees), width / (float) height, 0.05F, 1000.0F);

        return television().footprintPixels(
                new Vector3f(0.0F, 0.0F, (float) -distance), projection, new Vector3f(), width, height);
    }

    private static void footprintReproducesThePlansWorkedExample() {
        section("Worked example: a television, viewer at 1080p, 70 degree FOV");
        check(footprintAt(4, 70), 523_000, Rung.P720, "4 blocks");
        check(footprintAt(8, 70), 131_000, Rung.P360, "8 blocks");
        check(footprintAt(30, 70), 9_300, Rung.P144, "30 blocks");
        check(footprintAt(70, 70), 1_700, Rung.AMBIENT, "70 blocks");
    }

    private static void zoomLandsOnTheSameRungAsStandingClose() {
        section("A zoom mod is not a special case");
        double zoomed = footprintAt(30, 14);
        double close = footprintAt(4, 70);
        check(zoomed, 302_000, Rung.P720, "30 blocks with 5x zoom (14 degree FOV)");
        expect(Rung.forFootprint(zoomed) == Rung.forFootprint(close),
                "a zoomed viewer 30 blocks back gets the same rung as one standing at 4 blocks");
    }

    private static void check(double measured, double expected, Rung expectedRung, String label) {
        double error = Math.abs(measured - expected) / expected;
        expect(error < 0.06, String.format(
                "%s: measured %,.0f px, the plan says %,.0f (%.1f%% apart)",
                label, measured, expected, error * 100));
        expect(Rung.forFootprint(measured) == expectedRung, String.format(
                "%s: %,.0f px selects %s, the plan says %s",
                label, measured, Rung.forFootprint(measured), expectedRung));
    }

    private static void offScreenAndBehindAreBothZero() {
        section("Behind the camera and off to the side both measure zero");
        int width = 1920, height = 1080;
        Matrix4f projection = new Matrix4f().perspective(
                (float) Math.toRadians(70.0), width / (float) height, 0.05F, 1000.0F);

        double behind = television().footprintPixels(
                new Vector3f(0, 0, 8), projection, new Vector3f(), width, height);
        expect(behind == 0.0, "a screen behind the viewer measures 0, not a mirrored area");

        double aside = television().footprintPixels(
                new Vector3f(400, 0, -10), projection, new Vector3f(), width, height);
        expect(aside == 0.0, "a screen far off to the side measures 0");

        double filling = television().footprintPixels(
                new Vector3f(0, 0, -0.6F), projection, new Vector3f(), width, height);
        expect(filling > width * (double) height * 0.5,
                "a screen larger than the viewport still measures, rather than falling to 0");
    }

    private static void pointerRoundTripsThroughTheQuad() {
        section("Pointing at a known spot on a screen returns that spot");
        ScreenQuad quad = television();
        Vector3f origin = new Vector3f(0.0F, 0.0F, -8.0F);
        Vector3f eye = new Vector3f(1.3F, 0.7F, 4.0F);

        float[][] probes = {{0.02F, 0.02F}, {0.5F, 0.5F}, {0.97F, 0.11F}, {0.25F, 0.8F}};
        for (float[] probe : probes) {
            Vector3f target = quad.pointAt(origin, probe[0], probe[1]);
            Vector3f direction = new Vector3f(target).sub(eye).normalize();
            float[] hit = quad.rayHitUv(origin, eye, direction);
            if (hit == null) {
                FAILURES.add(String.format("uv (%.2f, %.2f) did not hit the quad at all", probe[0], probe[1]));
                checks++;
                continue;
            }
            expect(Math.abs(hit[0] - probe[0]) < 1e-3 && Math.abs(hit[1] - probe[1]) < 1e-3,
                    String.format("uv (%.2f, %.2f) -> (%.4f, %.4f)", probe[0], probe[1], hit[0], hit[1]));
        }

        Vector3f away = new Vector3f(0.0F, 0.0F, 1.0F);
        expect(quad.rayHitUv(origin, eye, away) == null, "a ray pointing away from the screen misses");

        float[] onPicture = quad.uvOfPoint(origin, quad.pointAt(origin, 0.6F, 0.3F));
        expect(onPicture != null
                && Math.abs(onPicture[0] - 0.6F) < 1e-3 && Math.abs(onPicture[1] - 0.3F) < 1e-3,
                "a point on the picture reports its own uv");
        Vector3f pastTheEdge = quad.pointAt(origin, 0.5F, 0.5F).add(9.0F, 0.0F, 0.0F);
        expect(quad.uvOfPoint(origin, pastTheEdge) == null,
                "a point on the bezel rather than the picture reports nothing, not a clamped edge");
    }

    private static void cornersRunClockwiseAsTheReaderSeesThem() {
        section("Corner order fixes which side of a screen is the front");

        ScreenQuad panel = new ScreenQuad(
                new Vector3f(1.0F, 1.0F, 0.0F),
                new Vector3f(0.0F, 1.0F, 0.0F),
                new Vector3f(0.0F, 0.0F, 0.0F),
                new Vector3f(1.0F, 0.0F, 0.0F));
        Vector3f normal = panel.frontNormal();
        expect(Math.abs(normal.x) < 1e-4 && Math.abs(normal.y) < 1e-4 && Math.abs(normal.z + 1.0F) < 1e-4,
                String.format("a north-facing panel's normal is (0,0,-1), got (%.3f, %.3f, %.3f)",
                        normal.x, normal.y, normal.z));

        double angle = Math.toRadians(-77.5);
        float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
        ScreenQuad lid = new ScreenQuad(
                lidCorner(0.78F, 1.0F, cos, sin),
                lidCorner(0.22F, 1.0F, cos, sin),
                lidCorner(0.22F, 0.0F, cos, sin),
                lidCorner(0.78F, 0.0F, cos, sin));
        Vector3f lidNormal = lid.frontNormal();
        expect(Math.abs(lidNormal.y - 0.2164F) < 1e-3 && Math.abs(lidNormal.z + 0.9763F) < 1e-3,
                String.format("the lid's normal is (0, 0.216, -0.976), got (%.3f, %.3f, %.3f)",
                        lidNormal.x, lidNormal.y, lidNormal.z));
        expect(lidNormal.y > 0.0F && lidNormal.z < 0.0F,
                "the lid faces north and slightly upward, which is what an open laptop does");

        Vector3f acrossTheTop = new Vector3f(panel.topRight()).sub(panel.topLeft());
        Vector3f downTheSide = new Vector3f(panel.bottomLeft()).sub(panel.topLeft());
        Vector3f handedness = new Vector3f(acrossTheTop).cross(downTheSide);
        expect(handedness.dot(panel.frontNormal()) < 0.0F,
                "corners 0..3 wind clockwise from the front, so a front face emits them 3,2,1,0");
    }

    private static Vector3f lidCorner(float x, float alongLid, float cos, float sin) {
        float y = 0.0625F, z = alongLid;
        return new Vector3f(x, y * cos - z * sin, y * sin + z * cos);
    }

    private static void resampleMatchesTheObviousImplementation() {
        section("Fast box downscale equals the obvious one, pixel for pixel");
        int sourceWidth = 1024, sourceHeight = 768;
        int[] source = new int[sourceWidth * sourceHeight];
        java.util.Random random = new java.util.Random(20260830);
        for (int i = 0; i < source.length; i++) {
            source[i] = 0xFF000000 | random.nextInt(0xFFFFFF);
        }

        int[][] sizes = {{554, 415}, {221, 166}, {16, 9}, {1, 1}, {1023, 767}};
        for (int[] size : sizes) {
            int w = size[0], h = size[1];
            int[] expected = new int[w * h];
            naiveBoxDownscale(source, sourceWidth, sourceHeight, expected, w, h);

            Resample resample = new Resample();
            resample.configure(sourceWidth, sourceHeight, w, h);
            int[] actual = new int[w * h];
            resample.boxDownscale(source, actual);

            int differing = 0;
            for (int i = 0; i < expected.length; i++) {
                if (expected[i] != actual[i]) {
                    differing++;
                }
            }
            expect(differing == 0, String.format("%dx%d -> %dx%d: %d of %d pixels differ",
                    sourceWidth, sourceHeight, w, h, differing, expected.length));
        }
    }

    private static void naiveBoxDownscale(int[] src, int sw, int sh, int[] dst, int w, int h) {
        for (int y = 0; y < h; y++) {
            int y0 = y * sh / h;
            int y1 = Math.max(y0 + 1, (y + 1) * sh / h);
            for (int x = 0; x < w; x++) {
                int x0 = x * sw / w;
                int x1 = Math.max(x0 + 1, (x + 1) * sw / w);
                long r = 0, g = 0, b = 0;
                int count = 0;
                for (int sy = y0; sy < y1; sy++) {
                    for (int sx = x0; sx < x1; sx++) {
                        int argb = src[sy * sw + sx];
                        r += argb >> 16 & 0xFF;
                        g += argb >> 8 & 0xFF;
                        b += argb & 0xFF;
                        count++;
                    }
                }
                dst[y * w + x] = count == 0 ? 0xFF000000
                        : 0xFF000000 | (int) (r / count) << 16 | (int) (g / count) << 8 | (int) (b / count);
            }
        }
    }

    private static void aspectFitAddsBarsRatherThanStretching() {
        section("A guest keeps its own aspect inside the picture");

        ScreenQuad panel = new ScreenQuad(
                new Vector3f(9.0F, 5.0625F, 0.0F),
                new Vector3f(0.0F, 5.0625F, 0.0F),
                new Vector3f(0.0F, 0.0F, 0.0F),
                new Vector3f(9.0F, 0.0F, 0.0F));
        expect(Math.abs(panel.aspect() - 16.0F / 9.0F) < 1e-4,
                String.format("the panel is 16:9 (%.4f)", panel.aspect()));

        ScreenQuad fitted = panel.fitPreservingAspect(1024, 768);
        float fittedWidth = new Vector3f(fitted.topRight()).sub(fitted.topLeft()).length();
        float fittedHeight = new Vector3f(fitted.bottomLeft()).sub(fitted.topLeft()).length();
        expect(Math.abs(fitted.aspect() - 4.0F / 3.0F) < 1e-3,
                String.format("a 1024x768 guest is drawn at 4:3, got %.4f", fitted.aspect()));
        expect(Math.abs(fittedHeight - 5.0625F) < 1e-3,
                String.format("full height is used: %.4f of 5.0625", fittedHeight));
        expect(fittedWidth < 9.0F - 0.5F,
                String.format("width is inset for pillarbox: %.4f of 9.0", fittedWidth));

        float leftBar = panel.topLeft().x - fitted.topLeft().x;
        float rightBar = fitted.topRight().x - panel.topRight().x;
        expect(Math.abs(leftBar - rightBar) < 1e-3,
                String.format("bars are equal: %.4f and %.4f of 9.0", leftBar, rightBar));

        ScreenQuad exact = panel.fitPreservingAspect(1920, 1080);
        expect(exact == panel, "a 16:9 guest gets the whole picture, untouched");

        ScreenQuad ultrawide = panel.fitPreservingAspect(2560, 1080);
        float ultrawideHeight = new Vector3f(ultrawide.bottomLeft()).sub(ultrawide.topLeft()).length();
        expect(ultrawideHeight < 5.0625F - 0.5F,
                String.format("an ultrawide guest letterboxes: height %.4f of 5.0625", ultrawideHeight));
    }

    private static void liveLayerSitsInFrontOfAmbient() {
        section("The live layer is lifted clear of the ambient one");

        ScreenQuad panel = new ScreenQuad(
                new Vector3f(1, 1, 0), new Vector3f(0, 1, 0),
                new Vector3f(0, 0, 0), new Vector3f(1, 0, 0));
        float lift = 0.0005F;
        ScreenQuad live = panel.fitPreservingAspect(1024, 768).raisedBy(lift);

        Vector3f normal = panel.frontNormal();
        expect(Math.abs(normal.z + 1.0F) < 1.0E-5F,
                String.format("the panel faces the viewer, normal z = %.3f", normal.z));

        float minShift = Float.MAX_VALUE;
        float maxShift = -Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            float shift = -live.corner(i).z;
            minShift = Math.min(minShift, shift);
            maxShift = Math.max(maxShift, shift);
        }
        expect(Math.abs(minShift - lift) < 1.0E-6F && Math.abs(maxShift - lift) < 1.0E-6F,
                String.format("all four corners moved by exactly the lift (%.5f to %.5f)",
                        minShift, maxShift));
        expect(maxShift > 0.0F, "the movement is toward the viewer, not away");

        expect(lift < 1.0F / 16.0F / 10.0F,
                "the lift is far below one model pixel, so it cannot be seen edge-on");

        ScreenQuad unlifted = panel.fitPreservingAspect(1024, 768);
        expect(Math.abs(live.aspect() - unlifted.aspect()) < 1.0E-6F,
                String.format("lifting does not change the aspect (%.4f)", live.aspect()));
    }

    private static void pointerMeasuresAgainstThePictureNotThePanel() {
        section("A click lands on the guest pixel drawn under it");

        ScreenQuad panel = new ScreenQuad(
                new Vector3f(1.7778F, 1, 0), new Vector3f(0, 1, 0),
                new Vector3f(0, 0, 0), new Vector3f(1.7778F, 0, 0));
        int guestWidth = 1024;
        int guestHeight = 768;
        ScreenQuad picture = panel.fitPreservingAspect(guestWidth, guestHeight);
        Vector3f origin = new Vector3f(0, 0, 0);

        expect(picture.aspect() < panel.aspect(),
                String.format("a 4:3 guest is inset into a 16:9 panel (%.3f inside %.3f)",
                        picture.aspect(), panel.aspect()));

        float worstPixels = 0;
        for (float u : new float[] {0.0F, 0.25F, 0.5F, 0.75F, 1.0F}) {
            Vector3f world = picture.pointAt(origin, u, 0.5F);
            float[] uv = picture.uvOfPoint(origin, world);
            expect(uv != null, String.format("a point at u=%.2f of the picture is on the picture", u));
            if (uv != null) {
                worstPixels = Math.max(worstPixels, Math.abs(uv[0] - u) * (guestWidth - 1));
            }
        }
        expect(worstPixels < 1.0F,
                String.format("measuring against the picture round-trips within %.2f px", worstPixels));

        float[] wrong = panel.uvOfPoint(origin, picture.pointAt(origin, 1.0F, 0.5F));
        expect(wrong != null, "the picture's right edge is somewhere on the panel");
        if (wrong != null) {
            float driftPx = Math.abs(wrong[0] - 1.0F) * (guestWidth - 1);
            expect(driftPx > 100.0F, String.format(
                    "measuring against the panel instead would be %.0f px out - the bug this catches",
                    driftPx));
        }

        float[] onBar = picture.uvOfPoint(origin, panel.pointAt(origin, 0.02F, 0.5F));
        expect(onBar == null, "a click on the bar beside the picture reports nothing, not an edge");
    }

    private static void section(String title) {
        System.out.println();
        System.out.println(title);
    }

    private static void expect(boolean condition, String what) {
        checks++;
        System.out.println((condition ? "  ok   " : "  FAIL ") + what);
        if (!condition) {
            FAILURES.add(what);
        }
    }
}
