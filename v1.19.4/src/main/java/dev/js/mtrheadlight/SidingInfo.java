package dev.js.mtrheadlight;

import mtr.data.Siding;

/** Fields that only exist in some MTR versions. */
public final class SidingInfo {
    private SidingInfo() {
    }

    /** Car count of the train configured on this siding. MTR 3.2.x has no such getter. */
    public static int cars(final Siding siding) {
        return 0;
    }
}
