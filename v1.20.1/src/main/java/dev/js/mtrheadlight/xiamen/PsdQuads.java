// Ported from Minecraft Transit Railway (MTR), MIT License.
package dev.js.mtrheadlight.xiamen;

import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;

/**
 * 画 MTR「盒子 UV 贴图」薄板 / 小立方体的小工具（门扇、门灯、锁定牌都靠它）。
 *
 * <h2>坐标</h2>
 * <p>单位是 <b>像素</b>：x ∈ [-8, 8]（沿门宽）、y ∈ [0, 16]（向上，格底为 0）、
 * z ∈ [-8, 8]（<b>+z 朝外</b>，即朝轨道一侧）。这个空间与 MTR 的
 * {@code StoredMatrixTransformations}（translate(0.5,0,0.5) + rotateY(-facing.asRotation())
 * + rotateX(180)）在 y/z 上是同一套，只是省掉了 180° 翻转，所以 MTR 的模型数字可以直接照抄
 * （x 不翻、y 与 z 取反，见 {@link UvSet#box}）。
 *
 * <h2>盒子 UV</h2>
 * <p>MTR 的门扇不是「整张图铺满一个面」，而是 Minecraft 的 <b>box UV</b>：
 * 一个 (w,h,d) 的盒子在贴图上占 2(d+w) × (d+h) 个「单位」，正面 / 背面 / 四条边
 * 各自落在不同的小矩形里。资源包里的 {@code psd_door_*_1.png} 就是按这个布局画的
 * （例：{@code psd_door_top_left_1.png} 576×288 = 36×18 单位 × 16 倍，左半张是盒子 +z 面、
 * 右半张是 -z 面 —— 哪一半朝轨道见 {@link UvSet#box} 的 180° 翻转说明）。
 * MTR 的 {@code RenderPSDAPGDoor$ModelSingleCube} 也是这个算法：
 * {@code new ModelSingleCube(36, 18, 0, 0, 0, 16, 16, 2)} —— 前两个参数就是 2(d+w) 与 (d+h)。
 */
public final class PsdQuads {

    private PsdQuads() {
    }

    /** 一个面上的 UV 矩形，(u0,v0) 是面的左上角、(u1,v1) 是右下角（都已是 0..1 的比例）。 */
    public record Uv(float u0, float u1, float v0, float v1) {
    }

    /** 一个盒子六个面的 UV。{@code front} 朝 +z、{@code back} 朝 -z、{@code right} 朝 +x、{@code left} 朝 -x。 */
    public record UvSet(Uv front, Uv back, Uv right, Uv left, Uv top, Uv bottom) {

        /**
         * 按 Minecraft 的 box UV 规则算出六面 UV（与 {@code ModelPart.Cuboid} 同一套布局）。
         *
         * <pre>
         *   u: 0 ── d ── d+w ── d+w+d ── 2d+2w
         *      ↑右(+x)  ↑正/背面 ─┐      └ 背面
         *   v: 0 ── d ── d+h
         *      ↑上/下檐      └ 侧面（整高）
         * </pre>
         *
         * <p><b>注意 180° 翻转</b>：MTR 的 {@code lambda$render$0} 在
         * {@code translate(x+0.5, y, z+0.5) → rotateY(-facing)} 之后还有一步
         * {@code rotateXDegrees(180)}，把 y、z 都取反。我们的几何直接在「翻转后」的空间里写
         * （+z 朝轨道、y 向上），所以 <b>盒子的 +z 面成了我们的 -z 面、+y 面成了 -y 面</b>：
         * 六个面在这里按翻转关系重新配对，这样画出来的每张面才和 MTR 一模一样
         * （两侧图集并不镜像对称，实测同一张图左右两半的 meanAbsDiff≈51/255，配错会画反）。
         *
         * @param w 盒宽（沿门宽，像素）
         * @param h 盒高（像素）
         * @param d 盒厚（像素）
         */
        public static UvSet box(float w, float h, float d) {
            float u1 = d;
            float u2 = d + w;
            float u3 = d + w + d;
            float u4 = d + w + d + w; // = 2(d+w)，贴图总宽
            float aw = u4;
            float ah = d + h;
            return new UvSet(
                    new Uv(u3 / aw, u4 / aw, d / ah, (d + h) / ah), // 我们的 +z（朝轨道）← 盒子的 -z 面（图集右半）
                    new Uv(u1 / aw, u2 / aw, d / ah, (d + h) / ah), // 我们的 -z（朝站台内）← 盒子的 +z 面（图集左半）
                    new Uv(0.0F, u1 / aw, d / ah, (d + h) / ah),    // +x 窄边
                    new Uv(u2 / aw, u3 / aw, d / ah, (d + h) / ah), // -x 窄边
                    new Uv(u3 / aw, u4 / aw, 0.0F, d / ah),         // 我们的 +y（上檐）← 盒子的 -y 面
                    new Uv(u1 / aw, u2 / aw, 0.0F, d / ah));        // 我们的 -y（下檐）← 盒子的 +y 面
        }
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

