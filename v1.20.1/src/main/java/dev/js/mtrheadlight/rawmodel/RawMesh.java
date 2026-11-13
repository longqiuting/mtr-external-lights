// 与「模型格式」无关的中间结构：一堆四边形 + 一张贴图。解析器产出它，渲染器只吃它。
package dev.js.mtrheadlight.rawmodel;

import net.minecraft.util.Identifier;

/**
 * 一份<b>与源格式无关</b>的网格：{@code texture} + 若干四边形。
 *
 * <h2>分工（这条约束请保持）</h2>
 * <pre>
 *   Blockbench JSON  ──RawModelJson.parse(...)──┐
 *   .obj / .ply / …  ──RawObjParser.parse(...)─┴─→  RawMesh  ──RawModelRenderer.render(...)──→ 屏幕
 * </pre>
 * <ul>
 *   <li><b>解析器</b>（{@link RawModelJson} 是第一个，将来想支持 {@code .obj} 只需要再写一个
 *       {@code RawObjParser}）负责：读原文件、按各自的旋转 / 轴向约定把顶点算到下面这套坐标里、
 *       把「这个面该多亮」算成 {@link Quad#shade()}、把格子级的自发光算成 {@link Quad#fullBright()}。</li>
 *   <li><b>渲染器</b>（{@link RawModelRenderer}）只认 {@code RawMesh}：不读 JSON、不认识
 *       {@code elements} / {@code faces} / {@code rotation}，也不知道 OBJ 的存在。加一种新素材格式
 *       不需要动渲染层一行。</li>
 * </ul>
 *
 * <h2>坐标与 UV 约定（所有解析器都必须遵守）</h2>
 * <ul>
 *   <li>顶点：模型空间，<b>1 格 = 16 单位</b>（与原版 block model 一致），原点在方块西北下角；</li>
 *   <li>uv：<b>0..1</b>，(0,0) 是贴图左上角；</li>
 *   <li>法线：模型空间单位向量；</li>
 *   <li>顶点顺序必须<b>绕序朝外</b>（从外面看是逆时针），渲染层按这个顺序直接往外写；</li>
 *   <li>{@code shade}：已经算好的明暗系数（原版 {@code "shade": true} 那套
 *       {@code down 0.5 / up 1.0 / 南北 0.8 / 东西 0.6}），{@code fullBright} 的四边形忽略它。</li>
 * </ul>
 */
public final class RawMesh {

    /**
     * 一个四边形。
     *
     * @param pos      4 个顶点，每个 {@code [x,y,z]}（模型空间 0..16）
     * @param uv       4 组 uv，每个 {@code [u,v]}（0..1），与 {@code pos} 一一对应
     * @param normal   外法线（单位向量）
     * @param shade    方向明暗系数（见类注释）
     * @param fullBright 自发光：lightmap 拉满、颜色不压暗（原版的 {@code "shade": false}）
     * @param group    源文件里的 {@code o} / {@code g} 组名（{@link RawModelJson} 恒为 {@code ""}）
     * @param paragraph 第几个 {@code o} / {@code g} <b>段落</b>（从 0 数；{@link RawModelJson} 恒为 0）。
     *                 同名组连续写两段时（Blockbench 允许重名，BRT 屏蔽门收纳机的
     *                 {@code o light} 就是两段）只有这个下标能把它们分开 —— 见
     *                 {@code RawObjParser.groupRuns}
     */
    public record Quad(float[][] pos, float[][] uv, float[] normal, float shade, boolean fullBright,
                       String group, int paragraph) {

        /**
         * 没有组名的五参数写法（{@link RawModelJson} 用它；组名兜底成空串）。
         *
         * <p>加这个构造器是为了让「给 {@code Quad} 加一个字段」不需要改动任何已有的解析器 ——
         * record 的规范构造器新增一个分量时，老的 5 参数调用点会全部编译失败。
         */
        public Quad(final float[][] pos, final float[][] uv, final float[] normal, final float shade,
                    final boolean fullBright) {
            this(pos, uv, normal, shade, fullBright, "", 0);
        }

        /** 有组名、没有段落信息的写法（段落兜底成 0）。 */
        public Quad(final float[][] pos, final float[][] uv, final float[] normal, final float shade,
                    final boolean fullBright, final String group) {
            this(pos, uv, normal, shade, fullBright, group, 0);
        }
    }


    private final Identifier texture;
    private final Quad[] quads;

    public RawMesh(final Identifier texture, final Quad[] quads) {
        this.texture = texture;
        this.quads = quads;
    }

    /** 直接喂给 {@code RenderLayer.getEntityCutoutNoCull(...)} 的贴图 id（带 {@code .png}）。 */
    public Identifier texture() {
        return this.texture;
    }

    public Quad[] quads() {
        return this.quads;
    }

    public int quadCount() {
        return this.quads.length;
    }

    /**
     * 模型空间包围盒：{@code [minX, minY, minZ, maxX, maxY, maxZ]}（0..16 单位）。
     *
     * <p>给「碰撞箱从几何推」用（{@code xiamen.eyecandy} 那 26 个物件就是按它算的）。
     * 空网格返回全 0。
     */
    public double[] bounds() {
        final double[] box = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE,
                -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (final Quad quad : this.quads) {
            for (final float[] p : quad.pos()) {
                for (int axis = 0; axis < 3; axis++) {
                    if (p[axis] < box[axis]) {
                        box[axis] = p[axis];
                    }
                    if (p[axis] > box[axis + 3]) {
                        box[axis + 3] = p[axis];
                    }
                }
            }
        }
        if (box[0] > box[3]) {
            return new double[6];
        }
        return box;
    }
}
