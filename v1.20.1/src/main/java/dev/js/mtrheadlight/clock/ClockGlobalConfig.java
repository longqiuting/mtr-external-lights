// 全局时钟配置：一份 ClockConfig + 一个「全局启用」开关。纯数据，不碰方块 / 世界 / 网络。
package dev.js.mtrheadlight.clock;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;

/**
 * 仪表板「其他 → 时钟全局设置」改的那一份配置。
 *
 * <h2>为什么是「{@link ClockConfig} + 一个开关」而不是往 {@link ClockConfig} 里加字段</h2>
 * <p>{@link ClockConfig} 是<b>每块钟一份</b>的东西（存方块实体 NBT）。「全局启用」只在全局这一层
 * 有意义 —— 加进去会让每块钟的 NBT 里都多一个谁也不读的 {@code enabled}。所以包一层。
 *
 * <h2>{@link #enabled} 默认关</h2>
 * <p>装上这个版本不会把玩家现有的钟全改掉：只有管理员在仪表板里打开「全局启用」之后，
 * 全局配置才盖住每一块钟自己的设置。
 *
 * <h2>三个出口</h2>
 * <ul>
 *   <li>{@link #writeNbt}/{@link #readNbt} —— 服务端 {@link ClockGlobalState}（{@code PersistentState}）落盘；</li>
 *   <li>{@link #writeBuf}/{@link #readBuf} —— {@link ClockGlobalNet} 的快照（S2C）与保存（C2S）；</li>
 *   <li>{@link #clamp} —— 两端最后一道闸，直接转给 {@link ClockConfig#clamp()}。</li>
 * </ul>
 */
public final class ClockGlobalConfig {

    private static final String TAG_ENABLED = "Enabled";
    private static final String TAG_CLOCK = "Clock";

    /** 全局配置是否盖住每一块钟自己的配置。 */
    public boolean enabled;

    /** 全局那一份 {@link ClockConfig}。 */
    public ClockConfig config;

    /** 出厂状态：关闭 + 默认配置。 */
    public ClockGlobalConfig() {
        this.enabled = false;
        this.config = new ClockConfig();
    }

    public ClockGlobalConfig(final ClockConfig config, final boolean enabled) {
        this.enabled = enabled;
        this.config = config == null ? new ClockConfig() : config.copy();
    }

    /** 深拷贝（对外只给副本，别让人直接改到存档 / 缓存里的那一份）。 */
    public ClockGlobalConfig copy() {
        return new ClockGlobalConfig(this.config, this.enabled);
    }

    /** 压回合法范围（全局配置与单块钟用同一套规则）。 */
    public void clamp() {
        if (this.config == null) {
            this.config = new ClockConfig();
        }
        this.config.clamp();
    }

    // ------------------------------------------------------------------ NBT

    public void writeNbt(final NbtCompound nbt) {
        nbt.putBoolean(TAG_ENABLED, this.enabled);
        final NbtCompound clock = new NbtCompound();
        this.config.writeNbt(clock);
        nbt.put(TAG_CLOCK, clock);
    }

    /** 读一份（缺字段一律回默认，坏存档不会让钟变成纯黑）。 */
    public static ClockGlobalConfig readNbt(final NbtCompound nbt) {
        final ClockGlobalConfig out = new ClockGlobalConfig();
        if (nbt == null) {
            return out;
        }
        out.enabled = nbt.getBoolean(TAG_ENABLED);
        if (nbt.contains(TAG_CLOCK)) {
            out.config.readNbt(nbt.getCompound(TAG_CLOCK));
        }
        out.clamp();
        return out;
    }

    // ------------------------------------------------------------------ 网络

    public void writeBuf(final PacketByteBuf buf) {
        buf.writeBoolean(this.enabled);
        this.config.writeBuf(buf);
    }

    public static ClockGlobalConfig readBuf(final PacketByteBuf buf) {
        final ClockGlobalConfig out = new ClockGlobalConfig();
        out.enabled = buf.readBoolean();
        out.config = ClockConfig.readBuf(buf);
        out.clamp();
        return out;
    }
}
