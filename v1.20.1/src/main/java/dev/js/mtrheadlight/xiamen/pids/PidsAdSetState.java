// 「全局广告集」的服务端权威存档：一份世界级 PersistentState（data/mtrlte_pids_ad_set.dat）。
package dev.js.mtrheadlight.xiamen.pids;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.PersistentState;

/**
 * 全局广告集（{@link PidsAdSet}）在服务端的唯一一份存档，照 {@code ClockGlobalState} 的做法：
 * 存主世界那一份，任意维度 / 任意玩家看到的都是同一套。
 *
 * <h2>为什么存在服务端</h2>
 * <p>「所有屏都改用这一套」是服务器级的策略，不是某一块屏的属性，也不该按客户端各看各的 ——
 * 否则两个人站在同一块屏前会看到不同的广告。所以权威值在这里，客户端只拿只读镜像
 * （{@link PidsAdSetCache}），改一次广播给所有人。
 *
 * <h2>写</h2>
 * <p>{@link #set} 只改内存 + {@link #markDirty()}；广播与权限校验都在
 * {@link PidsAdSetNet#applySave}。存档类不认识网络，和 {@code ClockGlobalState} /
 * {@code PidsLinkStore} 的分工一致。
 *
 * <h2>只 markDirty 不够</h2>
 * <p>{@code markDirty()} 只保证「下次区块 / 存档落盘时写这份数据」，它<b>不会</b>把变化推给
 * 已经在服里的客户端。客户端那份镜像靠 {@link PidsAdSetNet} 的快照包更新（进服一份 + 每次保存
 * 广播一份）。如果哪天把这份数据挪进某个方块实体，同步就必须补齐
 * {@code toUpdatePacket()} + {@code toInitialChunkDataNbt()} + {@code world.updateListeners(...)}
 * 三件套（{@code ClockBlockEntity} 里就是这么做的）—— 只 markDirty 的话客户端永远看不到新值。
 */
public final class PidsAdSetState extends PersistentState {

    /** 存档 id / 文件名：{@code data/mtrlte_pids_ad_set.dat}。 */
    public static final String ID = "mtrlte_pids_ad_set";

    /** 内存里的真相。 */
    private final PidsAdSet data = new PidsAdSet();

    /** 新档：关着、空集（绝不悄悄改掉玩家屏上正在播的广告）。 */
    public PidsAdSetState() {
    }

    /** 读档。 */
    public PidsAdSetState(final NbtCompound nbt) {
        this.data.copyFrom(PidsAdSet.readNbt(nbt));
    }

    /** 主世界那一份（没有就建）。{@code server == null} 时给一个临时对象，调用方别存它。 */
    public static PidsAdSetState get(final MinecraftServer server) {
        if (server == null) {
            return new PidsAdSetState();
        }
        final ServerWorld overworld = server.getOverworld();
        if (overworld == null) {
            return new PidsAdSetState();
        }
        return overworld.getPersistentStateManager()
                .getOrCreate(PidsAdSetState::new, PidsAdSetState::new, ID);
    }

    /** 当前那份的<b>副本</b>（发快照 / 界面用）。 */
    public PidsAdSet get() {
        return this.data.copy();
    }

    /** 整份覆盖（调用方已经 clamp 过，这里再来一次）。 */
    public void set(final PidsAdSet set) {
        this.data.copyFrom(set);
        this.markDirty();
    }

    @Override
    public NbtCompound writeNbt(final NbtCompound nbt) {
        return this.data.writeNbt(nbt);
    }
}
