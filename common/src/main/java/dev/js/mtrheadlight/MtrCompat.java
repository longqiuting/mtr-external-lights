package dev.js.mtrheadlight;

import mtr.data.Rail;
import mtr.data.RailwayData;
import net.minecraft.util.math.BlockPos;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;

/**
 * MTR 3.2.2 / 3.6.x 的小差异垫片（版本无关，放在 common）。
 *
 * <p>唯一需要垫的地方是轨道表：1.20.1 的 MTR 3.6.x 有公开的
 * {@code RailwayData.getRailsMap()}，而 1.19.x 用的 3.2.2 只有私有的
 * {@code rails} 字段、没有 getter。两边的字段名都叫 {@code rails}，所以先试 getter、
 * 再退回反射读字段，两边都成立；反射失败时返回空表（调用方本来就要处理「没有轨道」）。
 */
public final class MtrCompat {
    private static Field railsField;
    private static boolean railsFieldFailed;

    private MtrCompat() {
    }

    /** {@code Map<BlockPos, Map<BlockPos, Rail>>} 形式的轨道表；不可用时返回空表。 */
    @SuppressWarnings("unchecked")
    public static Map<BlockPos, Map<BlockPos, Rail>> railsMap(final RailwayData data) {
        if (data == null) {
            return Collections.emptyMap();
        }
        // 1.20.1 线：直接用公开 getter。
        try {
            final Method m = RailwayData.class.getMethod("getRailsMap");
            final Object v = m.invoke(data);
            if (v instanceof Map) {
                return (Map<BlockPos, Map<BlockPos, Rail>>) v;
            }
        } catch (Throwable ignored) {
            // 3.2.2 没有这个方法，走反射。
        }
        if (!railsFieldFailed) {
            try {
                if (railsField == null) {
                    railsField = RailwayData.class.getDeclaredField("rails");
                    railsField.setAccessible(true);
                }
                final Object v = railsField.get(data);
                if (v instanceof Map) {
                    return (Map<BlockPos, Map<BlockPos, Rail>>) v;
                }
            } catch (Throwable t) {
                railsFieldFailed = true;
                LteLog.LOGGER.warn("[lte] RailwayData.rails unavailable: {}", String.valueOf(t));
            }
        }
        return Collections.emptyMap();
    }
}
