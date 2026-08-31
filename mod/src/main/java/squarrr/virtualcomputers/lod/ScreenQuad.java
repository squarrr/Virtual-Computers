package squarrr.virtualcomputers.lod;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

public record ScreenQuad(Vector3f topLeft, Vector3f topRight, Vector3f bottomRight, Vector3f bottomLeft) {
    public static final float[][] UV = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};

    public Vector3f corner(int index) {
        return switch (index) {
            case 0 -> topLeft;
            case 1 -> topRight;
            case 2 -> bottomRight;
            default -> bottomLeft;
        };
    }

    public Vector3f frontNormal() {
        Vector3f edgeU = new Vector3f(topRight).sub(topLeft);
        Vector3f edgeV = new Vector3f(bottomLeft).sub(topLeft);
        Vector3f normal = edgeU.cross(edgeV).negate();
        return normal.lengthSquared() < 1.0E-12F ? new Vector3f(0.0F, 0.0F, -1.0F) : normal.normalize();
    }

    public float aspect() {
        float w = new Vector3f(topRight).sub(topLeft).length();
        float h = new Vector3f(bottomLeft).sub(topLeft).length();
        return h < 1.0E-6F ? 1.0F : w / h;
    }

    public ScreenQuad fitPreservingAspect(int sourceWidth, int sourceHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return this;
        }
        float sourceAspect = sourceWidth / (float) sourceHeight;
        float ratio = sourceAspect / aspect();
        float keepU = ratio < 1.0F ? ratio : 1.0F;
        float keepV = ratio > 1.0F ? 1.0F / ratio : 1.0F;
        if (keepU > 0.999F && keepV > 0.999F) {
            return this;
        }
        float u0 = (1.0F - keepU) / 2.0F, v0 = (1.0F - keepV) / 2.0F;
        return subRect(u0, v0, 1.0F - u0, 1.0F - v0);
    }

    public ScreenQuad raisedBy(float distance) {
        Vector3f lift = frontNormal().mul(distance);
        return new ScreenQuad(
                new Vector3f(topLeft).add(lift), new Vector3f(topRight).add(lift),
                new Vector3f(bottomRight).add(lift), new Vector3f(bottomLeft).add(lift));
    }

    public ScreenQuad subRect(float u0, float v0, float u1, float v1) {
        return new ScreenQuad(local(u0, v0), local(u1, v0), local(u1, v1), local(u0, v1));
    }

    private Vector3f local(float u, float v) {
        Vector3f top = new Vector3f(topLeft).lerp(topRight, u);
        Vector3f bottom = new Vector3f(bottomLeft).lerp(bottomRight, u);
        return top.lerp(bottom, v);
    }

    public Vector3f pointAt(Vector3f blockOrigin, float u, float v) {
        Vector3f top = new Vector3f(topLeft).lerp(topRight, u);
        Vector3f bottom = new Vector3f(bottomLeft).lerp(bottomRight, u);
        return top.lerp(bottom, v).add(blockOrigin);
    }

    public double footprintPixels(Vector3f blockOrigin, Matrix4f viewProjection, Vector3f cameraPosition,
                                  int framebufferWidth, int framebufferHeight) {
        double[] sx = new double[4];
        double[] sy = new double[4];
        boolean anyOnScreen = false;

        for (int i = 0; i < 4; i++) {
            Vector3f world = new Vector3f(corner(i)).add(blockOrigin).sub(cameraPosition);
            Vector4f clip = new Vector4f(world.x, world.y, world.z, 1.0F);
            viewProjection.transform(clip);

            if (clip.w <= 1.0E-4F) {
                return 0.0;
            }
            double ndcX = clip.x / clip.w;
            double ndcY = clip.y / clip.w;
            sx[i] = (ndcX * 0.5 + 0.5) * framebufferWidth;
            sy[i] = (0.5 - ndcY * 0.5) * framebufferHeight;

            if (ndcX >= -1.0 && ndcX <= 1.0 && ndcY >= -1.0 && ndcY <= 1.0) {
                anyOnScreen = true;
            }
        }

        if (!anyOnScreen && !containsViewportCentre(sx, sy, framebufferWidth * 0.5, framebufferHeight * 0.5)) {
            return 0.0;
        }

        double twiceArea = 0.0;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) & 3;
            twiceArea += sx[i] * sy[j] - sx[j] * sy[i];
        }
        return Math.abs(twiceArea) * 0.5;
    }

    private static boolean containsViewportCentre(double[] sx, double[] sy, double px, double py) {
        boolean inside = false;
        for (int i = 0, j = 3; i < 4; j = i++) {
            if ((sy[i] > py) != (sy[j] > py)
                    && px < (sx[j] - sx[i]) * (py - sy[i]) / (sy[j] - sy[i]) + sx[i]) {
                inside = !inside;
            }
        }
        return inside;
    }

    public float[] rayHitUv(Vector3f blockOrigin, Vector3f origin, Vector3f direction) {
        Vector3f anchor = new Vector3f(topLeft).add(blockOrigin);
        Vector3f edgeU = new Vector3f(topRight).sub(topLeft);
        Vector3f edgeV = new Vector3f(bottomLeft).sub(topLeft);
        Vector3f normal = new Vector3f(edgeU).cross(edgeV);

        float denominator = normal.dot(direction);
        if (Math.abs(denominator) < 1.0E-6F) {
            return null;
        }
        float t = new Vector3f(anchor).sub(origin).dot(normal) / denominator;
        if (t < 0.0F) {
            return null;
        }
        return uvOfPoint(blockOrigin, new Vector3f(direction).mul(t).add(origin));
    }

    public float[] uvOfPoint(Vector3f blockOrigin, Vector3f worldPoint) {
        Vector3f anchor = new Vector3f(topLeft).add(blockOrigin);
        Vector3f edgeU = new Vector3f(topRight).sub(topLeft);
        Vector3f edgeV = new Vector3f(bottomLeft).sub(topLeft);
        Vector3f offset = new Vector3f(worldPoint).sub(anchor);

        float u = offset.dot(edgeU) / edgeU.lengthSquared();
        float v = offset.dot(edgeV) / edgeV.lengthSquared();
        if (u < 0.0F || u > 1.0F || v < 0.0F || v > 1.0F) {
            return null;
        }
        return new float[] {u, v};
    }
}
