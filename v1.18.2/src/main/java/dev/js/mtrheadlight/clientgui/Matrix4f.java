package dev.js.mtrheadlight.clientgui;

import java.nio.FloatBuffer;

/**
 * 1.19.2 上的 JOML {@code org.joml.Matrix4f} 替身。
 *
 * <p>1.19.2 的原版<b>不带 JOML</b>（{@code org.joml} 是 1.19.3 才进 Minecraft 依赖树的），
 * 它的 4×4 是 {@code net.minecraft.util.math.Matrix4f} —— 那是个「只有 multiply/translate、
 * 没有 rotate/mul/invert-返回自身、也没有 m00() 这类元素访问器」的极简版本。
 * 1.19.4 的 3D 光束编辑器与车模预览（{@code TrainPreview} / {@code BeamEditor3DScreen} /
 * 粒子）是按 JOML 的链式 API 写的，所以这里补一个形状相同的小实现：
 *
 * <ul>
 *   <li>列主序 {@code float[16]}，索引 {@code col * 4 + row}，与 JOML 的 {@code m00..m33}
 *       （{@code m<column><row>}）逐字段对应 —— {@link #m30()} 就是平移的 X，与 JOML 一致；</li>
 *   <li>{@code translate / scale / rotate / mul} 全部是<b>右乘</b>（{@code this = this * other}），
 *       与 JOML 的链式语义一致，所以原代码的「先转后移」顺序不用改；</li>
 *   <li>{@link #toMc()} 交给原版：先用 {@code writeColumnMajor} 导出（实测列主序，
 *       平移落在索引 12/13/14），再用 {@code readColumnMajor} 装进
 *       {@code net.minecraft.util.math.Matrix4f}。注意 1.19.2 的 {@code new Matrix4f()}
 *       是<b>全零</b>而不是单位阵，所以这里必须走 {@code readColumnMajor} 赋值。</li>
 * </ul>
 *
 * <p>角度单位只有 {@link #rotate(Quaternionf)}（四元数）与 {@link #rotateZ(float)}（弧度），
 * 没有「角度优先」的重载，避免 1.19.4 那份的 {@code RotationAxis#rotationDegrees} 语义被搬错。
 */
public final class Matrix4f {
    /** 列主序：{@code m[col * 4 + row]}。 */
    private final float[] m = new float[16];

    /** 单位阵。 */
    public Matrix4f() {
        this.m[0] = 1.0F;
        this.m[5] = 1.0F;
        this.m[10] = 1.0F;
        this.m[15] = 1.0F;
    }

    /** 拷贝构造（对应 JOML 的 {@code new Matrix4f(Matrix4fc)}）。 */
    public Matrix4f(final Matrix4f other) {
        System.arraycopy(other.m, 0, this.m, 0, 16);
    }

    /** 纯旋转矩阵。 */
    public Matrix4f(final Quaternionf rotation) {
        this.rotation(rotation);
    }

    // ------------------------------------------------------------------ 元素访问（JOML 命名）

    public float m00() {
        return this.m[0];
    }

    public float m01() {
        return this.m[1];
    }

    public float m02() {
        return this.m[2];
    }

    public float m03() {
        return this.m[3];
    }

    public float m10() {
        return this.m[4];
    }

    public float m11() {
        return this.m[5];
    }

    public float m12() {
        return this.m[6];
    }

    public float m13() {
        return this.m[7];
    }

    public float m20() {
        return this.m[8];
    }

    public float m21() {
        return this.m[9];
    }

    public float m22() {
        return this.m[10];
    }

    public float m23() {
        return this.m[11];
    }

    public float m30() {
        return this.m[12];
    }

    public float m31() {
        return this.m[13];
    }

    public float m32() {
        return this.m[14];
    }

    public float m33() {
        return this.m[15];
    }

    // ------------------------------------------------------------------ 组合

