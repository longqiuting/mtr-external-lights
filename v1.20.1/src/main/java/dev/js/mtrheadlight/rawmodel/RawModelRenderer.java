// 渲染器：只吃 RawMesh（与源格式无关），一个矩阵画完所有四边形。
package dev.js.mtrheadlight.rawmodel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

/**
 * {@link RawMesh} 的绘制器：{@code matrices} 里已经摆好「方块 → 世界」的变换
 * （平移 + 朝向旋转），这里只负责把每个四边形的 4 个顶点按 1.20.1 的顶点格式写出去。
 *
 * <h2>它不认识任何源格式</h2>
 * <p>本类不读 JSON、不认识 {@code elements} / {@code faces} / {@code rotation}，也不知道有 OBJ 这回事
 * —— 解析交给 {@link RawModelJson}（将来要支持 {@code .obj} 就再加一个 {@code RawObjParser}，
 * 本类一行都不用改）。这里唯一依赖的约定见 {@link RawMesh} 的类注释：
 * 顶点是模型空间 0..16、uv 是 0..1、绕序朝外、{@code shade} 已算好、{@code fullBright} 表示自发光。
 *
 * <h2>顶点格式（1.20.1）</h2>
 * <p>1.20.1 的 {@code VertexConsumer} 没有裸坐标的 {@code quad(...)} 重载，只能按
 * {@code vertex(矩阵, x, y, z) → color → texture → overlay → light → normal → next} 的链式写法
 * 逐顶点写（和 {@code PsdQuads} 一致）。
 *
 * <h2>光照</h2>
 * <ul>
 *   <li>普通四边形：用渲染器传进来的 {@code light}（方块位置的天光 / 方块光），颜色乘 {@code shade}
 *       —— 也就是原版 {@code "shade": true} 的观感；</li>
 *   <li>{@code fullBright}：lightmap 强制 {@link LightmapTextureManager#MAX_LIGHT_COORDINATE}、
 *       颜色不压暗（灯组、屏幕、发光条）。</li>
 * </ul>
 */
public final class RawModelRenderer {

    /**
     * 贴图 → {@code RenderLayer} 缓存。
     *
     * <p>{@code RenderLayer.getEntityCutout(tex)} <b>每次调用都会新建一个 RenderLayer 对象</b>
     * （不是按贴图缓存），而 {@code VertexConsumerProvider.Immediate} 是按 RenderLayer 的<b>身份</b>
     * 建缓冲区的；每帧每台闸机新建一次，等于不停地往那张表里塞新键。这里按贴图缓存一份就够了 ——
     * RenderLayer 引用的是「贴图 id」而不是某个 GL 纹理对象，资源包重载后照样有效。
     */
    private static final Map<Identifier, RenderLayer> CUTOUT_LAYERS = new ConcurrentHashMap<>();

    private RawModelRenderer() {
    }

    /** 这份网格该用的渲染层：{@code entity_cutout}（贴图直绑、alpha 裁掉、背面剔除开着）。 */
    public static RenderLayer cutoutLayer(final RawMesh mesh) {
        return CUTOUT_LAYERS.computeIfAbsent(mesh.texture(), RenderLayer::getEntityCutout);
    }

    /**
     * 画一整份网格。
     *
     * @param matrices 已经含「方块位置 + 朝向」的矩阵栈（本方法不再改它）
     * @param consumer 目标 {@link VertexConsumer}（由 {@code RenderLayer.getEntityCutoutNoCull} 取）
     * @param mesh     {@link RawModelJson#get} 的结果，{@code null} 时直接返回
     * @param light    这个方块位置的打包光照
     * @param overlay  覆盖层（通常 {@code OverlayTexture.DEFAULT_UV}）
     */
    public static void render(final MatrixStack matrices, final VertexConsumer consumer,
                              final RawMesh mesh, final int light, final int overlay) {
        if (mesh == null || consumer == null) {
            return;
        }
        final MatrixStack.Entry entry = matrices.peek();
        for (final RawMesh.Quad quad : mesh.quads()) {
            final boolean bright = quad.fullBright();
            final int quadLight = bright ? LightmapTextureManager.MAX_LIGHT_COORDINATE : light;
            final float shade = bright ? 1.0F : quad.shade();
            final float[][] pos = quad.pos();
            final float[][] uv = quad.uv();
            final float[] n = quad.normal();
            for (int i = 0; i < 4; i++) {
                consumer.vertex(entry.getPositionMatrix(), pos[i][0] / 16.0F, pos[i][1] / 16.0F,
                                pos[i][2] / 16.0F)
                        .color(shade, shade, shade, 1.0F)
                        .texture(uv[i][0], uv[i][1])
                        .overlay(overlay)
                        .light(quadLight)
                        .normal(entry.getNormalMatrix(), n[0], n[1], n[2])
                        .next();
            }
        }
    }
}
