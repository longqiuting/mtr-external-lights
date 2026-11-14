// 端门 / 滑动门 / 玻璃板共用的「开合进度插值」——按时间推进，与帧率无关。
package dev.js.mtrheadlight.xiamen.fangsu;

/**
 * 开合进度 0..1 的插值器（客户端每帧调一次 {@link #update}）。
 *
 * <h2>为什么不直接用 MTR 的 {@code getOpen(float)}</h2>
 * <p>MTR 的 {@code TileEntityPSDAPGDoorBase#getOpen(float)} 是「每调用一次推进 0.95」—— 与帧率挂钩：
 * 60 fps 约 0.56 s 走完，144 fps 只剩 0.23 s（看起来就是瞬开）。这里改成<b>按时间推进</b>：
 * {@code progress += dt / MOVE_TICKS}，{@code dt} 用 {@code world.getTime() + tickDelta} 的差值，
 * 所以 60 / 144 / 240 fps 以及掉帧时都是同一个速度（{@value #MOVE_TICKS} tick ≈ 0.35 s 走完），
 * 并且每帧都平滑（不是按 tick 跳）。
 *
 * <p>两个方块实体（{@code FangsuPsdDoorEntity} 自动门 / {@code FangsuPsdEndDoorEntity} 端门）
 * 共用这一份，所以两种门的开合手感永远一致。
 */
final class FangsuPsdProgress {

    /**
     * 全行程耗时（tick）。<b>调速度就改这一个数</b>。
     *
     * <p>MTR 自己那套是按帧推进（60 fps ≈ 0.57 s），方速是 500 ms。原来写 15 tick（0.75 s），
     * 用户反馈「太慢」；7 tick = 0.35 s，90° 摆过去的手感和真实端门更接近。
     */
    static final float MOVE_TICKS = 7.0F;

    /** 当前进度 0..1。 */
    private double progress;

    /** 上一次采样用的时间（tick，相对 {@link #origin}）。 */
    private double lastSample = Double.NaN;

    /**
     * 时间原点（世界时间的 tick 数）。
     *
     * <p>为什么不直接拿 {@code world.getTime()} 当时间轴：那是<b>世界总 tick 数</b>，
     * 老存档能到上亿，塞进 {@code float} 只剩几百 tick 的分辨率 —— 动画会一顿一顿甚至完全不动。
     * 所以这里减掉第一次渲染时的时间，后面只在「几十 tick」的量级上算差值。
     */
    private long origin = Long.MIN_VALUE;

    /**
     * 朝目标值推进一帧，返回新的进度。
     *
     * @param target    目标进度 0..1（关门 0 / 开到底 1）
     * @param ticks     {@code world.getTime()}
     * @param tickDelta 当前帧的 partialTick
     */
    float update(final float target, final long ticks, final float tickDelta) {
        if (this.origin == Long.MIN_VALUE) {
            this.origin = ticks;
        }
        final double now = (double) (ticks - this.origin) + (double) tickDelta;
        final float clamped = Math.max(0.0F, Math.min(1.0F, target));
        if (Double.isNaN(this.lastSample)) {
            // 第一帧：直接对齐（方块刚加载时不该从 0 慢慢动起来）。
            this.lastSample = now;
            this.progress = clamped;
            return (float) this.progress;
        }
        // 掉帧 / 区块重载时 dt 可能很大：夹住，免得一帧跳完整程。
        final double dt = Math.max(0.0D, Math.min(4.0D, now - this.lastSample));
        this.lastSample = now;
        final double step = dt / MOVE_TICKS;
        final double delta = (double) clamped - this.progress;
        if (Math.abs(delta) <= step) {
            this.progress = clamped;
        } else {
            this.progress += Math.signum(delta) * step;
        }
        return (float) this.progress;
    }
}