    /** 重置为纯旋转矩阵（JOML {@code rotation(Quaternionfc)}）。 */
    public Matrix4f rotation(final Quaternionf q) {
        final float xx = q.x * q.x;
        final float xy = q.x * q.y;
        final float xz = q.x * q.z;
        final float xw = q.x * q.w;
        final float yy = q.y * q.y;
        final float yz = q.y * q.z;
        final float yw = q.y * q.w;
        final float zz = q.z * q.z;
        final float zw = q.z * q.w;

        this.m[0] = 1.0F - 2.0F * (yy + zz);   // m00
        this.m[1] = 2.0F * (xy + zw);          // m01
        this.m[2] = 2.0F * (xz - yw);          // m02
        this.m[3] = 0.0F;
        this.m[4] = 2.0F * (xy - zw);          // m10
        this.m[5] = 1.0F - 2.0F * (xx + zz);   // m11
        this.m[6] = 2.0F * (yz + xw);          // m12
        this.m[7] = 0.0F;
        this.m[8] = 2.0F * (xz + yw);          // m20
        this.m[9] = 2.0F * (yz - xw);          // m21
        this.m[10] = 1.0F - 2.0F * (xx + yy);  // m22
        this.m[11] = 0.0F;
        this.m[12] = 0.0F;
        this.m[13] = 0.0F;
        this.m[14] = 0.0F;
        this.m[15] = 1.0F;
        return this;
    }

