package dev.js.mtrheadlight.clientgui;

import net.minecraft.util.math.Quaternion;
import net.minecraft.util.math.Vec3f;

/**
 * 1.19.2 上的 {@code net.minecraft.util.math.RotationAxis} 替身。
 *
 * <p>1.20.1 / 1.19.4 的 {@code MatrixStack#multiply} 收 {@code org.joml.Quaternionf}，旋转角度的
 * 惯用写法是 {@code matrices.multiply(RotationAxis.NEGATIVE_Y.rotationDegrees(d))}；
 * {@code RotationAxis} 本身是 1.19.3 跟着 JOML 一起进来的，1.19.2 没有。
 *
 * <p>1.19.2 的 {@code MatrixStack#multiply} 收的是 {@code net.minecraft.util.math.Quaternion}，
 * 所以这里只做一件事：把「哪个轴 + 多少角度」翻成那个类。{@code NEGATIVE_*} 用轴的负方向表达
 * （和 1.20.1 的语义一致：绕 -Y 转 d 度 = 绕 +Y 转 -d 度，两者是同一个旋转）。
 */
public enum RotationAxis {
    POSITIVE_X(1.0F, 0.0F, 0.0F),
    NEGATIVE_X(-1.0F, 0.0F, 0.0F),
    POSITIVE_Y(0.0F, 1.0F, 0.0F),
    NEGATIVE_Y(0.0F, -1.0F, 0.0F),
    POSITIVE_Z(0.0F, 0.0F, 1.0F),
    NEGATIVE_Z(0.0F, 0.0F, -1.0F);

    private final float axisX;
    private final float axisY;
    private final float axisZ;

    RotationAxis(final float axisX, final float axisY, final float axisZ) {
        this.axisX = axisX;
        this.axisY = axisY;
        this.axisZ = axisZ;
    }

    /** 角度制版本（1.20.1 的 {@code RotationAxis#rotationDegrees}）。 */
    public Quaternion rotationDegrees(final float degrees) {
        return new Quaternion(new Vec3f(this.axisX, this.axisY, this.axisZ), degrees, true);
    }

    /** 弧度制版本（1.20.1 的 {@code RotationAxis#rotation}）。 */
    public Quaternion rotation(final float radians) {
        return new Quaternion(new Vec3f(this.axisX, this.axisY, this.axisZ), radians, false);
    }
}
