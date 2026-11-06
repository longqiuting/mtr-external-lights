package dev.js.mtrheadlight;

import mtr.data.Siding;

/** Fields that only exist in some MTR versions. */
public final class SidingInfo {
    private SidingInfo() {
    }

    /** Car count of the train configured on this siding (MTR 3.6.x only; older versions report 0). */
    public static int cars(final Siding siding) {
        try {
            return siding.getTrainCars();
        } catch (Throwable t) {
            return 0;
        }
    }
}
