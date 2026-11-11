package dev.js.mtrheadlight.clientgui;

/**
 * 1.19.2 上的 JOML {@code org.joml.Vector3f} 替身（见 {@link Matrix4f} 的类注释）。
 *
 * <p>为什么不是 {@code net.minecraft.util.math.Vec3f}：水花粒子的代码按 JOML 写法
 * <b>直接读写 public 字段</b>（{@code corner.y *= VERTICAL_STRETCH}、{@code vel.x}），
 * 而原版的 {@code Vec3f} 只有 getter/setter。这里保留 JOML 的字段风格，屏幕与粒子代码
 * 一行不改。
 */
public final class Vector3f {
    public float x;
    public float y;
    public float z;

    public Vector3f() {
    }

    public Vector3f(final float x, final float y, final float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Vector3f(final Vector3f other) {
        this.x = other.x;
        this.y = other.y;
        this.z = other.z;
    }

    public float x() {
        return this.x;
    }

    public float y() {
        return this.y;
    }

    public float z() {
        return this.z;
    }

    public Vector3f set(final float x, final float y, final float z) {
        this.x = x;
        this.y = y;
        this.z = z;
        return this;
    }

    public Vector3f set(final Vector3f other) {
        return this.set(other.x, other.y, other.z);
    }

    public Vector3f mul(final float scalar) {
        this.x *= scalar;
        this.y *= scalar;
        this.z *= scalar;
        return this;
    }

    public Vector3f add(final float dx, final float dy, final float dz) {
        this.x += dx;
        this.y += dy;
        this.z += dz;
        return this;
    }

    public Vector3f add(final Vector3f other) {
        return this.add(other.x, other.y, other.z);
    }

    public Vector3f sub(final Vector3f other) {
        return this.add(-other.x, -other.y, -other.z);
    }

    /** 用四元数旋转本向量（JOML {@code rotate(Quaternionfc)}）。 */
    public Vector3f rotate(final Quaternionf q) {
        // t = 2 * (q.xyz × v); v' = v + q.w * t + q.xyz × t
        final float tx = 2.0F * (q.y * this.z - q.z * this.y);
        final float ty = 2.0F * (q.z * this.x - q.x * this.z);
        final float tz = 2.0F * (q.x * this.y - q.y * this.x);
        final float nx = this.x + q.w * tx + (q.y * tz - q.z * ty);
        final float ny = this.y + q.w * ty + (q.z * tx - q.x * tz);
        final float nz = this.z + q.w * tz + (q.x * ty - q.y * tx);
        this.x = nx;
        this.y = ny;
        this.z = nz;
        return this;
    }

    /** 绕 Z 轴旋转，弧度（JOML {@code rotateZ(float)}）。 */
    public Vector3f rotateZ(final float radians) {
        final float cos = (float) Math.cos(radians);
        final float sin = (float) Math.sin(radians);
        final float nx = this.x * cos - this.y * sin;
        final float ny = this.x * sin + this.y * cos;
        this.x = nx;
        this.y = ny;
        return this;
    }

    @Override
    public String toString() {
        return "(" + this.x + ", " + this.y + ", " + this.z + ")";
    }
}
