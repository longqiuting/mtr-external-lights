// 贴墙标识的方块实体：纯标记，不存任何数据。
package dev.js.mtrheadlight.xiamen.sign;

import mtr.mappings.BlockEntityMapper;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

/**
 * 贴墙标识 {@code mtrlte:wall_sign_*} 的方块实体。
 *
 * <h2>它什么都不存</h2>
 * <p>站名、站色、这条牌子横跨几格 —— 全部每帧现算：
 * <ul>
 *   <li><b>站名 / 站色</b>：{@code RailwayData.getStation(ClientData.STATIONS,
 *       ClientData.DATA_CACHE, pos)}，按<b>位置</b>取（MTR 自己的站名牌子也是这么取的），
 *       所以不需要绑车站、不需要 NBT、不需要网络同步；</li>
 *   <li><b>连接 / 形态</b>：都在方块状态上（{@code link} 位掩码 + {@code style}），
 *       方块实体一个字段都不重复存；</li>
 *   <li><b>没有 ticker</b>：只有渲染器在画，服务端一次都不碰它。</li>
 * </ul>
 * <p>存在它的唯一理由和 {@code dep_on_wall} 一样：BER 只能挂在方块实体类型上，
 * 而方块实体渲染器拿不到「没有方块实体的方块」。
 *
 * <p>继承 MTR 的 {@link BlockEntityMapper}（而不是 vanilla {@code BlockEntity}）是为了和其它
 * MTR 方块保持一致：它把 1.20.1 的 {@code toInitialChunkDataNbt} / {@code readNbt} 名字差异包了一层，
 * {@code writeCompoundTag} 默认空实现就够了。
 */
public class WallSignBlockEntity extends BlockEntityMapper {

    public WallSignBlockEntity(final BlockPos pos, final BlockState state) {
        super(WallSignObjects.entityType(), pos, state);
    }
}