    /** 右乘：{@code this = this * other}。 */
    public Matrix4f mul(final Matrix4f other) {
        final float[] a = this.m;
        final float[] b = other.m;
        final float[] out = new float[16];
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                out[col * 4 + row] = a[row] * b[col * 4]
                        + a[4 + row] * b[col * 4 + 1]
                        + a[8 + row] * b[col * 4 + 2]
                        + a[12 + row] * b[col * 4 + 3];
            }
        }
        System.arraycopy(out, 0, this.m, 0, 16);
        return this;
    }

    /** 右乘平移（JOML {@code translate(x, y, z)}）。 */
    public Matrix4f translate(final float x, final float y, final float z) {
        this.m[12] = this.m[0] * x + this.m[4] * y + this.m[8] * z + this.m[12];
        this.m[13] = this.m[1] * x + this.m[5] * y + this.m[9] * z + this.m[13];
        this.m[14] = this.m[2] * x + this.m[6] * y + this.m[10] * z + this.m[14];
        this.m[15] = this.m[3] * x + this.m[7] * y + this.m[11] * z + this.m[15];
        return this;
    }

    /** 右乘缩放（JOML {@code scale(x, y, z)}）。 */
    public Matrix4f scale(final float x, final float y, final float z) {
        for (int row = 0; row < 4; row++) {
            this.m[row] *= x;
            this.m[4 + row] *= y;
            this.m[8 + row] *= z;
        }
        return this;
    }

    /** 右乘四元数旋转（JOML {@code rotate(Quaternionfc)}）。 */
    public Matrix4f rotate(final Quaternionf q) {
        return this.mul(new Matrix4f(q));
    }

    /** 右乘绕 Z 轴旋转，弧度（JOML {@code rotateZ(float)}）。 */
    public Matrix4f rotateZ(final float radians) {
        return this.rotate(new Quaternionf().rotateZ(radians));
    }

    // ------------------------------------------------------------------ 逆

    /** 原地求逆（JOML {@code invert()}）。检测到奇异矩阵时保持原值并返回 this。 */
    public Matrix4f invert() {
        final float[] a = this.m;
        final float[] inv = new float[16];

        inv[0] = a[5] * a[10] * a[15] - a[5] * a[11] * a[14] - a[9] * a[6] * a[15]
                + a[9] * a[7] * a[14] + a[13] * a[6] * a[11] - a[13] * a[7] * a[10];
        inv[4] = -a[4] * a[10] * a[15] + a[4] * a[11] * a[14] + a[8] * a[6] * a[15]
                - a[8] * a[7] * a[14] - a[12] * a[6] * a[11] + a[12] * a[7] * a[10];
        inv[8] = a[4] * a[9] * a[15] - a[4] * a[11] * a[13] - a[8] * a[5] * a[15]
                + a[8] * a[7] * a[13] + a[12] * a[5] * a[11] - a[12] * a[7] * a[9];
        inv[12] = -a[4] * a[9] * a[14] + a[4] * a[10] * a[13] + a[8] * a[5] * a[14]
                - a[8] * a[6] * a[13] - a[12] * a[5] * a[10] + a[12] * a[6] * a[9];
        inv[1] = -a[1] * a[10] * a[15] + a[1] * a[11] * a[14] + a[9] * a[2] * a[15]
                - a[9] * a[3] * a[14] - a[13] * a[2] * a[11] + a[13] * a[3] * a[10];
        inv[5] = a[0] * a[10] * a[15] - a[0] * a[11] * a[14] - a[8] * a[2] * a[15]
                + a[8] * a[3] * a[14] + a[12] * a[2] * a[11] - a[12] * a[3] * a[10];
        inv[9] = -a[0] * a[9] * a[15] + a[0] * a[11] * a[13] + a[8] * a[1] * a[15]
                - a[8] * a[3] * a[13] - a[12] * a[1] * a[11] + a[12] * a[3] * a[9];
        inv[13] = a[0] * a[9] * a[14] - a[0] * a[10] * a[13] - a[8] * a[1] * a[14]
                + a[8] * a[2] * a[13] + a[12] * a[1] * a[10] - a[12] * a[2] * a[9];
        inv[2] = a[1] * a[6] * a[15] - a[1] * a[7] * a[14] - a[5] * a[2] * a[15]
                + a[5] * a[3] * a[14] + a[13] * a[2] * a[7] - a[13] * a[3] * a[6];
        inv[6] = -a[0] * a[6] * a[15] + a[0] * a[7] * a[14] + a[4] * a[2] * a[15]
                - a[4] * a[3] * a[14] - a[12] * a[2] * a[7] + a[12] * a[3] * a[6];
        inv[10] = a[0] * a[5] * a[15] - a[0] * a[7] * a[13] - a[4] * a[1] * a[15]
                + a[4] * a[3] * a[13] + a[12] * a[1] * a[7] - a[12] * a[3] * a[5];
        inv[14] = -a[0] * a[5] * a[14] + a[0] * a[6] * a[13] + a[4] * a[1] * a[14]
                - a[4] * a[2] * a[13] - a[12] * a[1] * a[6] + a[12] * a[2] * a[5];
        inv[3] = -a[1] * a[6] * a[11] + a[1] * a[7] * a[10] + a[5] * a[2] * a[11]
                - a[5] * a[3] * a[10] - a[9] * a[2] * a[7] + a[9] * a[3] * a[6];
        inv[7] = a[0] * a[6] * a[11] - a[0] * a[7] * a[10] - a[4] * a[2] * a[11]
                + a[4] * a[3] * a[10] + a[8] * a[2] * a[7] - a[8] * a[3] * a[6];
        inv[11] = -a[0] * a[5] * a[11] + a[0] * a[7] * a[9] + a[4] * a[1] * a[11]
                - a[4] * a[3] * a[9] - a[8] * a[1] * a[7] + a[8] * a[3] * a[5];
        inv[15] = a[0] * a[5] * a[10] - a[0] * a[6] * a[9] - a[4] * a[1] * a[10]
                + a[4] * a[2] * a[9] + a[8] * a[1] * a[6] - a[8] * a[2] * a[5];

        float det = a[0] * inv[0] + a[1] * inv[4] + a[2] * inv[8] + a[3] * inv[12];
        if (det == 0.0F || Float.isNaN(det)) {
            return this;
        }
        det = 1.0F / det;
        for (int i = 0; i < 16; i++) {
            this.m[i] = inv[i] * det;
        }
        return this;
    }

    // ------------------------------------------------------------------ 向量变换

    /** 只用左上 3×3 变换方向、忽略平移（JOML {@code transformDirection}），返回新向量。 */
    public Vector3f transformDirection(final Vector3f v) {
        return new Vector3f(
                this.m[0] * v.x + this.m[4] * v.y + this.m[8] * v.z,
                this.m[1] * v.x + this.m[5] * v.y + this.m[9] * v.z,
                this.m[2] * v.x + this.m[6] * v.y + this.m[10] * v.z);
    }

    /** 接原版渲染管线：导出成 {@code net.minecraft.util.math.Matrix4f}。 */
    public net.minecraft.util.math.Matrix4f toMc() {
        final net.minecraft.util.math.Matrix4f out = new net.minecraft.util.math.Matrix4f();
        out.readColumnMajor(FloatBuffer.wrap(this.m.clone()));
        return out;
    }

    /** 调试用：列主序快照。 */
    public float[] toArray() {
        return this.m.clone();
    }
}
