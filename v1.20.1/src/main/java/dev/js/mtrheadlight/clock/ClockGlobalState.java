// 全局时钟配置的服务端存档：一份世界级 PersistentState（world/data/mtrlte_clock_global.dat）。
package dev.js.mtrheadlight.clock;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.PersistentState;

/**
 * 「所有时钟」那一份配置的服务端权威存档。
 *
 * <h2>为什么必须是服务端</h2>
 * <p>整点报时、红石停摆都是服务端行为，客户端说的不算；配置写盘也只能在服务端。所以全局配置
 * 存在 {@link PersistentState} 里，客户端只拿一份只读镜像（{@link ClockGlobalCache}）。
 *
 * <h2>存在哪个世界 / 一个还是每维度一份</h2>
 * <p>存<b>主世界</b>那一份（{@code server.getOverworld().getPersistentStateManager()}，key
 * {@value #ID}）—— 需求是「所有时钟」，一个服务器一份，不按下界 / 末地区分。任意维度的钟都能拿到
 * 同一份：方块实体从 {@code world.getServer()} 进来，这里再转去主世界。
 * 文件落在 {@code <存档>/data/mtrlte_clock_global.dat}。
 *
 * <h2>写</h2>
 * <p>{@link #set} 只负责改内存 + {@code markDirty()}；广播由 {@link ClockGlobalNet#applySave}
 * 负责（存档类不认识网络，和 {@code PidsLinkStore} 的分工一致）。
 */
public final class ClockGlobalState extends PersistentState {

    /** 存档 id / 文件名：{@code data/mtrlte_clock_global.dat}。 */
    public static final String ID = "mtrlte_clock_global";

    /** 当前那份（内存里的真相）。 */
    private final ClockGlobalConfig data = new ClockGlobalConfig();

    /** 新档：默认「关闭 + 默认配置」（绝不悄悄改掉玩家已有的钟）。 */
    public ClockGlobalState() {
    }

    /** 读档。 */
    public ClockGlobalState(final NbtCompound nbt) {
        final ClockGlobalConfig loaded = ClockGlobalConfig.readNbt(nbt);
        this.data.enabled = loaded.enabled;
        this.data.config = loaded.config;
    }

    /** 拿这一份（主世界那一份；没有就建）。{@code server == null} 时给一个临时对象，调用方别存它。 */
    public static ClockGlobalState get(final MinecraftServer server) {
        if (server == null) {
            return new ClockGlobalState();
        }
        final ServerWorld overworld = server.getOverworld();
        if (overworld == null) {
            return new ClockGlobalState();
        }
        return overworld.getPersistentStateManager()
                .getOrCreate(ClockGlobalState::new, ClockGlobalState::new, ID);
    }

    /** 当前配置的<b>副本</b>（界面 / 包用）。 */
    public ClockGlobalConfig get() {
        return this.data.copy();
    }

    /** 全局开关是不是开着。 */
    public boolean enabled() {
        return this.data.enabled;
    }

    /** 整份覆盖（服务端收到保存包时用；调用方已经 clamp 过，这里再来一次）。 */
    public void set(final ClockGlobalConfig cfg) {
        if (cfg == null) {
            return;
        }
        final ClockGlobalConfig copy = cfg.copy();
        copy.clamp();
        this.data.enabled = copy.enabled;
        this.data.config = copy.config;
        this.markDirty();
    }

    /**
     * 时钟实际该用的配置：全局开关开着 → 全局那份；关着 → 方块自己的。
     *
     * <p>{@code server == null}（理论上单机客户端侧 / 数据没起来）时退回方块自己那份，
     * 绝不因为「拿不到全局」让钟停摆。
     */
    public static ClockConfig effectiveOr(final MinecraftServer server, final ClockConfig own) {
        if (server == null) {
            return own;
        }
        final ClockGlobalState state = get(server);
        if (!state.data.enabled) {
            return own;
        }
        return state.data.config.copy();
    }

    @Override
    public NbtCompound writeNbt(final NbtCompound nbt) {
        this.data.writeNbt(nbt);
        return nbt;
    }
}
