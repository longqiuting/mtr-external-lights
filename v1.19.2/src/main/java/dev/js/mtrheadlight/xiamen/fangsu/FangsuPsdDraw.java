// 画一扇方速门（端门 / 滑动门共用）：静态部分 + 按进度变换的门扇。
package dev.js.mtrheadlight.xiamen.fangsu;

import java.util.List;
import java.util.Map;

import dev.js.mtrheadlight.rawmodel.RawMesh;
import dev.js.mtrheadlight.rawmodel.RawModelRenderer;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.Direction;
import dev.js.mtrheadlight.clientgui.RotationAxis;

/**
 * 「把一个 {@link FangsuPsdDef} 画出来」的唯一实现。
 *
 * <h2>为什么单独一个类</h2>
 * <p>这一版之后有两套方块实体在画同一套几何：自动滑动门
 * （{@link FangsuPsdRenderer}，方块实体是 MTR 的 {@code TileEntityPSDAPGDoorBase}）和
 * 端门（{@link FangsuPsdEndDoorRenderer}，方块实体是我们自己的）。两者的<b>绘制</b>必须一模一样，
 * 差别只在「进度从哪来」，所以绘制放在这里、进度由调用方给。
 *
 * <h2>画什么</h2>
 * <p>方块实例 → {@link FangsuPsdDef} → {@link FangsuPsdBlocks#meshes} → {@code o} 组 →
 * 按材质拆开、各带一张贴图的 {@link RawMesh} 列表。三部分：
 * <ol>
 *   <li><b>静态部分</b>（{@code subModels.main}，端门的门框）：原样画；</li>
 *   <li><b>门扇</b>（{@code subModels.door} / 滑动门的 {@code subModel}）：按开合进度 {@code p ∈ [0,1]}
 *       变换后再画 —— 旋转式绕 {@code doorPos} 转 {@code angle × p}；滑动式沿 x 平移 {@code step × p}；</li>
 *   <li><b>上半段</b>：{@code HALF == UPPER} 的那个方块<b>不画</b> —— 整扇门（含 3 格高的变体）都由
 *       下半段的方块实体一次画完，和 MTR 自己的 {@code RenderPSDAPGDoor} 同一个做法
 *       （否则两段各画一次，重合成 z-fighting）。见 {@link #isUpperHalf}。</li>
 * </ol>
 *
 * <h2>朝向</h2>
 * <p>和 {@code eyecandy} 一模一样：绕方块中心 {@code (0.5, ·, 0.5)} 转 {@code NEGATIVE_Y}
 * （blockstate 的 {@code y} 是俯视顺时针，JOML 正角是逆时针，所以取负）。
 * 这套映射正好等于方速自己的 {@code rotateY(-toRadians(facing.getHorizontal()) + PI)}
 * （north 0 / east −90 / south 180 / west 90），也就是<b>模型</b>的朝向；
 * 碰撞盒要在此基础上再多转 180° 才对得上 —— 见 {@code FangsuPsdDoorBlock#shapeQuarterTurns}。
 *
 * <h2>渲染层</h2>
 * <p>{@code RenderLayer.getEntityCutout(贴图)}（按贴图缓存，见 {@link RawModelRenderer#cutoutLayer}）：
 * 贴图直绑、不进方块图集（blockstate 模型是空壳），alpha &lt; 0.1 裁掉。灯带那份网格的四边形在
 * 注册期被标成 {@code fullBright}（贴图名 {@code ltg.png}），画出来是亮的。
 */
final class FangsuPsdDraw {

    private FangsuPsdDraw() {
    }

    /** 这个是上半个方块吗（上半个不画，整扇门由下半个画完）。 */
    static boolean isUpperHalf(final BlockState state) {
        return state != null && state.contains(Properties.DOUBLE_BLOCK_HALF)
                && state.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER;
    }

    /** 当前方块该用哪个朝向（属性存在就取，不存在兜底成北）。 */
    static Direction facingOf(final BlockState state) {
        return state != null && state.contains(Properties.HORIZONTAL_FACING)
                ? state.get(Properties.HORIZONTAL_FACING) : Direction.NORTH;
    }

    /**
     * 画一整扇门。
     *
     * @param def      这个方块的描述
     * @param progress 开合进度 0..1
     * @param facing   朝向
     */
    static void draw(final FangsuPsdDef def, final float progress, final Direction facing,
                     final MatrixStack matrices, final VertexConsumerProvider vertexConsumers,
                     final int light, final int overlay) {
        final Map<String, List<RawMesh>> meshes = FangsuPsdBlocks.meshes(def);
        if (meshes == null || meshes.isEmpty()) {
            return;
        }
        matrices.push();
        try {
            if (facing != Direction.NORTH) {
                matrices.translate(0.5F, 0.0F, 0.5F);
                matrices.multiply(RotationAxis.NEGATIVE_Y.rotationDegrees(degreesOf(facing)));
                matrices.translate(-0.5F, 0.0F, -0.5F);
            }
            // 静态部分（门框）。
            if (def.staticGroup() != null) {
                drawGroup(matrices, vertexConsumers, meshes.get(def.staticGroup()), light, overlay);
            }
            // 门扇：每扇一个变换。
            for (final FangsuPsdDef.Motion motion : def.motions()) {
                matrices.push();
                try {
                    if (motion.rotating()) {
                        final float[] pivot = motion.pivot();
                        matrices.translate(pivot[0] / 16.0F, pivot[1] / 16.0F, pivot[2] / 16.0F);
                        matrices.multiply(RotationAxis.POSITIVE_Y.rotation(motion.angle() * progress));
                        matrices.translate(-pivot[0] / 16.0F, -pivot[1] / 16.0F, -pivot[2] / 16.0F);
                    } else if (motion.step() != 0.0F) {
                        matrices.translate(motion.step() * progress, 0.0F, 0.0F);
                    }
                    drawGroup(matrices, vertexConsumers, meshes.get(motion.group()), light, overlay);
                } finally {
                    matrices.pop();
                }
            }
        } finally {
            matrices.pop();
        }
    }

    /** 画一组（同一 {@code o} 组、按材质拆开的多份网格，各带自己的贴图）。 */
    static void drawGroup(final MatrixStack matrices, final VertexConsumerProvider vertexConsumers,
                          final List<RawMesh> meshes, final int light, final int overlay) {
        if (meshes == null) {
            return;
        }
        for (final RawMesh mesh : meshes) {
            final RenderLayer layer = RawModelRenderer.cutoutLayer(mesh);
            final VertexConsumer consumer = vertexConsumers.getBuffer(layer);
            RawModelRenderer.render(matrices, consumer, mesh, light, overlay);
        }
    }

    /** facing → 俯视顺时针角度（{@code north=0 / east=90 / south=180 / west=270}）。 */
    private static float degreesOf(final Direction facing) {
        return switch (facing) {
            case EAST -> 90.0F;
            case SOUTH -> 180.0F;
            case WEST -> 270.0F;
            default -> 0.0F;
        };
    }
}
