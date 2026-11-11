package dev.js.mtrheadlight.mixin.mtrserver;

import dev.js.mtrheadlight.bind.PidsScheduleScope;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 把「绑定过的显示屏旁边的玩家」也算进 MTR 的到站数据范围。
 *
 * <h2>为什么改这个地方</h2>
 * <p>MTR 给玩家发 {@code PACKET_UPDATE_SCHEDULE} 时，只带<b>离玩家 16 格以内</b>、或者
 * <b>所在车站矩形覆盖玩家位置</b>的站台（javap：
 * {@code RailwayData.lambda$simulateTrains$7} →
 * {@code platform.isCloseToSavedRail(playerBlockPos, 16, 16, 16) || station.inArea(px, pz)}）。
 * 客户端收到后是 {@code ClientData.SCHEDULES_FOR_PLATFORM} <b>整表替换</b>，
 * 所以没被带上的站台，它的到站数据会被删掉 —— 站外的投影机 / PIDS 就黑屏，而且是
 * 「亮 3 秒、灭 3 秒」的周期性表现（MTR 每 60 tick 重发一次包）。
 *
 * <p>{@code SavedRailBase#isCloseToSavedRail} 在 MTR 里<b>只有这一个调用点</b>
 * （grep 整个 jar：只有 {@code RailwayData} 与 {@code SavedRailBase} 自己提到这个名字），
 * 所以在这里放宽判定的波及面是最小的：别的逻辑（GUI 选站台、PIDS 自动模式）都不走它。
 *
 * <p>返回 {@code true} 时立刻返回，不打扰 MTR 本来的判定顺序；
 * 只有 {@code false} 且玩家正站在「绑定了这个站台的屏」旁边时才改成 {@code true}。
 * 索引与阈值见 {@link PidsScheduleScope}。
 */
@Mixin(value = mtr.data.SavedRailBase.class, remap = false)
public abstract class PidsScheduleScopeMixin {

    @Inject(method = "isCloseToSavedRail", at = @At("RETURN"), cancellable = true, remap = false)
    private void lte$widenScheduleScope(final BlockPos pos, final int x, final int y, final int z,
                                        final CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue())) {
            return;
        }
        try {
            final long id = ((mtr.data.NameColorDataBase) (Object) this).id;
            if (PidsScheduleScope.covers(id, pos)) {
                cir.setReturnValue(Boolean.TRUE);
            }
        } catch (Throwable ignored) {
            // 服务端 tick 里的判定，永远不许因为我们的索引抛异常而打断 MTR 的列车模拟。
        }
    }
}
