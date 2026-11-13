// 闸机的渲染器：按 OPEN 挑 rawmodels 里的 *_open / *_closed，直接画。
package dev.js.mtrheadlight.xiamen.ticket;

import dev.js.mtrheadlight.rawmodel.RawMesh;
import dev.js.mtrheadlight.rawmodel.RawModelJson;
import dev.js.mtrheadlight.rawmodel.RawModelRenderer;
import mtr.block.BlockTicketBarrier;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.world.World;

/**
 * 厦门 / BRT 闸机（4 个方块共用）的方块实体渲染器。
 *
 * <h2>画什么</h2>
 * <ul>
 *   <li>{@code open=closed}（以及刚落下的闸机）→ {@code rawmodels/ticket_barrier_closed.json} /
 *       {@code brt_barrier_closed.json}；</li>
 *   <li>{@code open=open} 与 {@code open=open_concessionary} → 对应的 {@code *_open.json}
 *       —— {@link mtr.data.TicketSystem.EnumTicketBarrierOpen#isOpen()} 两者都是 {@code true}，
 *       优惠票（儿童 / 老人卡）开的门和普通票是同一副样子，这是用户点名要的。</li>
 * </ul>
 * 用哪一款（地铁 / BRT）由方块实例上的 {@link XiamenTicketGate#rawModel()} 决定 ——
 * 进站和出站闸机外观完全一样，所以 4 个方块只有 2 套模型。
 *
 * <h2>状态从哪读</h2>
 * <p>从世界里的 {@code BlockState} 现读（不是方块实体里缓存的那份）：
 * {@code onEntityCollision} 里 {@code setBlockState(OPEN=…)} 之后，同一个 tick 内就会重画，
 * 门立刻动，不会晚一帧或者卡在旧状态。
 *
 * <h2>朝向</h2>
 * <p>完全照抄原来 blockstate 里的映射（{@code facing=north → y=0, east → 90, south → 180, west → 270}，
 * 与 MTR 自己的 {@code ticket_barrier_entrance_1.json} 一模一样），
 * 也就是 {@code y = FACING.getOpposite().asRotation()}。原版的 {@code "y": n} 等价于绕方块中心
 * <b>转 -n 度</b>（右手定则），所以这里是 {@code rotationDegrees(-y)}。
 *
 * <h2>渲染层</h2>
 * <p>{@code RenderLayer.getEntityCutout(texture)}：贴图直绑、不进方块图集
 * （方块模型已经是一份 {@code {"elements": []}} 的空模型，图集里没有这些图），
 * alpha &lt; 0.1 的像素裁掉，硬边、不混合。
 *
 * <p><b>为什么是 {@code cutout} 而不是 {@code cutoutNoCull}</b>：素材里有 12 个「零厚度卡片」元素，
 * 它们把六个面都写上了 —— 其中 ticket_barrier 的第 15 / 19 号元素<b>正反两面用的是不同的 uv
 * 区域</b>。这两张面共面（同一 z，数值核对过），一旦关掉背面剔除，两张都会画进去，
 * {@code GL_LEQUAL} 下后画的那张（south）必胜，于是<b>两面都显示同一张图</b>；开着背面剔除时
 * 从哪边看就只画哪一面，与 Blockbench / 原版模型渲染这张 JSON 的效果一致。
 */
public class XiamenTicketGateRenderer implements BlockEntityRenderer<XiamenTicketGateBlockEntity> {

    public XiamenTicketGateRenderer(final BlockEntityRendererFactory.Context context) {
    }

    @Override
    public void render(final XiamenTicketGateBlockEntity entity, final float tickDelta,
                       final MatrixStack matrices, final VertexConsumerProvider vertexConsumers,
                       final int light, final int overlay) {
        final World world = entity.getWorld();
        final BlockState state = world != null ? world.getBlockState(entity.getPos()) : entity.getCachedState();
        if (!(state.getBlock() instanceof XiamenTicketGate gate)) {
            return;
        }

        final boolean open = state.contains(BlockTicketBarrier.OPEN) && state.get(BlockTicketBarrier.OPEN).isOpen();
        final RawMesh mesh = RawModelJson.get(RawModelJson.id(gate.rawModel() + (open ? "_open" : "_closed")));
        if (mesh == null) {
            return;
        }

        final VertexConsumer consumer =
                vertexConsumers.getBuffer(RawModelRenderer.cutoutLayer(mesh));

        matrices.push();
        // 绕方块中心（x=0.5, z=0.5）转，和原版 blockstate 的 "y" 完全同一个轴。
        matrices.translate(0.5D, 0.0D, 0.5D);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-rotationOf(state)));
        matrices.translate(-0.5D, 0.0D, -0.5D);
        RawModelRenderer.render(matrices, consumer, mesh, light, overlay);
        matrices.pop();
    }

    /** 等价于旧 blockstate 里的 {@code "y"}（north 0 / east 90 / south 180 / west 270）。 */
    private static int rotationOf(final BlockState state) {
        final Property<Direction> facing = net.minecraft.block.HorizontalFacingBlock.FACING;
        if (!state.contains(facing)) {
            return 0;
        }
        return (int) state.get(facing).getOpposite().asRotation();
    }
}
