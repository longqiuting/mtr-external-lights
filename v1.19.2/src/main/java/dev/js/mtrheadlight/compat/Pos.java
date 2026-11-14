package dev.js.mtrheadlight.compat;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

/** 1.20.1 的 {@code BlockPos#ofFloored(double, double, double)}（1.19.3 才加）在 1.19.2 上的替身。 */
public final class Pos {

    private Pos() {
    }

    /** 三个 double 各向下取整成一个 {@link BlockPos}。 */
    public static BlockPos ofFloored(final double x, final double y, final double z) {
        return new BlockPos(MathHelper.floor(x), MathHelper.floor(y), MathHelper.floor(z));
    }
}
