package mtr.path;

/** Stub mirroring mtr/path/PathData (public final mtr.data.Rail rail). */
public class PathData {
    public final mtr.data.Rail rail;
    public final int stopIndex = 0;

    public PathData() {
        this(new mtr.data.Rail());
    }

    public PathData(mtr.data.Rail rail) {
        this.rail = rail;
    }
}
