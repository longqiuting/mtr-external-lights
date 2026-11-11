package dev.js.mtrheadlight.clientgui;

/**
 * 1.19.2 上的 JOML {@code org.joml.Quaternionf} 替身（见 {@link Matrix4f} 的类注释）。
 *
 * <p>只实现光束编辑器 / 车模预览 / 水花粒子用到的那一小块：
 * 拷贝构造、从原版 {@code net.minecraft.util.math.Quaternion} 转换、Hamilton 乘法、
 * 共轭、绕 Z 轴旋转。{@code rotateZ} 收的是<b>弧度</b>，与 JOML 一致
 * （{@code Particle} 的 {@code angle} 与 {@code atan2} 出来的 {@code tilt} 都是弧度）。
 */
public final class Quaternionf {
    float x;
    float y;
    float z;
    float w;

    /** 单位四元数。 */
    public Quaternionf() {
        this.w = 1.0F;
    }

    public Quaternionf(final float x, final float y, final float z, final float w) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.w = w;
    }

    public Quaternionf(final Quaternionf other) {
        this.x = other.x;
        this.y = other.y;
        this.z = other.z;
        this.w = other.w;
    }

    /** 从原版四元数转换（1.19.2 的 {@code Camera#getRotation()} 返回它）。 */
    public Quaternionf(final net.minecraft.util.math.Quaternion vanilla) {
        this.x = vanilla.getX();
        this.y = vanilla.getY();
        this.z = vanilla.getZ();
        this.w = vanilla.getW();
    }

    public Quaternionf identity() {
        this.x = 0.0F;
        this.y = 0.0F;
        this.z = 0.0F;
        this.w = 1.0F;
        return this;
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

    public float w() {
        return this.w;
    }

    /** 右乘（Hamilton 积）：{@code this = this * other}，与 JOML {@code mul} 一致。 */
    public Quaternionf mul(final Quaternionf other) {
        final float nx = this.w * other.x + this.x * other.w + this.y * other.z - this.z * other.y;
        final float ny = this.w * other.y - this.x * other.z + this.y * other.w + this.z * other.x;
        final float nz = this.w * other.z + this.x * other.y - this.y * other.x + this.z * other.w;
        final float nw = this.w * other.w - this.x * other.x - this.y * other.y - this.z * other.z;
        this.x = nx;
        this.y = ny;
        this.z = nz;
        this.w = nw;
        return this;
    }

    /** 共轭（原地）。 */
    public Quaternionf conjugate() {
        this.x = -this.x;
        this.y = -this.y;
        this.z = -this.z;
        return this;
    }

    /** 右乘绕 Z 轴旋转，弧度（JOML {@code rotateZ(float)}）。 */
    public Quaternionf rotateZ(final float radians) {
        final float half = radians * 0.5F;
        return this.mul(new Quaternionf(0.0F, 0.0F,
                (float) Math.sin(half), (float) Math.cos(half)));
    }

    /** 右乘绕 Y 轴旋转，弧度。 */
    public Quaternionf rotateY(final float radians) {
        final float half = radians * 0.5F;
        return this.mul(new Quaternionf(0.0F,
                (float) Math.sin(half), 0.0F, (float) Math.cos(half)));
    }

    /** 右乘绕 X 轴旋转，弧度。 */
    public Quaternionf rotateX(final float radians) {
        final float half = radians * 0.5F;
        return this.mul(new Quaternionf(
                (float) Math.sin(half), 0.0F, 0.0F, (float) Math.cos(half)));
    }
}
