// 闸机的渲染器：本体按 OPEN 挑 rawmodels 里的 *_open / *_closed，侧壳画 *_side，都直接画。
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
import net.minecraft.util.math.Vec3f;
import net.minecraft.world.World;

/**
 * 厦门 / BRT 闸机（4 个本体 + 2 款侧壳，共 6 个方块共用）的方块实体渲染器。
 *
 * <h2>画什么</h2>
 * <ul>
 *   <li><b>本体</b>：{@code open=closed}（以及刚落下的闸机）→ {@code rawmodels/ticket_barrier_closed.json} /
 *       {@code brt_barrier_closed.json}；{@code open=open} 与 {@code open=open_concessionary} → 对应的
 *       {@code *_open.json} —— {@link mtr.data.TicketSystem.EnumTicketBarrierOpen#isOpen()} 两者都是
 *       {@code true}，优惠票（儿童 / 老人卡）开的门和普通票是同一副样子，这是用户点名要的；</li>
 *   <li><b>侧壳</b>：{@code rawmodels/ticket_barrier_side.json} / {@code brt_barrier_side.json}，
 *       不分开关（它就是一只柜子）。</li>
 * </ul>
 * 用哪一款（地铁 / BRT）由方块实例上的 {@link XiamenTicketGate#rawModel()} /
 * {@link XiamenTicketGateSide#rawModel()} 决定。
 *
 * <h2>侧壳为什么要多平移一格</h2>
 * <p>素材的侧壳是在<b>本体的局部坐标系</b>里做的（柜体在 {@code x -4..0}，也就是本体西边那一格）。
 * 侧壳是它自己那一格的方块实体，BER 的原点在它自己的西北下角，所以要把模型沿建模的 +x 挪回本体那一格
 * 才对得上：{@code matrices.translate(1, 0, 0)}，而且必须在<b>朝向旋转之后</b>再挪 ——
 * 旋转是绕本格中心转的，先挪后转等于绕另一格的中心转，四个朝向里只有一个碰巧对。
 * 挪完之后（{@code R(x̂) = -sideOf(facing)}）两个柜体贴着脸，挡杆正好落在两条柜子中间。
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
 * <b>转 -n 度</b>（右手定则），所以这里是 {@code rotationDegrees(-y)}。侧壳用同一个映射（同向）。
 *
 * <h2>渲染层</h2>
 * <p>{@code RenderLayer.getEntityCutout(texture)}：贴图直绑、不进方块图集
 * （方块模型已经是一份 {@code {"elements": []}} 的空模型，图集里没有这些图），
 * alpha &lt; 0.1 的像素裁掉，硬边、不混合。
 *
 * <p><b>为什么是 {@code cutout} 而不是 {@code cutoutNoCull}</b>：素材里有若干「零厚度卡片」元素，
 * 它们把六个面都写上了 —— 其中不少元素<b>正反两面用的是不同的 uv 区域</b>。这些面共面，
 * 一旦关掉背面剔除，两张都会画进去，{@code GL_LEQUAL} 下后画的那张必胜，于是<b>两面都显示同一张图</b>；
 * 开着背面剔除时从哪边看就只画哪一面，与 Blockbench / 原版模型渲染这份 JSON 的效果一致。
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

        final String modelName;
        final boolean side;
        if (state.getBlock() instanceof XiamenTicketGate gate) {
            final boolean open = state.contains(BlockTicketBarrier.OPEN) && state.get(BlockTicketBarrier.OPEN).isOpen();
            modelName = gate.rawModel() + (open ? "_open" : "_closed");
            side = false;
        } else if (state.getBlock() instanceof XiamenTicketGateSide sideBlock) {
            modelName = sideBlock.rawModel();
            side = true;
        } else {
            return;
        }

        final RawMesh mesh = RawModelJson.get(RawModelJson.id(modelName));
        if (mesh == null) {
            return;
        }

        final VertexConsumer consumer = vertexConsumers.getBuffer(RawModelRenderer.cutoutLayer(mesh));

        matrices.push();
        // 绕方块中心（x=0.5, z=0.5）转，和原版 blockstate 的 "y" 完全同一个轴。
        matrices.translate(0.5D, 0.0D, 0.5D);
        matrices.multiply(Vec3f.POSITIVE_Y.getDegreesQuaternion(-rotationOf(state)));
        matrices.translate(-0.5D, 0.0D, -0.5D);
        if (side) {
            // 侧壳的素材是在本体坐标系里做的（本地 x -4..0）：沿建模 +x 挪一格回到本体那格。
            matrices.translate(1.0D, 0.0D, 0.0D);
        }
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
