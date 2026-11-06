package net.minecraft.util.math;

/** Stub mirroring the Yarn 1.20.1 signature set used by this mod (javap-verified shape). */
public class Vec3d {
    public static final Vec3d ZERO = new Vec3d(0.0D, 0.0D, 0.0D);
    public final double x;
    public final double y;
    public final double z;

    public Vec3d(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Vec3d add(double x, double y, double z) {
        return new Vec3d(this.x + x, this.y + y, this.z + z);
    }

    public Vec3d subtract(Vec3d other) {
        return new Vec3d(this.x - other.x, this.y - other.y, this.z - other.z);
    }

    public Vec3d multiply(double factor) {
        return new Vec3d(this.x * factor, this.y * factor, this.z * factor);
    }

    public double length() {
        return Math.sqrt(this.x * this.x + this.y * this.y + this.z * this.z);
    }

    public double distanceTo(Vec3d other) {
        return Math.sqrt(Math.pow(other.x - this.x, 2) + Math.pow(other.y - this.y, 2) + Math.pow(other.z - this.z, 2));
    }
}
