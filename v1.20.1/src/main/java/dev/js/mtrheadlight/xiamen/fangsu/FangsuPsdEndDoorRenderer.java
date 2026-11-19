// 方速端门的方块实体渲染器：进度来自 blockstate 的 OPEN（客户端插值），绘制交给 FangsuPsdDraw。
package dev.js.mtrheadlight.xiamen.fangsu;

import dev.js.mtrheadlight.client.RenderDistance;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.world.World;

/**
 * 方速<b>端门</b>（{@code fangsu_psd_*duanmen_left|right}，10 个方块）的渲染器。
 *
 * <p>和 {@link FangsuPsdRenderer}（滑动门）只差一件事：<b>进度从哪来</b>。
 * 滑动门的进度来自 MTR 方块实体的 {@code open} 值（列车写的），端门来自方块状态
 * {@link FangsuPsdEndDoorBlock#OPEN}（玩家用驾驶钥匙改的，见 {@link FangsuPsdEndDoorBlock#onUse}）。
 * 几何、朝向、渲染层全部是同一个 {@link FangsuPsdDraw}。
 *
 * <p>上半段方块<b>不画</b>（整扇门由下半段一次画完，否则重合成 z-fighting），见
 * {@link FangsuPsdDraw#isUpperHalf}。
 */
public class FangsuPsdEndDoorRenderer implements BlockEntityRenderer<FangsuPsdEndDoorBlock.FangsuPsdEndDoorEntity> {

    @Override
    public boolean rendersOutsideBoundingBox(final FangsuPsdEndDoorBlock.FangsuPsdEndDoorEntity entity) {
        return true;
    }

    @Override
    public int getRenderDistance() {
        return RenderDistance.blocks();
    }

    @Override
    public boolean isInRenderDistance(final FangsuPsdEndDoorBlock.FangsuPsdEndDoorEntity entity, final net.minecraft.util.math.Vec3d cameraPos) {
        return RenderDistance.inRange(entity, cameraPos);
    }

    public FangsuPsdEndDoorRenderer(final BlockEntityRendererFactory.Context context) {
    }

    @Override
    public void render(final FangsuPsdEndDoorBlock.FangsuPsdEndDoorEntity entity, final float tickDelta,
                       final MatrixStack matrices, final VertexConsumerProvider vertexConsumers,
                       final int light, final int overlay) {
        final World world = entity.getWorld();
        final BlockState state = world != null ? world.getBlockState(entity.getPos()) : entity.getCachedState();
        if (!(state.getBlock() instanceof FangsuPsdEndDoorBlock block)) {
            return;
        }
        if (FangsuPsdDraw.isUpperHalf(state)) {
            return;
        }
        final float progress = entity.progress(state, world == null ? 0L : world.getTime(), tickDelta);
        FangsuPsdDraw.draw(block.def(), progress, FangsuPsdDraw.facingOf(state),
                matrices, vertexConsumers, light, overlay);
    }
}
