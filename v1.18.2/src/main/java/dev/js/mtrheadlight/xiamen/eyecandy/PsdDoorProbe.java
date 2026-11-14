// 「这块装饰方块旁边有没有门、门正开到什么程度」—— 给 BRT 屏蔽门收纳机的警示灯用。
package dev.js.mtrheadlight.xiamen.eyecandy;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import mtr.block.BlockPSDAPGDoorBase;
import mtr.block.BlockPSDAPGDoorBase.TileEntityPSDAPGDoorBase;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 扫一眼方块周围 {@code 3 × 3 × 3}（去掉自己），回答「有没有门、开着没有、<b>是不是正在关</b>」。
 *
 * <h2>为什么不硬编码某一家的方块类</h2>
 * <p>收纳机旁边站着的门可能是：
 * <ul>
 *   <li>MTR 原版 / 方速滑动门（{@link BlockPSDAPGDoorBase} 那一族）—— 它们<b>没有</b> blockstate
 *       属性，开关状态在方块实体里（{@link TileEntityPSDAPGDoorBase#isOpen()}）；</li>
 *   <li>方速端门（{@code FangsuPsdEndDoorBlock}）—— 走 blockstate 的 {@code open} 属性；</li>
 *   <li>将来别的门 —— 只要属性名是 {@code open} / {@code door_open} / {@code is_open} /
 *       {@code opened}（布尔、整型、枚举都认）就能自动接上，不用改这里。</li>
 * </ul>
 * 所以顺序是：<b>先看 blockstate 上的「open 类」属性</b>（便宜、跨方块类），
 * <b>再看 MTR 的方块实体</b>（MTR 的 PSD 门只有这一条路）。
 *
 * <h2>「门正在关」怎么判（关门提前闪的依据）</h2>
 * <p>MTR 的 {@link TileEntityPSDAPGDoorBase} 里 {@code open} 是 {@code 0..32} 的<b>整数中间态</b>
 * （服务端按 {@code doorValue × 64} 夹到 {@code 0..32} 逐 tick 写进去，客户端同步；
 * {@code isOpen()} 只是 {@code open > 0}）。所以关门动画的整段时间里 {@code isOpen()} 还是
 * {@code true} —— 只看布尔值只能等门<b>完全合上</b>才拿到「关」，警示灯自然就晚了。
 *
 * <p>这里改用 3.2.2 的 {@code getOpen(float)}（<b>归一化 0..1</b>，见
 * {@code BlockPSDAPGDoorBase$TileEntityPSDAPGDoorBase#getOpen} 的 {@code openClient / 32}）
 * 乘回 {@code MAX_OPEN_VALUE} 得到 0..32 的刻度，记下每扇门上一次的 open 值：
 * <b>值在变小 = 正在关</b>（{@link Scan#closing}）。拿到中间态就能在门刚开始动的那一 tick 起闪。
 * 只认「变小」，不需要知道满值是不是 32，也不依赖某一版 MTR 的常量。
 *
 * <p>拿不到中间态的门（只有 blockstate 布尔属性的端门）退化成<b>边沿</b>：{@code 1 → 0} 也是
 * 「变小」，所以它翻成关的那一 tick 同样算 {@code closing} —— 立即起闪，不额外等一个扫描周期
 * （扫描周期见 {@code EyecandyBlockEntity#SCAN_INTERVAL_TICKS}）。
 *
 * <h2>三态返回</h2>
 * <p>{@link Scan#anyDoor} 为 {@code false} 表示周围<b>一个门都没找到</b>（门还没放 / 被拆了），
 * 调用方据此「保持上一次状态」，而不是把灯硬判成「关」。
 */
public final class PsdDoorProbe {

    /** blockstate 上可能表示「开着」的属性名（全小写比较）。 */
    private static final String[] OPEN_PROPERTIES = {"open", "door_open", "is_open", "opened"};

    private PsdDoorProbe() {
    }

    /**
     * 一次扫描的结果。
     *
     * <p>{@link #open} 与 {@link #closing} 可以同时为真：关门动画途中门还是「开着」的
     * （open 值 {@code > 0}），但已经在往关上走 —— 那正是要提前闪的时刻。
     */
    public static final class Scan {

        /** 周围一个门都没有（方块未加载 / 门被拆了）—— 调用方保持上一次状态。 */
        public static final Scan NONE = new Scan(false, false, false);

        /** 周围至少有一扇门。 */
        public final boolean anyDoor;

        /** 至少一扇门的 open 值 {@code > 0}（或 blockstate 属性为真）。 */
        public final boolean open;

        /** 至少一扇门的 open 值在变小 = 正在关。 */
        public final boolean closing;

        private Scan(final boolean anyDoor, final boolean open, final boolean closing) {
            this.anyDoor = anyDoor;
            this.open = open;
            this.closing = closing;
        }
    }

    /**
     * 带「上一次读数」的扫描器：想判出「正在关」就必须有前后两帧的对比，所以状态挂在
     * 调用方（每个方块实体一个实例），而不是塞进静态表 —— 方块被拆 / 区块卸载时一起丢掉，
     * 不会留下永远清不掉的 {@link BlockPos}。
     *
     * <p>两张 map 每轮互换内容，最多 26 项，不随游戏时间增长。
     */
    public static final class Tracker {

        /** 上一轮每扇门的 open 值。 */
        private final Map<BlockPos, Integer> previous = new HashMap<>(8);

        /** 本轮每扇门的 open 值（扫完拷进 {@link #previous}）。 */
        private final Map<BlockPos, Integer> current = new HashMap<>(8);

        /**
         * 扫周围一圈。
         *
         * @param world  方块所在世界（{@code null} → {@link Scan#NONE}）
         * @param center 收纳机自己的位置（不参与判定）
         */
        public Scan scan(final World world, final BlockPos center) {
            if (world == null || center == null) {
                return Scan.NONE;
            }
            this.current.clear();
            boolean open = false;
            boolean closing = false;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        final BlockPos pos = center.add(dx, dy, dz);
                        // 跨区块时不把邻居区块强行读进来（未加载的区块里没有门可看）。
                        if (!world.isChunkLoaded(pos)) {
                            continue;
                        }
                        final BlockState state = world.getBlockState(pos);
                        if (state.isAir()) {
                            continue;
                        }
                        final Integer value = openValueOf(world, pos, state);
                        if (value == null) {
                            continue;
                        }
                        final int now = value.intValue();
                        this.current.put(pos, Integer.valueOf(now));
                        if (now > 0) {
                            open = true;
                        }
                        final Integer before = this.previous.get(pos);
                        if (before != null && now < before.intValue()) {
                            // open 值在变小：门正在关（MTR 0..32 的中间态，或布尔门的 1 → 0 沿）。
                            closing = true;
                        }
                    }
                }
            }
            this.previous.clear();
            this.previous.putAll(this.current);
            if (this.current.isEmpty()) {
                return Scan.NONE;
            }
            return new Scan(true, open, closing);
        }
    }

    /**
     * 这一扇门的 open 值；{@code null} = 它不是门。
     *
     * <p>blockstate 属性那条路只能是 0 / 1（布尔门没有中间态）；MTR 的方块实体那条路是
     * 真正的 {@code 0..32} 整数。
     */
    private static Integer openValueOf(final World world, final BlockPos pos,
                                       final BlockState state) {
        final Integer fromProperties = openFromProperties(state);
        if (fromProperties != null) {
            return fromProperties;
        }
        // MTR 的 PSD 门（含方速继承的那一族）：状态在方块实体里。
        if (state.getBlock() instanceof BlockPSDAPGDoorBase) {
            final BlockEntity entity = world.getBlockEntity(pos);
            if (entity instanceof TileEntityPSDAPGDoorBase door) {
                /*
                 * 量纲：3.2.2 的 {@code getOpen(float)} 返回的是<b>归一化 0..1</b>
                 * （{@code openClient / 32}），而这里的调用方要的是「MTR 那套 0..32 的原始刻度」
                 * （见方法注释：只有「值在变小」这个沿有意义，0/1 两档会丢掉中间态）。
                 * 所以乘回 MAX_OPEN_VALUE，而不是 {@code Math.round(getOpen(...))} —— 那样只会
                 * 得到 0 或 1，关门动画中间那 30 个刻度全被压平，探针判「正在关门」就不准了。
                 */
                return Integer.valueOf(Math.round(
                        door.getOpen(1.0F) * (float) BlockPSDAPGDoorBase.MAX_OPEN_VALUE));
            }
            return Integer.valueOf(0);
        }
        return null;
    }

    /**
     * 从 blockstate 上找「open 类」属性，归一成 0 / 1。
     *
     * @return 找到时返回它的值；没有这类属性返回 {@code null}
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Integer openFromProperties(final BlockState state) {
        for (final Property<?> property : state.getProperties()) {
            final String name = property.getName().toLowerCase(Locale.ROOT);
            boolean matches = false;
            for (final String candidate : OPEN_PROPERTIES) {
                if (candidate.equals(name)) {
                    matches = true;
                    break;
                }
            }
            if (!matches) {
                continue;
            }
            final Comparable<?> value = state.get((Property) property);
            if (value instanceof Boolean bool) {
                return Integer.valueOf(bool.booleanValue() ? 1 : 0);
            }
            if (value instanceof Integer number) {
                return Integer.valueOf(Math.max(0, number.intValue()));
            }
            if (value instanceof Enum<?> constant) {
                final String constantName = constant.name().toLowerCase(Locale.ROOT);
                final boolean closed = constantName.equals("closed") || constantName.equals("false")
                        || constantName.equals("none");
                return Integer.valueOf(closed ? 0 : 1);
            }
        }
        return null;
    }
}
