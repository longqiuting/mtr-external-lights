// eyecandy 的渲染器：按方块实例上的描述取 OBJ 网格，按 facing 绕方块中心转 −blockRotation 度后画出。
package dev.js.mtrheadlight.xiamen.eyecandy;

import dev.js.mtrheadlight.rawmodel.RawMesh;
import dev.js.mtrheadlight.rawmodel.RawModelRenderer;
import net.minecraft.block.BlockState;
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
 * 全部 eyecandy 共用的方块实体渲染器。
 *
 * <h2>画什么</h2>
 * <p>方块实例上的 {@link EyecandyEntry} → {@link EyecandyBlocks#mesh} → {@code assets/mtrlte/eyecandies/*.obj}
 * 解析出来的 {@link RawMesh}。{@code scale / rotation / translation} 已经在解析期烘进顶点
 * （见 {@code RawObjParser}），BER 的 {@code matrices} 原点就是方块西北下角，网格本身在模型空间
 * 0..16 里摆好了。
 *
 * <h2>警示灯（BRT 屏蔽门收纳机那两件）</h2>
 * <p>{@code xiamen_brt_psd_left / _right} 的 obj 里有<b>两段位置重合、只差 uv</b> 的 {@code o light}
 * 几何（深色 = 熄 / 浅色 = 亮）。这两件不画整份网格，而是：
 * <ol>
 *   <li>{@link EyecandyBlocks#baseMesh} —— 底座（去掉所有灯组），普通光照；</li>
 *   <li>{@link EyecandyBlocks#lightMesh} —— <b>只画其中一盏</b>，亮的那份走
 *       {@code emissive = true}（lightmap 满亮、不压暗），灭的那份走普通光照。</li>
 * </ol>
 * 亮 / 灭由方块实体的状态机给（{@link EyecandyBlockEntity#isLit()}）：开门闪三下后常亮、
 * 关门闪三下后熄灭。两段灯几何必须<b>只画一段</b>，否则重合面会 z-fighting 出花斑。
 *
 * <h2>朝向：59 件全部跟着 {@code facing} 转</h2>
 * <p>每一件都有 {@link EyecandyBlock#FACING}（放置时按玩家朝向定，见
 * {@link EyecandyBlock#getPlacementState}），这里按 {@code facing} 绕方块中心
 * （{@code 0.5, 0, 0.5}）转 −{@link EyecandyBlock#blockRotation} 度 —— 和
 * {@link EyecandyBlock#shapeFor} 转碰撞盒用的是<b>同一个角度</b>（同一个 {@code (x, z) → (16 − z, x)}
 * 方向），否则模型转了盒子不转。{@code facing=north} = 模型原始朝向 = 0°，矩阵一次都不动。
 *
 * <h2>渲染层</h2>
 * <p>{@code RenderLayer.getEntityCutout(texture)}（{@link RawModelRenderer#cutoutLayer} 按贴图缓存了
 * RenderLayer 实例）：贴图直绑、不进方块图集（方块的 blockstate 模型是 {@code mtrlte:block/empty}，
 * 图集里没有这些图），alpha &lt; 0.1 裁掉，背面剔除开着 —— OBJ 的绕序是朝外的，
 * 圆柱 / 薄片都靠剔除两面各画一次才不会 z-fighting。
 *
 * <h2>光照</h2>
 * <p>用 BER 传进来的 {@code light}（方块位置的天光 / 方块光），颜色乘解析期算好的方向明暗
 * （原版那套 down 0.5 / up 1.0 / 南北 0.8 / 东西 0.6）。OBJ 没有自发光信息，所以没有 fullBright 的面，
 * 唯一的例外是点亮的那盏警示灯（{@code emissive}）。
 *
 * <h2>远处 / 面数</h2>
 * <p>共享单车那种一个模型 3446 个面、60 个加起来的量级对 BER 来说没问题（闸机那份 JSON 也是几千个面）。
 * 网格按 obj id 缓存，只解析一次；警示灯的拆分（底座 / 亮 / 灭）也只在第一次画到时算一次。
 */
public class EyecandyRenderer implements BlockEntityRenderer<EyecandyBlockEntity> {

    public EyecandyRenderer(final BlockEntityRendererFactory.Context context) {
    }

    @Override
    public void render(final EyecandyBlockEntity entity, final float tickDelta, final MatrixStack matrices,
                       final VertexConsumerProvider vertexConsumers, final int light, final int overlay) {
        final World world = entity.getWorld();
        final BlockState state = world != null ? world.getBlockState(entity.getPos()) : entity.getCachedState();
        if (!(state.getBlock() instanceof EyecandyBlock block)) {
            return;
        }
        final EyecandyEntry entry = block.entry();
        // 警示灯那两件：底座 + 一盏灯分开画；其余 57 件就是整份网格。
        final boolean warningLight = block.hasWarningLight();
        final RawMesh base = warningLight ? EyecandyBlocks.baseMesh(entry) : EyecandyBlocks.mesh(entry);
        if (base == null) {
            return;
        }
        final boolean lit = warningLight && entity.isLit();
        final RawMesh lightPart = warningLight ? EyecandyBlocks.lightMesh(entry, lit) : null;
        final RenderLayer layer = RawModelRenderer.cutoutLayer(base);
        final VertexConsumer consumer = vertexConsumers.getBuffer(layer);
        // 全部物件都有 facing（兜底 NORTH 只防「状态里没这个属性」的老数据）。
        final Direction facing = state.contains(EyecandyBlock.FACING)
                ? state.get(EyecandyBlock.FACING) : Direction.NORTH;
        final int degrees = EyecandyBlock.blockRotation(facing);
        if (degrees != 0) {
            matrices.push();
            matrices.translate(0.5F, 0.0F, 0.5F);
            matrices.multiply(RotationAxis.NEGATIVE_Y.rotationDegrees(degrees));
            matrices.translate(-0.5F, 0.0F, -0.5F);
        }
        try {
            RawModelRenderer.render(matrices, consumer, base, light, overlay);
            if (lightPart != null) {
                // 点亮的那一段取满亮 lightmap；熄灭的那一段就是一块深色塑料，走普通光照。
                RawModelRenderer.render(matrices, consumer, lightPart, light, overlay, lit);
            }
        } finally {
            if (degrees != 0) {
                matrices.pop();
            }
        }
    }
}
