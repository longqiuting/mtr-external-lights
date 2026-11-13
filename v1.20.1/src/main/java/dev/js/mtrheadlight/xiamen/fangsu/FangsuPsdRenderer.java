// 方速屏蔽门的方块实体渲染器：画 OBJ，按 o 组把门扇单独做旋转 / 平移，进度由方块实体插值。
package dev.js.mtrheadlight.xiamen.fangsu;

import java.util.List;
import java.util.Map;

import dev.js.mtrheadlight.rawmodel.RawMesh;
import dev.js.mtrheadlight.rawmodel.RawModelRenderer;
import mtr.block.BlockPSDAPGDoorBase;
import mtr.block.IBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.world.World;

/**
 * 方速屏蔽门的渲染器（一个方块实体类型，14 个方块共用）。
 *
 * <h2>画什么</h2>
 * <p>方块实例 → {@link FangsuPsdDef} → {@link FangsuPsdBlocks#meshes} → {@code o} 组 →
 * 按材质拆开、各带一张贴图的 {@link RawMesh} 列表。三部分：
 * <ol>
 *   <li><b>静态部分</b>（{@code subModels.main}，端门的门框）：原样画；</li>
 *   <li><b>门扇</b>（{@code subModels.door} / 滑动门的 {@code subModel}）：按开合进度
 *       {@code p ∈ [0,1]} 变换后再画 ——
 *       <ul>
 *         <li>旋转式（端门）：绕竖直轴（{@code doorPos} 的 x/z，模型单位）转 {@code angle * p} 弧度，
 *              其中 {@code angle} 已经从素材的 {@code doorAngle: ±1} 换算成 ±π/2（±90°，见
 *              {@link FangsuPsdDef.Motion#angle()}）。
 *             矩阵顺序是「先就地转，再按朝向转」：{@code translate(pivot) → rotate → translate(-pivot)}
 *             压在 {@code FACING} 那一层<b>里面</b>，所以门扇是在方块自己的坐标系里开合的；</li>
 *         <li>滑动式：沿 x 平移 {@code step * p} 格（{@code step} 读入时已按方速的 {@code -step} 取负，
 *              所以这里直接正着乘）。</li>
 *       </ul></li>
 *   <li><b>上半段</b>：{@code HALF == TOP} 的那个方块<b>不画</b> —— 整扇门（含 3 格高的变体）都由
 *       下半段的方块实体一次画完，和 MTR 自己的 {@code RenderPSDAPGDoor} 同一个做法
 *       （否则两段各画一次，重合成 z-fighting）。</li>
 * </ol>
 *
 * <h2>开合进度</h2>
 * <p>{@code entity.progress(world.getTime() + tickDelta)}：目标值来自 MTR 的
 * {@code TileEntityPSDAPGDoorBase#getOpenValue()}（列车停稳后 {@code TrainServer#openDoors} 写进来的
 * 0..32），插值在方块实体里按<b>时间</b>推进（{@code MOVE_TICKS} tick 走完全程），
 * 详见 {@link FangsuPsdDoorBlock.FangsuPsdDoorEntity}。
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
public class FangsuPsdRenderer implements BlockEntityRenderer<FangsuPsdDoorBlock.FangsuPsdDoorEntity> {

    public FangsuPsdRenderer(final BlockEntityRendererFactory.Context context) {
    }

    @Override
    public void render(final FangsuPsdDoorBlock.FangsuPsdDoorEntity entity, final float tickDelta,
                       final MatrixStack matrices, final VertexConsumerProvider vertexConsumers,
                       final int light, final int overlay) {
        final World world = entity.getWorld();
        final BlockState state = world != null ? world.getBlockState(entity.getPos()) : entity.getCachedState();
        if (!(state.getBlock() instanceof FangsuPsdDoorBlock block)) {
            return;
        }
        // 上半段不画（整扇门都由下半段画完）。
        if (IBlock.getStatePropertySafe(state, BlockPSDAPGDoorBase.HALF) != DoubleBlockHalf.LOWER) {
            return;
        }
        final Map<String, List<RawMesh>> meshes = FangsuPsdBlocks.meshes(block.def());
        if (meshes.isEmpty()) {
            return;
        }

        final float progress = entity.progress(world == null ? 0L : world.getTime(), tickDelta);
        final Direction facing = IBlock.getStatePropertySafe(state, BlockPSDAPGDoorBase.FACING);

        matrices.push();
        try {
            if (facing != Direction.NORTH) {
                matrices.translate(0.5F, 0.0F, 0.5F);
                matrices.multiply(RotationAxis.NEGATIVE_Y.rotationDegrees(degreesOf(facing)));
                matrices.translate(-0.5F, 0.0F, -0.5F);
            }
            // 静态部分（门框）。
            final FangsuPsdDef def = block.def();
            if (def.staticGroup() != null) {
                draw(matrices, vertexConsumers, meshes.get(def.staticGroup()), light, overlay);
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
                    draw(matrices, vertexConsumers, meshes.get(motion.group()), light, overlay);
                } finally {
                    matrices.pop();
                }
            }
        } finally {
            matrices.pop();
        }
    }

    /** 画一组（同一 {@code o} 组、按材质拆开的多份网格，各带自己的贴图）。 */
    private static void draw(final MatrixStack matrices, final VertexConsumerProvider vertexConsumers,
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
