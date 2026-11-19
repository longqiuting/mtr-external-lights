// 时钟方块实体：只存配置 + 跑指针时间状态机 + 整点报时。几何一概不参与。
package dev.js.mtrheadlight.clock;

import dev.js.mtrheadlight.LteLog;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 时钟方块 {@code mtrlte:clock} 的方块实体。
 *
 * <h2>它只做三件事</h2>
 * <ol>
 *   <li><b>存配置</b>：{@link ClockConfig} 整份写进 NBT（刷子右键改的那一堆字段）；</li>
 *   <li><b>指针的显示时刻</b>（{@link #displaySeconds(float)}）：正常走、红石断电停摆、
 *       恢复供电后 1.3 秒追上当前时间 —— 全部在这里算，<b>不经过网络</b>
 *       （客户端自己看得到世界时间 / 系统时间，两边算出来必然一样）；</li>
 *   <li><b>整点报时</b>（仅服务端）：每 20 tick 看一次 12 小时制的整点有没有变，
 *       到点就按 SINGLE / HOURLY 排「敲几下」，用 {@code pendingStrikes + nextStrikeTick}
 *       状态机每 0.4 秒敲一下，<b>不另起线程</b>。</li>
 * </ol>
 *
 * <h2>停摆与追针</h2>
 * <p>红石控制打开、且方块没被充能时，{@code dispNow} 冻结在断电那一刻的显示值 —— 指针原地不动，
 * 也不报时。恢复供电的那一 tick 记下「差了多少秒」（{@code gapRemaining}），然后在
 * {@link #CATCHUP_TICKS} = 26 tick（1.3 秒）里匀速把这个差吃完：指针像被人拨快一样转回去，
 * 而不是瞬跳。差多少就是多少（停了俩小时就转俩小时），转动速度按差距自适应。
 *
 * <h2>为什么 tick 里先算显示值再判客户端</h2>
 * <p>指针位置两端都要（客户端画、服务端只管报时），报时是服务端权威。所以前半段两端都跑，
 * 后半段 {@code world.isClient} 直接返回。
 */
public class ClockBlockEntity extends BlockEntity {

    /** 恢复供电后追上当前时间用多少 tick（26 tick = 1.3 秒，落在需求的 1–1.5 秒）。 */
    public static final int CATCHUP_TICKS = 26;

    /** 整点检查间隔（tick）：20 tick = 1 秒一次。 */
    private static final int CHECK_INTERVAL = 20;

    /** HOURLY 模式两下之间的间隔（tick）：8 tick = 0.4 秒。 */
    private static final int STRIKE_GAP = 8;

    private static final String TAG_CONFIG = "Clock";

    private ClockConfig config = new ClockConfig();

    // ------------------------------------------------------------------ 显示状态（瞬时）
    /** 上一 tick 的显示值（秒），渲染插值用。 */
    private double dispPrev;
    /** 这一 tick 的显示值（秒）。 */
    private double dispNow;
    /** 第一次 tick 之前没有任何「上一帧」，先对齐再说。 */
    private boolean initialized;
    /** 上一次 tick 是不是停摆状态。 */
    private boolean stopped;
    /** 还要追回多少秒（恢复供电后从大往小掉）。 */
    private double gapRemaining;
    /** 每 tick 追多少秒（= 恢复那一刻的差距 / {@link #CATCHUP_TICKS}）。 */
    private double gapPerTick;

    // ------------------------------------------------------------------ 报时状态（瞬时）
    /** 每多少 tick 检查一次整点。 */
    private int tickCounter;
    /** 异常日志的节流计数器（和 {@link #tickCounter} 互不相干）。 */
    private int errorCounter;
    /** 上一次看到的 12 小时制钟点；-1 = 还没对齐（或刚过整点以外的时间）。 */
    private int lastChimeHour = -1;
    /** 第一次检查不敲钟，只把「现在几点」记下来。 */
    private boolean chimePrimed;
    /** 还剩几下没敲。 */
    private int pendingStrikes;
    /** 下一下最早在哪一 tick 敲。 */
    private long nextStrikeTick;

    public ClockBlockEntity(final BlockPos pos, final BlockState state) {
        super(ClockObjects.entityType(), pos, state);
    }

    // ------------------------------------------------------------------ 配置

    /** 当前配置的<b>副本</b>（改它不会影响存档数据）。 */
    public ClockConfig config() {
        return this.config.copy();
    }

    /** 覆盖整份配置（服务端收到保存包时用；这里再 clamp 一次）。 */
    public void setConfig(final ClockConfig cfg) {
        if (cfg == null) {
            return;
        }
        final ClockConfig copy = cfg.copy();
        copy.clamp();
        this.config = copy;
        this.markDirty();
    }

    // ------------------------------------------------------------------ 时间

    /**
     * 这一帧要画的一天内秒数（含手动校准、含红石停摆 / 追针）。
     *
     * @param tickDelta 渲染插值（0..1）。tick 之间按「这一 tick 走了多少」线性补，
     *                  停摆时差值为 0（指针纹丝不动），追针时差值很大（快速扫过）。
     */
    public double displaySeconds(final float tickDelta) {
        if (!this.initialized) {
            // 渲染可能比第一次 tick 早一帧：直接按时间源算，别让钟面先在 12:00 闪一下。
            return ClockTime.displayTarget(this.world, this.config, tickDelta);
        }
        double delta = this.dispNow - this.dispPrev;
        // 跨零点：差值是「绕回来」的那一段，取最短的一侧，免得指针倒转一整圈。
        if (delta < -ClockTime.DAY_SECONDS / 2.0D) {
            delta += ClockTime.DAY_SECONDS;
        } else if (delta > ClockTime.DAY_SECONDS / 2.0D) {
            delta -= ClockTime.DAY_SECONDS;
        }
        return ClockTime.wrap(this.dispNow + delta * tickDelta);
    }

    /** 现在是不是停摆（红石模式 + 未充能）。 */
    public boolean isStopped() {
        return this.stopped;
    }

    // ------------------------------------------------------------------ tick

    /** {@code BlockEntityTicker} 的入口（由 {@link ClockBlock#getTicker} 交出来）。 */
    public static void tick(final World world, final BlockPos pos, final BlockState state,
                            final ClockBlockEntity entity) {
        try {
            entity.tickOnce(world, pos, state);
        } catch (Throwable t) {
            // 时钟出问题绝不许影响 world tick（一个坏配置不该让区块 tick 崩掉）。
            if (entity.errorCounter++ % 200 == 0) {
                LteLog.LOGGER.warn("[lte] clock at {} failed to tick", pos, t);
            }
        }
    }

    private void tickOnce(final World world, final BlockPos pos, final BlockState state) {
        final double actual = ClockTime.displayTarget(world, this.config, 0.0D);
        final boolean stop = this.config.redstone && !state.get(ClockBlock.POWERED);

        if (!this.initialized) {
            this.initialized = true;
            this.dispPrev = actual;
            this.dispNow = actual;
        }

        this.dispPrev = this.dispNow;
        if (stop) {
            // 停摆：显示值原地不动（就是断电那一刻的样子），也没在追针。
            this.stopped = true;
            this.gapRemaining = 0.0D;
            this.gapPerTick = 0.0D;
        } else {
            if (this.stopped) {
                // 刚恢复供电：记下差多少，26 tick 匀速吃完。
                this.stopped = false;
                final double gap = ClockTime.wrap(actual - this.dispNow);
                this.gapRemaining = gap;
                this.gapPerTick = gap / CATCHUP_TICKS;
            }
            if (this.gapRemaining > 0.0D) {
                this.gapRemaining = Math.max(0.0D, this.gapRemaining - this.gapPerTick);
                this.dispNow = ClockTime.wrap(actual - this.gapRemaining);
            } else {
                this.dispNow = actual;
            }
        }

        if (world.isClient) {
            return;
        }
        this.tickChime(world, pos, stop);
    }

    // ------------------------------------------------------------------ 报时

    /**
     * 整点报时的状态机。
     *
     * <ol>
     *   <li>每 {@link #CHECK_INTERVAL} tick 看一次「12 小时制钟点」，整点（分 = 0）且钟点变了才起敲；</li>
     *   <li>起敲时按模式排数：SINGLE 排 1 下，HOURLY 排 N 下（N = 12 小时制的小时，最多 12）；</li>
     *   <li>每 tick 看 {@code nextStrikeTick}，到点敲一下、把下一次推后 {@link #STRIKE_GAP} tick。</li>
     * </ol>
     *
     * <p>断电（红石模式未充能）时：正在敲的那一串<b>立刻取消</b>，也不起新的 ——
     * 需求里的「断电时不报时」。
     */
    private void tickChime(final World world, final BlockPos pos, final boolean stop) {
        if (stop) {
            this.pendingStrikes = 0;
            this.nextStrikeTick = 0L;
        }

        if (this.pendingStrikes > 0) {
            if (world.getTime() >= this.nextStrikeTick) {
                world.playSound(null, pos, this.soundEvent(), SoundCategory.BLOCKS, 1.0F, 1.0F);
                this.pendingStrikes--;
                this.nextStrikeTick = world.getTime() + STRIKE_GAP;
            }
            // 敲钟期间照样往下走整点检查，只是这一秒里分钟不会变，不会重复起敲。
        }

        if (++this.tickCounter % CHECK_INTERVAL != 0) {
            return;
        }

        final double display = ClockTime.displayTarget(world, this.config, 0.0D);
        final int minute = ClockTime.minute(display);
        final int hour12 = ClockTime.hour12(display);

        if (!this.chimePrimed) {
            this.chimePrimed = true;
            this.lastChimeHour = minute == 0 ? hour12 : -1;
            return;
        }
        if (minute != 0) {
            // 不在整点：把「上一次的钟点」清掉，下一个整点才认得出来。
            this.lastChimeHour = -1;
            return;
        }
        if (!this.config.chime || stop || hour12 == this.lastChimeHour) {
            return;
        }
        this.lastChimeHour = hour12;
        this.pendingStrikes = this.config.chimeMode == ClockConfig.ChimeMode.HOURLY ? hour12 : 1;
        this.nextStrikeTick = world.getTime() + STRIKE_GAP;
        LteLog.LOGGER.debug("[lte] clock at {} chiming {}x at {}:00", pos,
                Integer.valueOf(this.pendingStrikes), Integer.valueOf(ClockTime.hour(display)));
    }

    /** 配置里的音效 id → {@link SoundEvent}；找不到就退回原版钟声。 */
    public SoundEvent soundEvent() {
        final Identifier id = Identifier.tryParse(this.config.chimeSound);
        if (id != null) {
            final SoundEvent event = Registries.SOUND_EVENT.getOrEmpty(id).orElse(null);
            if (event != null) {
                return event;
            }
        }
        return SoundEvents.BLOCK_BELL_USE;
    }

    /** 点「试听」时服务端播一下（界面里那个按钮走 {@link ClockNet}）。 */
    public void playPreview() {
        if (this.world == null || this.world.isClient) {
            return;
        }
        this.world.playSound(null, this.pos, this.soundEvent(), SoundCategory.BLOCKS, 1.0F, 1.0F);
    }

    // ------------------------------------------------------------------ NBT

    @Override
    public void writeNbt(final NbtCompound nbt) {
        super.writeNbt(nbt);
        final NbtCompound cfg = new NbtCompound();
        this.config.writeNbt(cfg);
        nbt.put(TAG_CONFIG, cfg);
    }

    @Override
    public void readNbt(final NbtCompound nbt) {
        super.readNbt(nbt);
        if (nbt.contains(TAG_CONFIG)) {
            final ClockConfig cfg = new ClockConfig();
            cfg.readNbt(nbt.getCompound(TAG_CONFIG));
            this.config = cfg;
        }
    }
}