    /** 面向 ±z 的一块矩形。v0 是矩形<b>上</b>边、v1 是<b>下</b>边。 */
    public static void quadZ(MatrixStack matrices, VertexConsumer vc, float x0, float x1,
                             float y0, float y1, float z, Uv uv, boolean positive,
                             int light, int overlay) {
        MatrixStack.Entry entry = matrices.peek();
        float nz = positive ? 1.0F : -1.0F;
        if (positive) {
            vertex(entry, vc, x0, y0, z, uv.u0(), uv.v1(), 0, 0, nz, light, overlay);
            vertex(entry, vc, x1, y0, z, uv.u1(), uv.v1(), 0, 0, nz, light, overlay);
            vertex(entry, vc, x1, y1, z, uv.u1(), uv.v0(), 0, 0, nz, light, overlay);
            vertex(entry, vc, x0, y1, z, uv.u0(), uv.v0(), 0, 0, nz, light, overlay);
        } else {
            vertex(entry, vc, x1, y0, z, uv.u1(), uv.v1(), 0, 0, nz, light, overlay);
            vertex(entry, vc, x0, y0, z, uv.u0(), uv.v1(), 0, 0, nz, light, overlay);
            vertex(entry, vc, x0, y1, z, uv.u0(), uv.v0(), 0, 0, nz, light, overlay);
            vertex(entry, vc, x1, y1, z, uv.u1(), uv.v0(), 0, 0, nz, light, overlay);
        }
    }

    /** 面向 ±x 的一块矩形（门的窄边）。 */
    public static void quadX(MatrixStack matrices, VertexConsumer vc, float x, float y0, float y1,
                             float z0, float z1, Uv uv, boolean positive, int light, int overlay) {
        MatrixStack.Entry entry = matrices.peek();
        float nx = positive ? 1.0F : -1.0F;
        if (positive) {
            vertex(entry, vc, x, y0, z0, uv.u0(), uv.v1(), nx, 0, 0, light, overlay);
            vertex(entry, vc, x, y0, z1, uv.u1(), uv.v1(), nx, 0, 0, light, overlay);
            vertex(entry, vc, x, y1, z1, uv.u1(), uv.v0(), nx, 0, 0, light, overlay);
            vertex(entry, vc, x, y1, z0, uv.u0(), uv.v0(), nx, 0, 0, light, overlay);
        } else {
            vertex(entry, vc, x, y0, z1, uv.u1(), uv.v1(), nx, 0, 0, light, overlay);
            vertex(entry, vc, x, y0, z0, uv.u0(), uv.v1(), nx, 0, 0, light, overlay);
            vertex(entry, vc, x, y1, z0, uv.u0(), uv.v0(), nx, 0, 0, light, overlay);
            vertex(entry, vc, x, y1, z1, uv.u1(), uv.v0(), nx, 0, 0, light, overlay);
        }
    }

    /** 面向 ±y 的一块矩形（板的上下檐）。 */
    public static void quadY(MatrixStack matrices, VertexConsumer vc, float y, float x0, float x1,
                             float z0, float z1, Uv uv, boolean positive, int light, int overlay) {
        MatrixStack.Entry entry = matrices.peek();
        float ny = positive ? 1.0F : -1.0F;
        if (positive) {
            vertex(entry, vc, x0, y, z1, uv.u0(), uv.v1(), 0, ny, 0, light, overlay);
            vertex(entry, vc, x1, y, z1, uv.u1(), uv.v1(), 0, ny, 0, light, overlay);
            vertex(entry, vc, x1, y, z0, uv.u1(), uv.v0(), 0, ny, 0, light, overlay);
            vertex(entry, vc, x0, y, z0, uv.u0(), uv.v0(), 0, ny, 0, light, overlay);
        } else {
            vertex(entry, vc, x0, y, z0, uv.u0(), uv.v1(), 0, ny, 0, light, overlay);
            vertex(entry, vc, x1, y, z0, uv.u1(), uv.v1(), 0, ny, 0, light, overlay);
            vertex(entry, vc, x1, y, z1, uv.u1(), uv.v0(), 0, ny, 0, light, overlay);
            vertex(entry, vc, x0, y, z1, uv.u0(), uv.v0(), 0, ny, 0, light, overlay);
        }
    }

    /**
     * 一块门板：六面按 box UV 各自贴图（正面 / 背面 / 左右窄边 / 上下檐）。
     *
     * <p>正面用 {@code uv.front()} 贴 {@code front} 那个 RenderLayer，背面用 {@code uv.back()}
     * 贴 {@code back}（厦门资源包给了单独的背面图），窄边取同一张图上的细条。
     */
    public static void plate(MatrixStack matrices, VertexConsumer front, VertexConsumer back,
                             float x0, float x1, float y0, float y1, float z0, float z1,
                             UvSet uv, int light, int overlay) {
        quadZ(matrices, front, x0, x1, y0, y1, z1, uv.front(), true, light, overlay);
        quadZ(matrices, back, x0, x1, y0, y1, z0, uv.back(), false, light, overlay);
        quadY(matrices, front, y1, x0, x1, z0, z1, uv.top(), true, light, overlay);
        quadY(matrices, front, y0, x0, x1, z0, z1, uv.bottom(), false, light, overlay);
        quadX(matrices, front, x0, y0, y1, z0, z1, uv.left(), false, light, overlay);
        quadX(matrices, front, x1, y0, y1, z0, z1, uv.right(), true, light, overlay);
    }

    /** 一个 6 面都贴同一张整图的小立方体（门灯、锁定牌用）。 */
    public static void cube(MatrixStack matrices, VertexConsumer vc,
                            float x0, float x1, float y0, float y1, float z0, float z1,
                            float u0, float u1, int light, int overlay) {
        Uv full = new Uv(u0, u1, 0.0F, 1.0F);
        quadZ(matrices, vc, x0, x1, y0, y1, z1, full, true, light, overlay);
        quadZ(matrices, vc, x0, x1, y0, y1, z0, full, false, light, overlay);
        quadX(matrices, vc, x0, y0, y1, z0, z1, full, false, light, overlay);
        quadX(matrices, vc, x1, y0, y1, z0, z1, full, true, light, overlay);
        quadY(matrices, vc, y0, x0, x1, z0, z1, full, false, light, overlay);
        quadY(matrices, vc, y1, x0, x1, z0, z1, full, true, light, overlay);
    }
}
