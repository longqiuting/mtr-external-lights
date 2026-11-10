package dev.js.mtrheadlight.signal;

import mtr.client.ClientData;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Map;

/**
 * 手持「信号连接器」时，在附近的轨道节点上画方块轮廓（白），已选中的那个画青色。
 *
 * <h2>为什么是这套做法</h2>
 * <ul>
 *   <li><b>节点从 {@code ClientData.RAILS} 拿，不是扫方块。</b>{@code BlockNode} 的 collision/outline
 *       只有 1px 高（{@code BlockNode#getOutlineShape}：{@code IS_CONNECTED} 时
 *       {@code cuboid(0,0,0,16,1,16)}），而且弯道上的轨道是几何体不是方块，玩家很难瞄。MTR 客户端本来
 *       就维护着 {@code ClientData.RAILS}（键 = 节点方块坐标，值 = 「另一端节点 → Rail」），
 *       所以「附近有哪些节点」是一次 Map 遍历，不用读 32³ 个方块。服务端那条路用的是
 *       {@code RailwayData#getRailsMap()}（见 {@link SignalLinkNet#railsAt}），两边是同一张表的两个副本。</li>
 *   <li><b>每帧上限 {@value #MAX_NODES} 个、距离 {@value #RANGE} 格。</b>画的是 12 条线段，一个节点
 *       24 个顶点；200 个节点 = 4800 顶点，对一次 {@code RENDER_LAYER_LINES} 提交来说很小。超出上限就
 *       停止收集（不做全局排序：32 格内的节点本来就已经很少）。</li>
 *   <li><b>自己 draw，不等别人。</b>世界渲染到 {@code AFTER_TRANSLUCENT} 时主轴已经画完，
 *       往 {@code VertexConsumerProvider.Immediate} 里塞完必须立刻 {@code draw(LINES)}，
 *       否则要等到下一次 pass 才可见（而 { LINES } 不一定还有机会被画）。</li>
 * </ul>
 *
 * <p>1.20.1 的入口是 {@code WorldRenderer#drawBox(MatrixStack, VertexConsumer, x1..z2, r,g,b,a)}：
 * 它自己会写 {@code vertex → color → normal → next}（javap 已核对），所以配
 * {@code RenderLayer.getLines()}（POSITION_COLOR_NORMAL）不会因为缺 normal 抛
 * 「Not filled all elements」。
 */
@Environment(EnvType.CLIENT)
public final class SignalNodeHighlight {

    /** 显示半径（格）。和任务书一致：≤32。 */
    private static final double RANGE = 32.0D;
    private static final double RANGE_SQ = RANGE * RANGE;

    /** 每帧最多画多少个节点。 */
    private static final int MAX_NODES = 200;

    /** 轮廓略微外扩，压在方块边界上不和节点自己的贴图 z-fighting。 */
    private static final double INFLATE = 0.002D;

    private SignalNodeHighlight() {
    }

    /** 挂到 {@code WorldRenderEvents.AFTER_TRANSLUCENT}；异常一律不外抛（渲染报错会黑屏）。 */
    public static void render(final WorldRenderContext context) {
        try {
            renderInner(context);
        } catch (Throwable ignored) {
            // 节点标记是辅助显示，坏了就当没画。
        }
    }

    private static void renderInner(final WorldRenderContext context) {
        final MinecraftClient client = MinecraftClient.getInstance();
        final PlayerEntity player = client.player;
        if (player == null || client.world == null) {
            return;
        }
        final Item linker = LteCoreItems.signalLinker();
        if (linker == null) {
            return;
        }
        final ItemStack main = player.getMainHandStack();
        final ItemStack off = player.getOffHandStack();
        final boolean holdingMain = main.isOf(linker);
        if (!holdingMain && !off.isOf(linker)) {
            return;
        }

        final MatrixStack matrices = context.matrixStack();
        if (matrices == null) {
            return;
        }
        final Vec3d camera = context.camera().getPos();

        // 已选节点（第一次右键进来的那个）单独用青色，避免和白框混在一起。
        final BlockPos selected = SignalLinkItem.selectedNode(holdingMain ? main : off);

        final VertexConsumerProvider.Immediate buffers =
                client.getBufferBuilders().getEntityVertexConsumers();
        final VertexConsumer lines = buffers.getBuffer(RenderLayer.getLines());

        matrices.push();
        // 顶点用世界坐标，相机平移抵消掉（与 BeamGlow/ProtoBeamCone 同一套做法）。
        matrices.translate(-camera.x, -camera.y, -camera.z);
        try {
            int drawn = drawNodes(matrices, lines, player, selected);
            if (drawn > 0) {
                buffers.draw(RenderLayer.getLines());
            }
        } finally {
            matrices.pop();
        }
    }

    /** @return 实际画出的节点数。 */
    private static int drawNodes(final MatrixStack matrices, final VertexConsumer lines,
                                 final PlayerEntity player, final BlockPos selected) {
        final Vec3d eye = player.getPos();
        int drawn = 0;
        // 未 remap 的 MTR jar 里这个字段的泛型形参是 intermediary 名（class_2338），
        // 直接按 Map<BlockPos, …> 用会让 javac 去解析那个不存在的类，所以只看键集合。
        final Map<?, ?> rails = (Map<?, ?>) ClientData.RAILS;
        if (rails == null) {
            return 0;
        }
        for (final Object key : rails.keySet()) {
            if (!(key instanceof BlockPos node)) {
                continue;
            }
            if (node.getSquaredDistance(eye.x, eye.y, eye.z) > RANGE_SQ) {
                continue;
            }
            if (node.equals(selected)) {
                box(matrices, lines, node, 0.25F, 0.90F, 1.00F, 0.90F);
            } else {
                box(matrices, lines, node, 1.00F, 1.00F, 1.00F, 0.55F);
            }
            if (++drawn >= MAX_NODES) {
                break;
            }
        }
        return drawn;
    }

    private static void box(final MatrixStack matrices, final VertexConsumer lines, final BlockPos pos,
                            final float red, final float green, final float blue, final float alpha) {
        final double x = pos.getX();
        final double y = pos.getY();
        final double z = pos.getZ();
        WorldRenderer.drawBox(matrices, lines,
                x - INFLATE, y - INFLATE, z - INFLATE,
                x + 1.0D + INFLATE, y + 1.0D + INFLATE, z + 1.0D + INFLATE,
                red, green, blue, alpha);
    }
}
