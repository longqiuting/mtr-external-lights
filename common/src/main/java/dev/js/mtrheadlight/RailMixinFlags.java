package dev.js.mtrheadlight;

/**
 * Tiny, dependency-free record of which rail mixin landed and whether its injection really matched.
 *
 * <p>Deliberately has <b>no imports</b>: it is written from the mixin plugins' {@code postApply},
 * i.e. while a host class is being transformed. Touching anything that pulls in Minecraft, the
 * config or the logger at that moment is what freezes classes before other mods get to mix into
 * them. Two booleans and two setters cannot do that.
 *
 * <p>{@code *Matched} is the important one: a mixin with {@code require = 0} still counts as
 * "applied" when none of its injectors found their target, which is exactly how this feature was
 * silently dead on 1.19.4 before. The plugins therefore scan the transformed class for the call our
 * {@code @ModifyConstant} handler injects and only then mark the host as matched.
 *
 * <p>Read by {@link RailChunkBuildBoost#report()} at client init and once per tick, so the boot log
 * answers "which host's mixins are live in <em>this</em> instance" without guessing from class
 * presence.
 */
public final class RailMixinFlags {
    private static volatile boolean anteApplied;
    private static volatile boolean anteMatched;
    private static volatile boolean nteApplied;
    private static volatile boolean nteMatched;

    private RailMixinFlags() {
    }

    /** Called from {@code AnteMixinPlugin#postApply} for the rail build mixin. */
    public static void noteAnte(final boolean matched) {
        anteApplied = true;
        anteMatched = matched;
    }

    /** Called from {@code NteMixinPlugin#postApply} for the rail build mixin. */
    public static void noteNte(final boolean matched) {
        nteApplied = true;
        nteMatched = matched;
    }

    /** True when the ANTE rail-build mixin was applied to ANTE's dispatcher. */
    public static boolean anteApplied() {
        return anteApplied;
    }

    /** True when the ANTE rail-build mixin was applied and its {@code iconst_1} was replaced. */
    public static boolean anteMatched() {
        return anteMatched;
    }

    /** True when the NTE rail-build mixin was applied to NTE's dispatcher. */
    public static boolean nteApplied() {
        return nteApplied;
    }

    /** True when the NTE rail-build mixin was applied and its {@code iconst_1} was replaced. */
    public static boolean nteMatched() {
        return nteMatched;
    }

    /** True when any host's boost injection is live. */
    public static boolean anyMatched() {
        return anteMatched || nteMatched;
    }

    /** True when a rail mixin was applied but its constant did not get rewritten. */
    public static boolean appliedButNotMatched() {
        return (anteApplied && !anteMatched) || (nteApplied && !nteMatched);
    }
}
