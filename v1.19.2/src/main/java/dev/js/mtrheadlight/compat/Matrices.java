package dev.js.mtrheadlight.compat;

import java.nio.FloatBuffer;

import org.lwjgl.BufferUtils;

import net.minecraft.util.math.Matrix4f;

/**
 * 1.19.2 上的矩阵小工具。
 *
 * <p>1.20.1 里 {@code org.joml.Matrix4f} 有一个「16 个 float、列主序」的构造器，本工程的几个方块
 * 渲染器用它把一个「三个基向量拼成的纯旋转」直接塞进矩阵。1.19.2 的
 * {@code net.minecraft.util.math.Matrix4f} 没有元素级构造器（字段全私有），只有
 * {@link Matrix4f#readColumnMajor(FloatBuffer)}，所以这里补一个同义的工厂方法。
 */
public final class Matrices {

    private Matrices() {
    }

    /**
     * 把一个 1.19.2 的 {@link Matrix4f} 摊成列主序的 16 个 float
     * （{@code e[row + 4 * column]}，也就是 {@code m00=e[0], m01=e[4], m30=e[3], m33=e[15]}）。
     *
     * <p>1.19.2 的 {@code Matrix4f} 字段全私有、也没有 joml 的 {@code m00()} 一类访问器，
     * 想读元素只能整体导出。
     *
     * <p><b>不能 {@code flip()} 之后再 {@code get()}。</b> 1.19.2 的
     * {@code writeColumnMajor(FloatBuffer)} 用的是<b>绝对下标写入</b>
     * （{@code buf.put(pack(i, j), value)}，见 javap：{@code FloatBuffer.put(int,float)}），
     * 写完 buffer 的 <b>position 仍然是 0</b>。于是 {@code flip()} 把 limit 也设成 0，
     * 紧接着的 {@code get(float[])} 直接抛 {@code BufferUnderflowException}
     * （1.19.2 上散射 pass 每一帧都抛这个异常、整条 pass 从不落笔，就是这里）。
     * 正确做法是按绝对下标取。
     */
    public static float[] columnMajor(final Matrix4f matrix) {
        final float[] out = new float[16];
        if (matrix == null) {
            return out;
        }
        final FloatBuffer buffer = BufferUtils.createFloatBuffer(16);
        matrix.writeColumnMajor(buffer);
        for (int i = 0; i < 16; i++) {
            out[i] = buffer.get(i);
        }
        return out;
    }

    /**
     * <b>精确的 4×4 求逆</b> —— 1.19.2 自带的那条在这个 pass 里不可用。
     *
     * <p>{@code net.minecraft.util.math.Matrix4f#invert()} 在 1.19.2 上是旧 Blaze3D 的那份实现，
     * 对<b>仿射</b>矩阵完全正确（纯旋转/平移误差 {@code 1.8e-7}），但一旦矩阵里带投影行
     * （{@code proj * view}，正是散射 pass 要的），它给出的是一个<b>统一缩放过的</b>逆：
     * 本机实测 {@code mcInv * vp = 0.0132 * I}（即 {@code |inv*vp - I| = 0.958}），
     * 而不是 {@code I}。原版从不对投影矩阵求逆，所以这个限制在 1.20.1（joml）里不存在、
     * 在 1.19.2 里也从没人踩过。
     *
     * <p>统一缩放在<b>这一条</b>链路上恰好无害（着色器里的 {@code rd} 会归一化、{@code tHit}
     * 用 {@code xyz/w} 也不受影响），所以它<b>不是</b> 1.19.2 上「不出光」的原因；<b>但是</b>
     * 它让任何「逆矩阵对不对」的判断失去意义，而这里恰恰需要一个能自证的数
     * （{@link #inverseError}）。既然要算，就算对。
     *
     * <p>这里按列主序对这 16 个数做标准的余子式展开（与 GLU / joml 的实现逐项相同），
     * 结果写回 {@code destination}。
     *
     * @return {@code false} 当矩阵奇异（此时 {@code destination} 不被修改）
     */
    public static boolean invert(final Matrix4f source, final Matrix4f destination) {
        if (source == null || destination == null) {
            return false;
        }
        final float[] m = columnMajor(source);
        final float[] inv = new float[16];
        inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15]
                + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10];
        inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15]
                - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10];
        inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15]
                + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9];
        inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14]
                - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9];
        inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15]
                - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10];
        inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15]
                + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10];
        inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15]
                - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9];
        inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14]
                + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9];
        inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15]
                + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6];
        inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15]
                - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6];
        inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15]
                + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5];
        inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14]
                - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5];
        inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11]
                - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6];
        inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11]
                + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6];
        inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11]
                - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5];
        inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10]
                + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5];
        final float det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12];
        if (det == 0.0F || Float.isNaN(det) || Float.isInfinite(det)) {
            return false;
        }
        final float scale = 1.0F / det;
        for (int i = 0; i < 16; i++) {
            inv[i] *= scale;
        }
        final FloatBuffer buffer = BufferUtils.createFloatBuffer(16);
        buffer.put(inv);
        buffer.flip();
        destination.readColumnMajor(buffer);
        return true;
    }

    /**
     * {@code |a * b - I|} 的最大元素偏差：给 {@code /mtr_lte scatter} 的周期行当「逆矩阵到底对不对」的证据。
     * 只在对角线上应当是 1、其余是 0，所以一次减法和取绝对值就够了。
     */
    public static float inverseError(final Matrix4f a, final Matrix4f b) {
        if (a == null || b == null) {
            return Float.POSITIVE_INFINITY;
        }
        final float[] ea = columnMajor(a);
        final float[] eb = columnMajor(b);
        float worst = 0.0F;
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                float sum = 0.0F;
                for (int k = 0; k < 4; k++) {
                    sum += ea[k * 4 + row] * eb[col * 4 + k];
                }
                final float want = row == col ? 1.0F : 0.0F;
                worst = Math.max(worst, Math.abs(sum - want));
            }
        }
        return worst;
    }

    /**
     * 三个基向量（列 0 / 列 1 / 列 2）拼成的旋转矩阵，最后一行 ({@code 0,0,0,1})：
     * 平移为零、无投影项 —— 也就是「只转不平移」。
     *
     * <p>参数顺序与 JOML 那个构造器逐位对应：先是列 0 的三个分量，再列 1、列 2。
     */
    public static Matrix4f fromColumns(final double x0, final double y0, final double z0,
                                       final double x1, final double y1, final double z1,
                                       final double x2, final double y2, final double z2) {
        final FloatBuffer buffer = BufferUtils.createFloatBuffer(16);
        buffer.put(new float[] {
                (float) x0, (float) y0, (float) z0, 0.0F,
                (float) x1, (float) y1, (float) z1, 0.0F,
                (float) x2, (float) y2, (float) z2, 0.0F,
                0.0F, 0.0F, 0.0F, 1.0F,
        });
        buffer.flip();
        final Matrix4f out = new Matrix4f();
        out.readColumnMajor(buffer);
        return out;
    }
}
