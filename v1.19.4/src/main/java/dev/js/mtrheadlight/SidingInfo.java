package dev.js.mtrheadlight;

import mtr.data.Siding;

/** Fields that only exist in some MTR versions. */
public final class SidingInfo {
    private SidingInfo() {
    }

    /**
     * Car count of the train configured on this siding.
     *
     * <p>MTR 3.6.x has {@code Siding#getTrainCars()}; 3.2.x (this version line) has no such getter,
     * in which case {@link MtrCompatValues#trainCars} answers 0 - same as before the port, and the
     * callers already render "unknown" for 0.
     */
    public static int cars(final Siding siding) {
        return MtrCompatValues.trainCars(siding);
    }
}
