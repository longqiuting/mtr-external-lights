package dev.js.mtrheadlight.mixin.mtr;

import dev.js.mtrheadlight.signal.SignalLinks;
import mtr.render.RenderSignalBase;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让「信号连接器」绑定的信号灯按<b>远处节点</b>计算显示状态。
 *
 * <h2>MTR 原本怎么找节点（javap 证据，3.6.4.2 / 1.20.1）</h2>
 * <pre>
 * RenderSignalBase.render(T, float, MatrixStack, VertexConsumerProvider, int, int):
 *   78: getNodePos(BlockView, BlockPos, Direction) -> BlockPos   // 私有静态
 *   89: if (nodePos == null) return;                              // 找不到节点 → 不渲染
 *  137: getOccupiedAspect(nodePos, facing.asRotation() + 90f)     // 私有实例
 *
 * getNodePos(BlockView, BlockPos, Direction):                     // 私有静态
 *   int[] d = {0, 1, -1, 2, -2, 3, -3, 4, -4};
 *   for (dx : d) for (dz : d) for (int back = -5; back <= 0; back++)
 *       pos = signalPos.offset(facing.getOpposite(), dz).relative(facing, dx).offset(facing.getOpposite(), back);
 *       if (worldView.getBlockState(pos).getBlock() instanceof BlockNode) return pos;
 *   return null;
 * </pre>
 *
 * <p>也就是说：<b>信号灯与节点的关系纯靠方块位置扫描</b>，方块实体
 * （{@code TileEntitySignalLightXAspectY}）是空壳、没有任何状态。所以「远处的信号灯显示某个
 * 节点的信号」唯一干净的入口就是在 {@code getNodePos} 的 HEAD 把位置换掉：后面的
 * {@code getOccupiedAspect} 只拿这个 {@link BlockPos} 去查 {@code ClientData.RAILS} /
 * {@code ClientData.SIGNAL_BLOCKS}，节点多远都一样算。
 *
 * <p>{@code remap = false}：MTR 的 jar 未 remap，方法名就是源码名。{@code require} 由
 * mixin 配置的 {@code defaultRequire: 0} 兜底，MTR 换版本时静默失效（信号灯退回普通行为）。
 */
@Mixin(value = RenderSignalBase.class, remap = false)
public abstract class RenderSignalBaseMixin {

    @Inject(method = "getNodePos", at = @At("HEAD"), cancellable = true, remap = false)
    private static void lte$useLinkedNode(final BlockView view, final BlockPos signalPos,
                                          final Direction facing,
                                          final CallbackInfoReturnable<BlockPos> cir) {
        try {
            final BlockPos linked = SignalLinks.nodeFor(view, signalPos);
            if (linked != null) {
                cir.setReturnValue(linked);
            }
        } catch (Throwable ignored) {
            // 绑定表异常时退回 MTR 自己的扫描。
        }
    }
}
