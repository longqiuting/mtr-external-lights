package mtr.data;

/** Stub mirroring mtr/data/TransportMode enum fields used by the mod. */
public enum TransportMode {
    TRAIN(0),
    BOAT(0),
    CABLE_CAR(0),
    AIRPLANE(0);

    public final int railOffset;

    TransportMode(int railOffset) {
        this.railOffset = railOffset;
    }
}
