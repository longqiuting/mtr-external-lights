// 贴墙标识的渲染器：就是 MTR 的那个，唯一多的动作是「这一帧画的是我们哪一张牌子」。
package dev.js.mtrheadlight.xiamen.sign;

import mtr.render.RenderStationNameTiled;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;
import net.minecraft.client.util.math.MatrixStack;

/**
 * {@code mtrlte:wall_sign_*} 的方块实体渲染器 —— <b>MTR 的 {@code RenderStationNameTiled}，原样</b>。
 *
 * <p>这里没有一行绘制代码：构造器把 {@code showLogo} 传 {@code true}（MTR 给
 * {@code mtr:station_name_entrance} 挂的就是这个），{@code render} 里也只做
 * {@link WallSignLogos#push(String)} / {@link WallSignLogos#pop()} 的配对，然后
 * {@code super.render(...)}。所以：
 * <ul>
 *   <li><b>放置 / 朝向 / 支撑</b>：{@code BlockStationNameEntrance#getPlacementState} /
 *       {@code canPlaceAt} / {@code getStateForNeighborUpdate}（方块继承来的，一行没重写）；</li>
 *   <li><b>刷子切形态</b>：{@code BlockStationNameEntrance#onUse}（{@code STYLE.cycle()}）；</li>
 *   <li><b>站名 / 字号 / 字色 / 相邻连接</b>：MTR 的 {@code drawStationName} →
 *       {@code getLength} / {@code getStationNameEntrance} / {@code scheduleRender}；</li>
 *   <li><b>落笔位置</b>：方块实体的 {@code yOffset = 0} / {@code zOffset = 0.00625}
 *       （{@link WallSignBlockEntity}，照 MTR 出入口款）；</li>
 *   <li><b>唯一的差别</b>：logo 那张图。MTR 在 {@code drawResource} 里写死取
 *       {@code mtr:textures/block/sign/logo.png}，{@link dev.js.mtrheadlight.mixin.mtr.StationNameEntranceLogoMixin}
 *       在本渲染器压了品牌时改成取 {@code mtrlte:textures/block/<brand>_logo.png}。</li>
 * </ul>
 */
public class WallSignRenderer extends RenderStationNameTiled<WallSignBlockEntity> {

    public WallSignRenderer(final BlockEntityRenderDispatcher dispatcher) {
        // 与 MTRClient 给 mtr:station_name_entrance 注册的完全同一个构造参数。
        super(dispatcher, true);
    }

    @Override
    public void render(final WallSignBlockEntity entity, final float tickDelta, final MatrixStack matrices,
                       final VertexConsumerProvider vertexConsumers, final int light, final int overlay) {
        WallSignLogos.push(brandOf(entity));
        try {
            super.render(entity, tickDelta, matrices, vertexConsumers, light, overlay);
        } finally {
            WallSignLogos.pop();
        }
    }

    /** 这张牌子属于哪个品牌（{@link WallSignBlock#logo()}：{@code xiamen} / {@code jiaomei} / {@code lte}）。 */
    private static String brandOf(final WallSignBlockEntity entity) {
        if (entity == null) {
            return null;
        }
        try {
            final BlockState state = entity.getCachedState();
            return state != null && state.getBlock() instanceof WallSignBlock sign ? sign.logo() : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
