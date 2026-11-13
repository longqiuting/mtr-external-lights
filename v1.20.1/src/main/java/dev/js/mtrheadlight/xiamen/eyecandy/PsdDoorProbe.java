// 「这块装饰方块旁边有没有开着的屏蔽门」—— 给 BRT 屏蔽门收纳机的警示灯用。
package dev.js.mtrheadlight.xiamen.eyecandy;

import java.util.Locale;

import mtr.block.BlockPSDAPGDoorBase;
import mtr.block.BlockPSDAPGDoorBase.TileEntityPSDAPGDoorBase;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 扫一眼方块周围 {@code 3 × 3 × 3}（去掉自己），回答「有没有开着的屏蔽门」。
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
 * <b>再看 MTR 的方块实体</b>（MTR 的 PSD 门只有这一条路）。两条都不成立 = 这个邻居不是门。
 *
 * <h2>三态返回</h2>
 * <p>{@code null} 表示周围<b>一个门都没找到</b>（比如门还没放、门被拆了），调用方据此「保持上一次状态」，
 * 而不是把灯硬判成「关」；{@code TRUE} / {@code FALSE} 才是「找到了门，且它开着 / 关着」。
 */
public final class PsdDoorProbe {

    /** blockstate 上可能表示「开着」的属性名（全小写比较）。 */
    private static final String[] OPEN_PROPERTIES = {"open", "door_open", "is_open", "opened"};

    private PsdDoorProbe() {
    }

    /**
     * 周围有没有开着的门。
     *
     * @return {@code TRUE} 有开着的门 / {@code FALSE} 有门但全关着 / {@code null} 周围没有门（或方块未加载）
     */
    public static Boolean anyDoorOpen(final World world, final BlockPos center) {
        if (world == null) {
            return null;
        }
        boolean found = false;
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
                    final Boolean open = openOf(world, pos, state);
                    if (open == null) {
                        continue;
                    }
                    if (open) {
                        return Boolean.TRUE;
                    }
                    found = true;
                }
            }
        }
        return found ? Boolean.FALSE : null;
    }

    /** 这一个方块的开关状态；{@code null} = 它不是门。 */
    private static Boolean openOf(final World world, final BlockPos pos, final BlockState state) {
        final Boolean fromProperties = openFromProperties(state);
        if (fromProperties != null) {
            return fromProperties;
        }
        // MTR 的 PSD 门（含方速继承的那一族）：状态在方块实体里。
        if (state.getBlock() instanceof BlockPSDAPGDoorBase) {
            final BlockEntity entity = world.getBlockEntity(pos);
            if (entity instanceof TileEntityPSDAPGDoorBase door) {
                return door.isOpen();
            }
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * 从 blockstate 上找「open 类」属性。
     *
     * @return 找到时返回它的值；没有这类属性返回 {@code null}
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Boolean openFromProperties(final BlockState state) {
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
                return bool;
            }
            if (value instanceof Integer number) {
                return number != 0;
            }
            if (value instanceof Enum<?> constant) {
                final String constantName = constant.name().toLowerCase(Locale.ROOT);
                return !constantName.equals("closed") && !constantName.equals("false")
                        && !constantName.equals("none");
            }
        }
        return null;
    }
}
