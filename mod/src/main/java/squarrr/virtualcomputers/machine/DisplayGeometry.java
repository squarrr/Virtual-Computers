package squarrr.virtualcomputers.machine;

import squarrr.virtualcomputers.lod.ScreenQuad;
import net.minecraft.core.Direction;
import org.joml.Vector3f;

public final class DisplayGeometry {
    private static final float U = 1.0F / 16.0F;

    private static final float LID_HINGE_Z = 9.0F * U;

    private static final float LID_ANGLE_DEG = -77.5F;

    private static final float LAPTOP_X0 = 3.5F * U;
    private static final float LAPTOP_X1 = 12.5F * U;
    private static final float LAPTOP_Z0 = 10.9375F * U;
    private static final float LAPTOP_Z1 = 16.0F * U;

    private static final float LAPTOP_SURFACE_Y = 1.0F * U + 0.001F;

    private DisplayGeometry() {
    }

    public static ScreenQuad laptopScreen(Direction facing) {
        Vector3f topLeft = place(LAPTOP_X1, LAPTOP_Z1, facing);
        Vector3f topRight = place(LAPTOP_X0, LAPTOP_Z1, facing);
        Vector3f bottomRight = place(LAPTOP_X0, LAPTOP_Z0, facing);
        Vector3f bottomLeft = place(LAPTOP_X1, LAPTOP_Z0, facing);
        return new ScreenQuad(topLeft, topRight, bottomRight, bottomLeft);
    }

    private static Vector3f place(float x, float z, Direction facing) {
        double angle = Math.toRadians(LID_ANGLE_DEG);
        double cos = Math.cos(angle), sin = Math.sin(angle);

        double localZ = z - LID_HINGE_Z;
        double y = LAPTOP_SURFACE_Y * cos - localZ * sin;
        double rotatedZ = LID_HINGE_Z + LAPTOP_SURFACE_Y * sin + localZ * cos;

        return yaw(new Vector3f(x, (float) y, (float) rotatedZ), facing);
    }

    public static Vector3f yaw(Vector3f point, Direction facing) {
        int clockwiseDegrees = Math.floorMod((int) facing.toYRot() + 180, 360);
        float dx = point.x - 0.5F;
        float dz = point.z - 0.5F;
        return switch (clockwiseDegrees) {
            case 90 -> new Vector3f(0.5F - dz, point.y, 0.5F + dx);
            case 180 -> new Vector3f(0.5F - dx, point.y, 0.5F - dz);
            case 270 -> new Vector3f(0.5F + dz, point.y, 0.5F - dx);
            default -> point;
        };
    }

    public static ScreenQuad panelScreen() {
        float z = -0.001F;
        return new ScreenQuad(
                new Vector3f(1.0F, 1.0F, z),
                new Vector3f(0.0F, 1.0F, z),
                new Vector3f(0.0F, 0.0F, z),
                new Vector3f(1.0F, 0.0F, z));
    }
}
