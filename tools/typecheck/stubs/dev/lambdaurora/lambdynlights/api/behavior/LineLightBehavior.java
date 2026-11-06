package dev.lambdaurora.lambdynlights.api.behavior;

import org.joml.Vector3d;

/** Stub mirroring the LambDynamicLights 4.4.0 LineLightBehavior signature set. */
public class LineLightBehavior implements DynamicLightBehavior {
    private final Vector3d start = new Vector3d();
    private final Vector3d end = new Vector3d();
    private int luminance;

    public LineLightBehavior(Vector3d start, Vector3d end, int luminance) {
        this.luminance = luminance;
    }

    public void setStartPoint(double x, double y, double z) {
        this.start.x = x;
        this.start.y = y;
        this.start.z = z;
    }

    public void setEndPoint(double x, double y, double z) {
        this.end.x = x;
        this.end.y = y;
        this.end.z = z;
    }

    public int getLuminance() {
        return this.luminance;
    }

    public void setLuminance(int luminance) {
        this.luminance = luminance;
    }

    @Override
    public double lightAtPos(net.minecraft.util.math.BlockPos pos, double factor) {
        return 0.0D;
    }

    @Override
    public BoundingBox getBoundingBox() {
        return new BoundingBox(0, 0, 0, 0, 0, 0);
    }

    @Override
    public boolean hasChanged() {
        return false;
    }
}
