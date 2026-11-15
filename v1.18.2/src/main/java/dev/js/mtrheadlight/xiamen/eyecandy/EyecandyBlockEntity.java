// eyecandy 的方块实体：几何的锚点（其它 57 件）+ BRT 屏蔽门收纳机的警示灯状态机（那两件）。
package dev.js.mtrheadlight.xiamen.eyecandy;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * eyecandy 的方块实体。两种角色：
 *
 * <ul>
 *   <li><b>纯锚点</b>（59 件里的 57 件）：模型、贴图、摆放参数全在方块的 {@link EyecandyEntry} 上，
 *       渲染器每次自己从方块实例读，这里没有数据；</li>
 *   <li><b>警示灯状态机</b>（BRT 屏蔽门收纳机 {@code xiamen_brt_psd_left / _right}）：每
 *       {@value #SCAN_INTERVAL_TICKS} tick 看一次周围有没有开着的屏蔽门（{@link PsdDoorProbe}），
 *       门的状态一变就闪三下，然后停在「亮」（开门）或「灭」（关门）。
 *       渲染器每帧读 {@link #isLit()}。</li>
 * </ul>
 *
 * <h2>闪烁节奏（常量都在下面这一段，改数值即可）</h2>
 * <pre>
 *   一个亮相 / 灭相 = BLINK_PHASE_TICKS = 12 tick = 0.6s
 *   闪一下 = 亮 12 + 灭 12 = 24 tick = 1.2s
 *   闪三下 = 3 × 24 tick = 72 tick = 3.6s（BLINK_FLASHES × 2 × BLINK_PHASE_TICKS）
 * </pre>
 * <p>开门（关→开）：亮–灭–亮–灭–亮–灭，然后<b>常亮</b>；
 * 关门（开→关）：同样的三下，然后<b>熄灭</b>。两种切换的波形一样，只是「闪完停在哪」不同 ——
 * 所以状态机只记 {@code doorOpen}（闪完的目标），相位完全由 {@code world.getTime()} 算。
 *
 * <p>觉得 3.6s 还长就把 {@link #BLINK_FLASHES} 调成 2（2.4s）；只想改快慢就动
 * {@link #BLINK_PHASE_TICKS}。两者相乘就是整段时长，别在别处再写死 12 / 3。
 *
 * <h2>关门为什么不等门合上</h2>
 * <p>MTR 的 {@code isOpen()} 是 {@code open > 0}：<b>关门动画全程都还是 true</b>，等到它翻成
 * false 门已经合拢了 —— 用户反馈「门都关上了才闪」就是这个原因。现在用
 * {@link PsdDoorProbe.Scan#closing}（open 值在变小的那一 tick）当作「开始关门」的信号，
 * 门刚动就起闪；只有布尔状态的端门也在翻成关的那一 tick 起闪（见 {@link PsdDoorProbe}）。
 *
 * <h2>状态从哪来（不硬编码门类）</h2>
 * <p>{@link PsdDoorProbe.Tracker#scan} 先看 blockstate 上的 {@code open} / {@code door_open} 类属性
 * （方速端门走这条），再看 MTR {@code BlockPSDAPGDoorBase} 方块实体的 {@code getOpenValue()}
 * （MTR 原版 / 方速滑动门走这条）。<b>找不到相邻门时保持上一次状态</b>（不改灯、不重启动画）。
 *
 * <h2>为什么两端都要 tick</h2>
 * <p>方块实体的 ticker 由 {@link EyecandyBlock#getTicker} 提供，服务端与客户端都会跑
 * （原版 {@code BlockEntityTicker} 的约定）。客户端那份是渲染要读的；服务端那份不参与渲染，
 * 但保持两端逻辑一致 —— 以后要在服务端做「开门提示音 / 红石输出」就不用另起一套。
 * 状态<b>不写 NBT、不联网同步</b>：它就是「旁边那扇门的开关状态」的镜像，重进世界时
 * 按当前门状态直接对齐（不补闪一次）。
 *
 * <h2>构造器必须容忍 {@code null} BlockState</h2>
 * <p>{@code BlockItem} 在物品栏 / 手持渲染时会走 {@code createBlockEntity(pos, null)}，
 * 这里兜底成 {@code Blocks.AIR.getDefaultState()}，否则打开背包就 NPE（PIDS 那边踩过一次）。
 */
public class EyecandyBlockEntity extends BlockEntity {

    /**
     * 一个亮相 / 灭相持续多少 tick（12 tick = 0.6s）。<b>想调快慢就只改这一个数</b>：
     * 一周期 = 2 × 本值，整段 = {@link #BLINK_FLASHES} × 2 × 本值。
     */
    public static final int BLINK_PHASE_TICKS = 12;

    /** 闪几下（开门 / 关门都闪这么多下）。3 × 24 tick = 3.6s；嫌长改 2（2.4s）。 */
    public static final int BLINK_FLASHES = 3;

    /** 整段闪烁的时长：{@code 3 × 2 × 12 = 72} tick（3.6s）。 */
    public static final int BLINK_TOTAL_TICKS = BLINK_PHASE_TICKS * 2 * BLINK_FLASHES;

    /**
     * 多久扫一次邻居。
     *
     * <p>2 tick（0.1s）：关门要「提前切入」，扫描间隔就是最坏情况下的提前量误差 ——
     * 布尔状态的端门拿不到 open 中间态，只能等它翻成关的那一 tick，间隔越小越贴近「立刻」。
     * 一次扫描 26 个方块的 {@code getBlockState} + 方块实体读，2 tick 的代价可以忽略。
     */
    static final int SCAN_INTERVAL_TICKS = 1;

    /** 最后一次看到门的开 / 关（闪完之后停在这个状态）。 */
    private boolean doorOpen;

    /** 有没有拿到过第一次读数（没拿到之前灯是灭的、也不闪）。 */
    private boolean initialised;

    /** 正在闪。 */
    private boolean blinking;

    /** 本次闪烁从哪个 world time 开始。 */
    private long blinkStart;

    /** 扫描倒计时（{@code 0} = 下一 tick 就扫）。 */
    private int scanCountdown;

    /** 每个方块实体自己一份：判「门正在关」要对比上一轮的 open 值（{@link PsdDoorProbe.Tracker}）。 */
    private final PsdDoorProbe.Tracker doorTracker = new PsdDoorProbe.Tracker();

    /** 当前这一 tick 的外观：亮 / 灭。渲染器读它。 */
    private boolean lit;

    public EyecandyBlockEntity(final BlockPos pos, final BlockState state) {
        super(EyecandyBlocks.entityType(), pos, state == null ? Blocks.AIR.getDefaultState() : state);
    }

    // ------------------------------------------------------------------ tick

    /** {@code BlockEntityTicker} 的入口（由 {@link EyecandyBlock#getTicker} 交出去）。 */
    public static void tick(final World world, final BlockPos pos, final BlockState state,
                            final EyecandyBlockEntity entity) {
        entity.tick(world, pos);
    }

    /**
     * 闪烁进行到第 {@code elapsed} tick 时该不该亮（{@code 0} = 切状态那一 tick = 亮相开始）。
     *
     * <p>亮相 / 灭相各 {@link #BLINK_PHASE_TICKS}（12）tick，偶数相亮、奇数相灭，于是
     * {@code 亮12 灭12 亮12 灭12 亮12 灭12}。整段（{@link #BLINK_TOTAL_TICKS} 之后）由调用方切成
     * 「常亮 / 熄灭」。
     *
     * @param elapsed 距离闪烁开始的 tick 数
     */
    public static boolean blinkLit(final long elapsed) {
        return (elapsed / BLINK_PHASE_TICKS) % 2L == 0L;
    }

    private void tick(final World world, final BlockPos pos) {
        final long now = world.getTime();

        // 先按 world time 推进相位（每 tick 都要算：闪烁是 tick 级的现象）。
        if (this.blinking) {
            final long elapsed = now - this.blinkStart;
            if (elapsed >= BLINK_TOTAL_TICKS) {
                this.blinking = false;
                this.lit = this.doorOpen;
            } else {
                this.lit = blinkLit(elapsed);
            }
        } else {
            this.lit = this.initialised && this.doorOpen;
        }

        if (--this.scanCountdown > 0) {
            return;
        }
        this.scanCountdown = SCAN_INTERVAL_TICKS;

        final PsdDoorProbe.Scan scan = this.doorTracker.scan(world, pos);
        if (!scan.anyDoor) {
            // 周围没有门（还没放 / 被拆了 / 区块没加载）：保持上一次状态，什么也不做。
            return;
        }
        // 闪完停在哪个状态：门还在关（open 值在降）就已经算「关」—— 这就是提前切入的那一段。
        final boolean target = scan.open && !scan.closing;
        if (!this.initialised) {
            // 第一次读数：直接对齐，不补闪（重进世界时不该闪一下）。
            this.initialised = true;
            this.doorOpen = target;
            this.lit = target;
            return;
        }
        if (target == this.doorOpen) {
            return;
        }
        // 状态切换：进入闪烁，从亮相开始。关门时这一 tick 就是「门刚动」的那一 tick。
        this.doorOpen = target;
        startBlink(now);
    }

    /** 从亮相开始一段新闪烁（{@link #BLINK_TOTAL_TICKS} 之后由 tick 切成常亮 / 熄灭）。 */
    private void startBlink(final long now) {
        this.blinking = true;
        this.blinkStart = now;
        this.lit = true;
    }

    // ------------------------------------------------------------------ 给渲染器

    /** 警示灯这一 tick 该不该亮（{@code true} = 画 {@code light2} 那一段，{@code false} = 画 {@code light}）。 */
    public boolean isLit() {
        return this.lit;
    }

    /** 旁边的门开着吗（闪完之后停在这个状态；调试用）。 */
    public boolean isDoorOpen() {
        return this.doorOpen;
    }

    /** 正在闪吗（调试用）。 */
    public boolean isBlinking() {
        return this.blinking;
    }
}
