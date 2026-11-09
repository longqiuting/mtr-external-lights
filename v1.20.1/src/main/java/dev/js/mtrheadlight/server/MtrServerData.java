package dev.js.mtrheadlight.server;

import dev.js.mtrheadlight.LteLog;
import mtr.data.RailwayData;
import net.minecraft.world.World;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 服务端唯一一份「所有 MTR 轨道」的入口。
 *
 * <h2>为什么需要它</h2>
 * 客户端从 {@code mtr.client.ClientData.RAILS} 拿轨道表；服务端没有那个类，而
 * {@code mtr.data.RailwayData} 又没有任何公开的「按世界取实例」入口（它是
 * {@code PersistentStateMapper}，实例由 MTR 自己在 {@code MinecraftServer} 的 tick 里持有）。
 *
 * <p>但 {@code RailwayData#simulateTrains()} 每 tick 每维度都会被调用一次 —— 那正好是唯一稳定、
 * 公开、且在 {@code RailwayData} 自己身上的入口。{@code RailwayDataCaptureMixin} 在它的 HEAD 把
 * {@code this} 登记进来；这里再按「它属于哪个世界」建索引，{@code getRailsMap()} 就是轨道表。
 *
 * <h2>世界怎么拿</h2>
 * {@code RailwayData.world} 是 {@code private final World}，没有 getter，所以用一次反射缓存
 * {@link Field}。MTR 未 remap，字段名就是源码名；取不到（换版本）时整块功能静默关闭
 * （{@link #rails(World)} 返回 {@code null}，道床对其它实体不生效，列车碰撞完全不受影响）。
 */
public final class MtrServerData {

    /** 世界 → 它的 RailwayData（弱引用：维度卸载后自然回收）。 */
    private static final Map<World, RailwayData> BY_WORLD = new WeakHashMap<>();

    private static volatile Field worldField;
    private static volatile boolean probed;
    private static volatile boolean warned;

    private MtrServerData() {
    }

    /** 由 mixin 调用：这个维度的 RailwayData 这一 tick 跑过了。 */
    public static void capture(final RailwayData data) {
        if (data == null) {
            return;
        }
        try {
            final Field field = worldField();
            if (field == null) {
                return;
            }
            final Object world = field.get(data);
            if (world instanceof World w) {
                synchronized (BY_WORLD) {
                    BY_WORLD.put(w, data);
                }
            }
        } catch (Throwable t) {
            warnOnce(t);
        }
    }

    /**
     * 这个世界的轨道表（{@code Map<BlockPos, Map<BlockPos, Rail>>}），拿不到返回 {@code null}。
     *
     * <p>返回 {@code Object}：{@code getRailsMap()} 的泛型实参在编译期是 intermediary 名，
     * 而 {code RailBed} 只按 {@code Map<?, ?>} 用，不需要具名类型。
     */
    public static Object rails(final World world) {
        if (world == null) {
            return null;
        }
        final RailwayData data;
        synchronized (BY_WORLD) {
            data = BY_WORLD.get(world);
        }
        if (data == null) {
            return null;
        }
        try {
            return data.getRailsMap();
        } catch (Throwable t) {
            warnOnce(t);
            return null;
        }
    }

    /** 维度卸载 / 重载：把索引清掉（下次 simulateTrains 会重新填）。 */
    public static void reset() {
        synchronized (BY_WORLD) {
            BY_WORLD.clear();
        }
    }

    private static Field worldField() {
        if (probed) {
            return worldField;
        }
        probed = true;
        try {
            final Field field = RailwayData.class.getDeclaredField("world");
            field.setAccessible(true);
            worldField = field;
        } catch (Throwable t) {
            warnOnce(t);
        }
        return worldField;
    }

    private static void warnOnce(final Throwable t) {
        if (warned) {
            return;
        }
        warned = true;
        LteLog.LOGGER.warn("[lte] trackbed: could not read MTR railway data - "
                + "trackbed collision for non-player entities is off (train collision unaffected)", t);
    }
}
