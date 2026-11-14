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
     */
    public static float[] columnMajor(final Matrix4f matrix) {
        final float[] out = new float[16];
        if (matrix == null) {
            return out;
        }
        final FloatBuffer buffer = BufferUtils.createFloatBuffer(16);
        matrix.writeColumnMajor(buffer);
        buffer.flip();
        buffer.get(out);
        return out;
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
