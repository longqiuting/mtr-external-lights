// 半高 APG 玻璃渲染器：MTR 的 RenderAPGGlass + 同一层作用域标记。
package dev.js.mtrheadlight.xiamen;

import mtr.render.RenderAPGGlass;
import mtr.render.RenderRouteBase;
import mtr.render.StoredMatrixTransformations;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;

/**
 * 半高 APG 玻璃的渲染器 —— 与 {@link XiamenPsdTopRenderer} 一样，只给 MTR 的
 * {@link RenderAPGGlass} 套一层作用域标记，画法一点没动。
 *
 * <p>玻璃和吊板共用 {@code RenderRouteBase}，所以「压栈 / 出栈」的位置、顺序、保证完全相同：
 * {@code getRenderType} 压栈 → 生成线路图 → {@code renderAdditional} 出栈。</p>
 */
public class XiamenApgGlassRenderer extends RenderAPGGlass {

    public XiamenApgGlassRenderer(final BlockEntityRenderDispatcher dispatcher) {
        super(dispatcher);
    }

    @Override
    protected RenderRouteBase.RenderType getRenderType(final BlockView world, final BlockPos pos,
                                                       final BlockState state) {
        XiamenRouteScope.push(true);
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
