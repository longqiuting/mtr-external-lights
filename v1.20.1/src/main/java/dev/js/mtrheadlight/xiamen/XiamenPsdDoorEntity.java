package dev.js.mtrheadlight.xiamen;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

/**
 * 门扇的 BlockEntity —— 对应 MTR 的 {@code BlockPSDAPGDoorBase.TileEntityPSDAPGDoorBase}。
 *
 * <p>MTR 把「开着多少」存在 BE 里（{@code open:int 0..32} + 客户端 {@code openClient:float}），
 * 我们这边<b>开关状态</b>放在 blockstate（{@code open=true/false}，原版负责存档 + 同步），
 * BE 只保留一个 <b>客户端插值进度</b> {@link #open} ∈ [0,1]，让门叶是「滑」出去的而不是瞬移。
 *
 * <p>插值方式和 MTR 一致：每 tick 朝目标走固定步长（MTR 的
 * {@code getOpen(lastFrameDuration)} 也是「按帧把 openClient 往 open-0.1 上靠」），
 * 渲染时再用 {@code tickDelta} 在 {@link #prevOpen} 与 {@link #open} 之间插值。
 */
public class XiamenPsdDoorEntity extends BlockEntity {

    /** 每 tick 的滑动距离：约 14 tick（0.7 秒）走完一格，和真实屏蔽门开合速度接近。 */
    private static final float SPEED = 0.07F;

    private float open;
    private float prevOpen;

    public XiamenPsdDoorEntity(BlockPos pos, BlockState state) {
        super(XiamenObjects.psdDoorEntityType(), pos, state);
    }

    /** 客户端每 tick 调一次；服务端根本没注册 ticker。 */
    public void tickAnimation() {
        this.prevOpen = this.open;
        final World world = this.getWorld();
        final BlockState state = world == null ? this.getCachedState() : world.getBlockState(this.getPos());
        final float target = state.getBlock() == XiamenObjects.psdDoor() && state.get(XiamenPsdDoorBlock.OPEN) ? 1.0F : 0.0F;
        if (this.open < target) {
            this.open = Math.min(target, this.open + SPEED);
        } else if (this.open > target) {
            this.open = Math.max(target, this.open - SPEED);
        }
    }

    /** 渲染用：0 = 全关，1 = 全开。 */
    public float getOpen(float tickDelta) {
        return MathHelper.lerp(tickDelta, this.prevOpen, this.open);
    }
}
