package dev.js.mtrheadlight.mixin.mtr;

import dev.js.mtrheadlight.signal.SignalLinks;
import dev.js.mtrheadlight.signal.SignalSourceCapture;
import mtr.block.BlockNode;
import mtr.render.RenderSignalBase;
import net.minecraft.block.BlockState;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让「信号连接器」绑定的信号灯按<b>远处节点</b>或<b>另一盏信号灯</b>计算显示状态。
 *
 * <h2>MTR 原本怎么算（javap 证据，3.6.4.2 / 1.20.1）</h2>
 * <pre>
 * RenderSignalBase.render(T, float, MatrixStack, VertexConsumerProvider, int, int):
 *   78: getNodePos(BlockView, BlockPos, Direction) -> BlockPos   // 私有静态
 *   89: if (nodePos == null) return;                              // 找不到节点 → 不渲染
 *  110: for (i = 0; i &lt; 2; i++)                                   // 双面：正面一次、背面一次
 *  135:   dir = (i == 1) ? facing.getOpposite() : facing
 *  137:   getOccupiedAspect(nodePos, dir.asRotation() + 90f)      // 私有实例 (BlockPos, float) -> int
 * </pre>
 *
 * <p>也就是说：<b>信号灯与节点的关系纯靠方块位置扫描</b>，方块实体是空壳、没有任何状态。所以
 * 「这盏灯显示别的节点的信号」唯一干净的入口就是在 {@code getNodePos} 的 HEAD 把位置换掉；
 * 后面的 {@code getOccupiedAspect} 只拿这个 {@link BlockPos} 去查 {@code ClientData.RAILS} /
 * {@code ClientData.SIGNAL_BLOCKS}，节点多远都一样算。
 *
 * <h2>灯 → 灯</h2>
 * 后点的灯 B 要显示前点的灯 A 的状态。A 的状态 = 用 <b>A 的节点</b> 和 <b>A 的朝向</b> 算出来的
 * aspect，所以两步：
 * <ol>
 *   <li>{@code getNodePos} 对 B 返回「A 的节点」（A 自己绑了节点就用那个，否则照 MTR 的算法在 A
 *       周围扫一个）—— 否则 {@code render} 在第 89 行就 return 了，B 一动不动；</li>
 *   <li>{@code getOccupiedAspect} 的 HEAD：把角度从「B 的朝向」换成「A 的朝向」。角度是形参，
 *       用 {@link ModifyVariable} 换掉，<b>MTR 自己的函数体照常算</b> —— 不复制那段
 *       {@code ClientData} 遍历，也不去反射私有方法。正面/背面两次调用按相对角度（0°/180°）映射，
 *       所以单面灯不会被我们照成双面。</li>
 * </ol>
 *
 * <p>循环由服务端建链时拒绝（{@code SignalLinkStore#putLamp}），客户端
 * {@code SignalLinks#resolveSource} 再用访问集兜一层；这里不需要重入保护，因为
 * {@link ModifyVariable} 只换入参、不重入。
 *
 * <p>{@code remap = false}：MTR 的 jar 未 remap，方法名就是源码名。注入失败由 mixin 配置的
 * {@code required: false} + {@code defaultRequire: 0} 兜底 —— 最差情况是信号灯退回 MTR 原行为，
 * 绝不会让客户端起不来。
 */
@Mixin(value = RenderSignalBase.class, remap = false)
public abstract class RenderSignalBaseMixin {

    /** 当前线程这一帧的「该抄谁」，见 {@link SignalSourceCapture}。 */
    private static final ThreadLocal<SignalSourceCapture> LTE_SOURCE = new ThreadLocal<>();

    @Inject(method = "getNodePos", at = @At("HEAD"), cancellable = true, remap = false)
    private static void lte$useLinkedNode(final BlockView view, final BlockPos signalPos,
                                          final Direction facing,
                                          final CallbackInfoReturnable<BlockPos> cir) {
        try {
            // 1) 灯 → 灯：换成源灯的节点
            final BlockPos source = SignalLinks.resolveSource(view, signalPos);
            if (source != null) {
                final Direction sourceFacing = lte$facingOf(view, source, facing);
                BlockPos node = SignalLinks.nodeFor(view, source);
                if (node == null) {
                    // 源灯没绑节点 → 用它自己的位置扫描（和 MTR 对源灯做的事完全一致）
                    node = lte$scanNode(view, source, sourceFacing);
                }
                LTE_SOURCE.set(new SignalSourceCapture(facing, sourceFacing, node));
                if (node != null) {
                    cir.setReturnValue(node);
                }
                return;
            }

            // 2) 灯 → 节点
            LTE_SOURCE.remove();
            final BlockPos linked = SignalLinks.nodeFor(view, signalPos);
            if (linked != null) {
                cir.setReturnValue(linked);
            }
        } catch (Throwable ignored) {
            // 绑定表异常时退回 MTR 自己的扫描。
            LTE_SOURCE.remove();
        }
    }

    /**
     * {@code getOccupiedAspect(BlockPos node, float angle)} 的第二个形参（LVT 2：0=this, 1=node）。
     *
     * <p>只换角度、不换节点：节点已经由 {@link #lte$useLinkedNode} 换成源灯的节点了。这样 MTR
     * 的 {@code similarFacing} / {@code SIGNAL_BLOCKS} / {@code OCCUPIED_RAILS} 那套判定一字不改。
     */
    @ModifyVariable(method = "getOccupiedAspect", at = @At("HEAD"),
            argsOnly = true, index = 2, remap = false)
    private float lte$syncAngle(final float angle) {
        final SignalSourceCapture capture = LTE_SOURCE.get();
        if (capture == null || capture.node == null) {
            return angle;
        }
        try {
            final float base = capture.facing.asRotation() + 90.0F;
            final float relative = lte$normalize(angle - base);
            final float sourceAngle = capture.sourceFacing.asRotation() + 90.0F;
            // 背面那一次（相对 180°）映射到源灯的背面，正面映射到正面。
            return Math.abs(relative) > 90.0F ? sourceAngle + 180.0F : sourceAngle;
        } catch (Throwable ignored) {
            return angle;
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 源灯方块自己的朝向；拿不到（方块被拆 / 不是水平朝向方块）就用调用方的。 */
    private static Direction lte$facingOf(final BlockView view, final BlockPos pos,
                                          final Direction fallback) {
        try {
            final BlockState state = view.getBlockState(pos);
            if (state.contains(Properties.HORIZONTAL_FACING)) {
                return state.get(Properties.HORIZONTAL_FACING);
            }
        } catch (Throwable ignored) {
            // 方块视图不可用 → fallback
        }
        return fallback;
    }

    /**
     * MTR {@code getNodePos} 的位置扫描，逐字照搬（javap 3.6.4.2）：
     * <pre>
     * int[] d = {0, 1, -1, 2, -2, 3, -3, 4, -4};
     * for (dx : d) for (dz : d) for (up = -5; up &lt;= 0; up++)
     *     p = pos.up(up).offset(facing.getOpposite(), dz).offset(facing, dx);
     *     if (world.getBlockState(p).getBlock() instanceof BlockNode) return p;
     * </pre>
     *
     * <p>只在「源灯自己没绑节点」时用，和 MTR 对源灯本体做的事等价；扫描失败（返回 {@code null}）
     * 就代表源灯自己也亮不了，B 跟着退回自己的行为。
     */
    private static BlockPos lte$scanNode(final BlockView view, final BlockPos pos, final Direction facing) {
        final int[] d = {0, 1, -1, 2, -2, 3, -3, 4, -4};
        for (final int dx : d) {
            for (final int dz : d) {
                for (int up = -5; up <= 0; up++) {
                    final BlockPos p = pos.up(up)
                            .offset(facing.getOpposite(), dz)
                            .offset(facing, dx);
                    if (BlockNode.class.isInstance(view.getBlockState(p).getBlock())) {
                        return p;
                    }
                }
            }
        }
        return null;
    }

    /** 归一到 (-180, 180]。 */
    private static float lte$normalize(final float degrees) {
        float d = degrees % 360.0F;
        if (d > 180.0F) {
            d -= 360.0F;
        }
        if (d <= -180.0F) {
            d += 360.0F;
        }
        return d;
    }
}
