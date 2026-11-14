package dev.js.mtrheadlight;

import mtr.data.Siding;

/** Compatibility indirection: what this build can read off an MTR {@link Siding}. */
final class SidingInfo {

    private SidingInfo() {
    }

    /** Car count of the train configured on this siding. MTR 3.2.x has no such getter. */
    public static int cars(final Siding siding) {
        return 0;
    }
}
