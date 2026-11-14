package dev.js.mtrheadlight;

import mtr.block.BlockPSDAPGDoorBase;
import mtr.data.Siding;

import java.lang.reflect.Method;

/**
 * MTR 3.2.2 ↔ 3.6.x 的取值垫片（1.19.4 / 1.19.2 / 1.18.2 线用 3.2.2，1.20.1 用 3.6.x）。
 *
 * <h2>为什么用反射而不是直接调</h2>
 * <p>3.2.2 没有 {@code getOpenValue()} / {@code Siding#getTrainCars()}：前者只有
 * {@code getOpen(float delta)}（float，量纲与 {@code BlockPSDAPGDoorBase.MAX_OPEN_VALUE} 相同，
 * 0 = 关到头、{@code MAX_OPEN_VALUE} = 开到头），后者根本没有（3.2.x 的车厢数是
 * 车厂里那列车自己的事，siding 不记）。这里先试新方法、退回到旧方法，两条线都成立；
 * 两条都不一样时给一个「关着 / 0 辆」的安全答案，功能本身不丢。
 */
public final class MtrCompatValues {
    private static Method openValue;
    private static boolean openValueFailed;

    private MtrCompatValues() {
    }

    /**
     * MTR PSD 门的 open 值（{@code 0..BlockPSDAPGDoorBase.MAX_OPEN_VALUE}）。
     *
     * @param door  MTR {@code BlockPSDAPGDoorBase.TileEntityPSDAPGDoorBase} 实例
     * @param delta 只有退回 3.2.2 的 {@code getOpen(float)} 时才用得上（1.0f = 不插值）
     */
    public static int openValue(final BlockPSDAPGDoorBase.TileEntityPSDAPGDoorBase door,
                                final float delta) {
        if (door == null) {
            return 0;
        }
        if (!openValueFailed) {
            try {
                if (openValue == null) {
                    openValue = BlockPSDAPGDoorBase.TileEntityPSDAPGDoorBase.class
                            .getMethod("getOpenValue");
                }
                final Object v = openValue.invoke(door);
                if (v instanceof Number) {
                    return ((Number) v).intValue();
                }
            } catch (Throwable ignored) {
                // 3.2.2 没有这个方法，走下面的旧 API。
                openValueFailed = true;
            }
        }
        try {
            // 3.2.2：getOpen(float delta) 返回与 MAX_OPEN_VALUE 同量纲的浮点值。
            return Math.round(door.getOpen(delta));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 车厂里这列车配置的车厢数；3.2.x 没有这个信息时返回 0（调用方本来就要处理「不知道」）。 */
    public static int trainCars(final Siding siding) {
        if (siding == null) {
            return 0;
        }
        try {
            final Method m = Siding.class.getMethod("getTrainCars");
            final Object v = m.invoke(siding);
            if (v instanceof Number) {
                return ((Number) v).intValue();
            }
        } catch (Throwable ignored) {
            // 3.2.2：Siding 不记车厢数。
        }
        return 0;
    }
}
