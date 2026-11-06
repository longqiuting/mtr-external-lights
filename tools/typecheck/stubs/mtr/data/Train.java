package mtr.data;

import mtr.path.PathData;

import java.util.ArrayList;
import java.util.List;

/**
 * Stub mirroring the public surface of mtr/data/Train used by the mod.
 * The real fields are final; here they are writable so the offline pose harness
 * ({@code tools/typecheck/src/PoseCheck.java}) can build a synthetic train.
 */
public class Train {
    public final String trainId = "stub:0";
    public final TransportMode transportMode = TransportMode.TRAIN;
    public int spacing;
    public int trainCars;
    public List<PathData> path = new ArrayList<>();
    protected double railProgress;

    public final double getRailProgress() {
        return this.railProgress;
    }

    public final boolean isReversed() {
        return false;
    }

    public final int getIndex(double progress, boolean reversed) {
        return 0;
    }

    public void setRailProgress(double value) {
        this.railProgress = value;
    }
}
