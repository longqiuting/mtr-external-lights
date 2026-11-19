package dev.js.mtrheadlight.client;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;

/**
 * 方块实体渲染器的视距。
 *
 * <p>1.20.1 的 {@code BlockEntityRenderDispatcher#render} 会先问
 * {@code renderer.isInRenderDistance(be, camera.getPos())}，默认实现拿 {@code getRenderDistance()}
 * （64 格）比距离 —— 所以模型再大、再显眼，超过 64 格也直接不画。这里改成
 * 「跟着客户端区块视距走」，和普通方块同一个可见范围（也就和 NTE 装饰物件的观感一致）。</p>
 */
public final class RenderDistance {

    /** 下限 / 上限（格）：视距 2 与 32 个区块时的量级，防止配置极端值把 BER 铺满世界。 */
    private static final int MIN = 64;
    private static final int MAX = 512;

    private RenderDistance() {
    }

    /** 当前应该用的绘制距离（格）。 */
    public static int blocks() {
        final MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null) {
            return MIN;
        }
        final int chunks = client.options.getViewDistance().getValue();
        return Math.max(MIN, Math.min(MAX, chunks * 16 + 32));
    }

    /** 方块实体中心到相机的距离在 {@link #blocks()} 之内。 */
    public static boolean inRange(final BlockEntity entity, final Vec3d cameraPos) {
        final double d = blocks();
        final Vec3d center = Vec3d.ofCenter(entity.getPos());
        return center.squaredDistanceTo(cameraPos) < d * d;
    }
}
