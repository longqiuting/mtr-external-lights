// 吊板渲染器：MTR 的 RenderPSDTop + 一层「这一段是我们的方块」的作用域标记。
package dev.js.mtrheadlight.xiamen;

import mtr.render.RenderPSDTop;
import mtr.render.RenderRouteBase;
import mtr.render.StoredMatrixTransformations;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;

/**
 * 吊板（全高 PSD 的 {@code y+2}）渲染器 —— <b>几何 / 站名 / 线路图 / 色带 / 方向箭头全部还是
 * MTR 的 {@link RenderPSDTop}</b>，本类只多两件事：
 *
 * <ol>
 *   <li>在 {@link #getRenderType} 里把 {@link XiamenRouteScope} 压栈（{@code ours = true}），
 *       表示“接下来这段渲染画的是本模组的吊板”；</li>
 *   <li>在 {@link #renderAdditional} 里出栈（{@code finally}，异常也不会漏）。</li>
 * </ol>
 *
 * <p>MTR 的 {@code RenderRouteBase#render} 是 final，这两个 protected 钩子是它留给我们唯一的
 * 位置，而且顺序保证「压栈 → 生成线路图纹理 → 出栈」（详见 {@link XiamenRouteScope} 的字节码注释）。
 * 于是 {@code RouteMapArtMixin} 只在 {@link XiamenRouteScope#active()} 时才接管位图，
 * <b>MTR 原版屏蔽门一块都不受影响</b>。</p>
 */
public class XiamenPsdTopRenderer extends RenderPSDTop {

    public XiamenPsdTopRenderer(final BlockEntityRenderDispatcher dispatcher) {
        super(dispatcher);
    }

    @Override
    protected RenderRouteBase.RenderType getRenderType(final BlockView world, final BlockPos pos,
                                                       final BlockState state) {
        XiamenRouteScope.push(true);
        // v7：把这一格方块的方向一起记下来（条带 / 终点三角的镜像规则由它决定）。
        XiamenRouteScope.arrow(XiamenRouteScope.arrowOf(state));
        return super.getRenderType(world, pos, state);
    }

    @Override
    protected void renderAdditional(final StoredMatrixTransformations storedMatrixTransformations,
                                    final long platformId, final BlockState state, final int light,
                                    final int overlay, final Direction facing, final int rh, final int rv) {
        try {
            super.renderAdditional(storedMatrixTransformations, platformId, state, light, overlay, facing, rh, rv);
        } finally {
            XiamenRouteScope.pop();
        }
    }
}
