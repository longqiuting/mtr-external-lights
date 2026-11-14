package dev.js.mtrheadlight;

import java.lang.reflect.Method;

import mtr.data.Siding;

/**
 * Fields that only exist in some MTR versions.
 *
 * <p>MTR 3.6.x has {@code Siding#getTrainCars()}; 3.2.2 (the 1.18.2 line) has no such getter — the car
 * count there only exists inside the train resource, so this returns {@code 0} and every caller treats
 * it as "unknown" (the sidings list simply omits the "· N 节" part). Reflection rather than a direct
 * call so the same source compiles against either MTR generation.
 */
public final class SidingInfo {

    private static Method trainCars;
    private static boolean trainCarsFailed;

    private SidingInfo() {
    }

    /** Car count of the train configured on this siding (MTR 3.6.x only; older versions report 0). */
    public static int cars(final Siding siding) {
        if (siding == null || trainCarsFailed) {
            return 0;
        }
        try {
            if (trainCars == null) {
                trainCars = Siding.class.getMethod("getTrainCars");
            }
            final Object value = trainCars.invoke(siding);
            return value instanceof Integer count ? count.intValue() : 0;
        } catch (Throwable t) {
            // 3.2.2 没有这个方法：一次之后就不再试，永不抛进调用方。
            trainCarsFailed = true;
            return 0;
        }
    }
}
