// 方速滑动屏蔽门的方块实体渲染器：进度来自 MTR 方块实体的 open 值，绘制交给 FangsuPsdDraw。
package dev.js.mtrheadlight.xiamen.fangsu;

import dev.js.mtrheadlight.MtrHeadlightClient;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.world.World;

/**
 * 方速<b>滑动</b>屏蔽门（{@code fangsu_psd_screendoor_low/high_left|right}，4 个方块）的渲染器。
 *
 * <h2>开合进度</h2>
 * <p>{@code entity.progress(world.getTime(), tickDelta)}：目标值来自 MTR 的
 * {@code TileEntityPSDAPGDoorBase#getOpenValue()}（列车停稳后 {@code TrainServer#openDoors} 写进来的
 * 0..32），插值按<b>时间</b>推进（{@code MOVE_TICKS} tick 走完全程），
 * 详见 {@link FangsuPsdDoorBlock.FangsuPsdDoorEntity} 与 {@link FangsuPsdProgress}。
 *
 * <h2>几何 / 朝向 / 渲染层</h2>
 * <p>全部在 {@link FangsuPsdDraw}（端门用的是同一个方法，只是进度来源不同）。
 *
 * <p>上半段方块<b>不画</b>（整扇门由下半段一次画完，否则重合成 z-fighting），见
 * {@link FangsuPsdDraw#isUpperHalf}。
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
        if (FangsuPsdDraw.isUpperHalf(state)) {
            return;
        }
        // 开关门音效：用 open 值的边沿判定，只在客户端、只在画下段的那一次调用里做
        // （每扇门每一列一次）。全局开关在这里读好再传进去 —— 方块实体本身不引用客户端类
        // （它在专用服务端上也会被创建），见 FangsuPsdDoorEntity#tickDoorSound。
        FangsuPsdSoundsClient.play(entity.getPos(),
                entity.tickDoorSound(world, entity.getPos(), MtrHeadlightClient.config().fangsuDoorSound));
        final float progress = entity.progress(world == null ? 0L : world.getTime(), tickDelta);
        FangsuPsdDraw.draw(block.def(), progress, FangsuPsdDraw.facingOf(state),
                matrices, vertexConsumers, light, overlay);
    }
}
