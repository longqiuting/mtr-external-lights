// Ported from Minecraft Transit Railway (MTR), MIT License.
package dev.js.mtrheadlight.xiamen;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.VertexConsumer;

/**
 * 画轴对齐薄板 / 小立方体的小工具（门扇、门灯、灯箱都靠它）。
 *
 * <p>坐标单位是 <b>像素</b>：x ∈ [-8, 8]（沿门宽）、y ∈ [0, 16]（向上，格底为 0）、
 * z ∈ [-8, 8]（<b>+z 朝外</b>，即朝轨道一侧）。这个空间与 MTR 的
 * {@code StoredMatrixTransformations}（translate(0.5,0,0.5) + rotateY(-facing.asRotation())
 * + rotateX(180)）在 y/z 上是同一套，只是省掉了 180° 翻转，所以 MTR 的模型数字可以直接照抄。
 */
public final class PsdQuads {

    private PsdQuads() {
    }

    private static void vertex(MatrixStack.Entry entry, VertexConsumer vc,
                               float x, float y, float z, float u, float v,
                               float nx, float ny, float nz, int light, int overlay) {
        vc.vertex(entry.getPositionMatrix(), x / 16.0F, y / 16.0F, z / 16.0F)
                .color(255, 255, 255, 255)
                .texture(u, v)
                .overlay(overlay)
                .light(light)
                .normal(entry.getNormalMatrix(), nx, ny, nz)
                .next();
    }

    /** 面向 ±z 的一块矩形（u 从 u0 到 u1）。 */
    public static void quadZ(MatrixStack matrices, VertexConsumer vc, float x0, float x1,
                             float y0, float y1, float z, float u0, float u1,
                             boolean positive, int light, int overlay) {
        MatrixStack.Entry entry = matrices.peek();
        float nz = positive ? 1.0F : -1.0F;
        if (positive) {
            vertex(entry, vc, x0, y0, z, u0, 1.0F, 0, 0, nz, light, overlay);
            vertex(entry, vc, x1, y0, z, u1, 1.0F, 0, 0, nz, light, overlay);
            vertex(entry, vc, x1, y1, z, u1, 0.0F, 0, 0, nz, light, overlay);
            vertex(entry, vc, x0, y1, z, u0, 0.0F, 0, 0, nz, light, overlay);
        } else {
            vertex(entry, vc, x1, y0, z, u1, 1.0F, 0, 0, nz, light, overlay);
            vertex(entry, vc, x0, y0, z, u0, 1.0F, 0, 0, nz, light, overlay);
            vertex(entry, vc, x0, y1, z, u0, 0.0F, 0, 0, nz, light, overlay);
            vertex(entry, vc, x1, y1, z, u1, 0.0F, 0, 0, nz, light, overlay);
        }
    }

    /** 面向 ±x 的一块矩形（门的窄边）。 */
    public static void quadX(MatrixStack matrices, VertexConsumer vc, float x, float y0, float y1,
                             float z0, float z1, float u0, float u1, boolean positive,
                             int light, int overlay) {
        MatrixStack.Entry entry = matrices.peek();
        float nx = positive ? 1.0F : -1.0F;
        if (positive) {
            vertex(entry, vc, x, y0, z0, u0, 1.0F, nx, 0, 0, light, overlay);
            vertex(entry, vc, x, y0, z1, u1, 1.0F, nx, 0, 0, light, overlay);
            vertex(entry, vc, x, y1, z1, u1, 0.0F, nx, 0, 0, light, overlay);
            vertex(entry, vc, x, y1, z0, u0, 0.0F, nx, 0, 0, light, overlay);
        } else {
            vertex(entry, vc, x, y0, z1, u1, 1.0F, nx, 0, 0, light, overlay);
            vertex(entry, vc, x, y0, z0, u0, 1.0F, nx, 0, 0, light, overlay);
            vertex(entry, vc, x, y1, z0, u0, 0.0F, nx, 0, 0, light, overlay);
            vertex(entry, vc, x, y1, z1, u1, 0.0F, nx, 0, 0, light, overlay);
        }
    }

    /** 面向 ±y 的一块矩形（板的上下檐）。 */
    public static void quadY(MatrixStack matrices, VertexConsumer vc, float y, float x0, float x1,
                             float z0, float z1, float u0, float u1, boolean positive,
                             int light, int overlay) {
        MatrixStack.Entry entry = matrices.peek();
        float ny = positive ? 1.0F : -1.0F;
        if (positive) {
            vertex(entry, vc, x0, y, z1, u0, 0.0F, 0, ny, 0, light, overlay);
            vertex(entry, vc, x1, y, z1, u1, 0.0F, 0, ny, 0, light, overlay);
            vertex(entry, vc, x1, y, z0, u1, 1.0F, 0, ny, 0, light, overlay);
            vertex(entry, vc, x0, y, z0, u0, 1.0F, 0, ny, 0, light, overlay);
        } else {
            vertex(entry, vc, x0, y, z0, u0, 0.0F, 0, ny, 0, light, overlay);
            vertex(entry, vc, x1, y, z0, u1, 0.0F, 0, ny, 0, light, overlay);
            vertex(entry, vc, x1, y, z1, u1, 1.0F, 0, ny, 0, light, overlay);
            vertex(entry, vc, x0, y, z1, u0, 1.0F, 0, ny, 0, light, overlay);
        }
    }

    /**
     * 一块朝外的门板：正面 / 背面各用一张贴图，四条窄边用正面贴图的一条边。
     *
     * @param u0 正面 UV 左边界（端部门只画半扇时传 0 或 0.5）
     * @param u1 正面 UV 右边界
     */
    public static void plate(MatrixStack matrices, VertexConsumer front, VertexConsumer back,
                             float x0, float x1, float y0, float y1, float z0, float z1,
                             float u0, float u1, int light, int overlay) {
        quadZ(matrices, front, x0, x1, y0, y1, z1, u0, u1, true, light, overlay);
        quadZ(matrices, back, x0, x1, y0, y1, z0, u0, u1, false, light, overlay);
        float rimU0 = u0;
        float rimU1 = u0 + (u1 - u0) * 0.03F;
        quadY(matrices, front, y1, x0, x1, z0, z1, rimU0, rimU1, true, light, overlay);
        quadY(matrices, front, y0, x0, x1, z0, z1, rimU0, rimU1, false, light, overlay);
        quadX(matrices, front, x0, y0, y1, z0, z1, rimU0, rimU1, false, light, overlay);
        quadX(matrices, front, x1, y0, y1, z0, z1, rimU0, rimU1, true, light, overlay);
    }

    /** 一个 6 面都贴同一张图的小立方体（门灯用）。 */
    public static void cube(MatrixStack matrices, VertexConsumer vc,
                            float x0, float x1, float y0, float y1, float z0, float z1,
                            float u0, float u1, int light, int overlay) {
        quadZ(matrices, vc, x0, x1, y0, y1, z1, u0, u1, true, light, overlay);
        quadZ(matrices, vc, x0, x1, y0, y1, z0, u0, u1, false, light, overlay);
        quadX(matrices, vc, x0, y0, y1, z0, z1, u0, u1, false, light, overlay);
        quadX(matrices, vc, x1, y0, y1, z0, z1, u0, u1, true, light, overlay);
        quadY(matrices, vc, y0, x0, x1, z0, z1, u0, u1, false, light, overlay);
        quadY(matrices, vc, y1, x0, x1, z0, z1, u0, u1, true, light, overlay);
    }
}
