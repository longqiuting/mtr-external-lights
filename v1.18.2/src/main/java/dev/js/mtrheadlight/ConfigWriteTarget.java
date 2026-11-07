package dev.js.mtrheadlight;

import java.util.function.Supplier;

/**
 * <b>The only place the config screens write configuration.</b>
 *
 * <p>A screen builds one of these in {@code init()}:
 * <ul>
 *   <li>{@link #global(HeadlightConfig)} - {@link ConfigScreen}, every write lands on a field of
 *       the global {@link HeadlightConfig};</li>
 *   <li>{@link #siding(HeadlightConfig, long)} - {@link SidingDetailScreen}, every write lands on
 *       that siding's {@link HeadlightConfig.SidingSettings} entry. <b>It has no code path that
 *       assigns a global field at all.</b></li>
 * </ul>
 *
 * <h2>Why this class exists</h2>
 * The old screens funnelled every control through one method with an {@code if (siding == null)}
 * branch. The siding half did write the override, but the two halves lived in the same method and
 * the same object, and a {@code null} siding - which {@link SidingScreen} used to hand out whenever
 * {@code ClientData.SIDINGS} contained a null entry - silently turned the detail screen into a
 * <em>global</em> editor: every slider and switch then wrote {@code HeadlightConfig} instead of
 * {@code sidingSettings[id]}. That is the "按侧线改配置，全局跟着变" report.
 *
 * <p>Splitting the two targets into two classes makes that failure mode unrepresentable:
 * {@link SidingTarget} never sees the global fields, {@link #siding} rejects id {@code 0}
 * (a casing id would silently mean "global" in {@code effectiveFor}), and
 * {@link SelfCheck#run()} proves the isolation on a real {@link HeadlightConfig}.
 *
 * <p>No Minecraft imports on purpose: this file is compiled and exercised by
 * {@code tools/verify-config-write.py} outside the game.
 */
abstract class ConfigWriteTarget {
    /** Numeric parameters, in the order the screens list them. */
    static final int DISTANCE = 0;
    static final int LUMINANCE = 1;
    static final int HEAD_LENGTH = 2;
    static final int BEAM_DISTANCE = 3;
    static final int BEAM_HEAD_LENGTH = 4;
    static final int BEAM_TAIL_LENGTH = 5;
    static final int BEAM_WIDTH = 6;
    static final int BEAM_HEAD_HEIGHT = 7;
    static final int BEAM_TAIL_HEIGHT = 8;
    static final int[] ALL_VALUES = {
            DISTANCE, LUMINANCE, HEAD_LENGTH, BEAM_DISTANCE, BEAM_HEAD_LENGTH,
            BEAM_TAIL_LENGTH, BEAM_WIDTH, BEAM_HEAD_HEIGHT, BEAM_TAIL_HEIGHT,
    };

    /** The three-level switch hierarchy: master -> (dynamic light | light band). */
    static final int MASTER = 0;
    static final int DYNAMIC_LIGHT = 1;
    static final int LIGHT_BAND = 2;
    static final int[] ALL_TOGGLES = {MASTER, DYNAMIC_LIGHT, LIGHT_BAND};

    /**
     * Resolved on every access, never cached: 取消 swaps the live config instance
     * ({@link MtrHeadlightClient#restoreConfig(String)}), and a screen that stayed open across that
     * swap must write the new instance instead of the dead one.
     */
    final Supplier<HeadlightConfig> cfg;

    ConfigWriteTarget(final Supplier<HeadlightConfig> cfg) {
        if (cfg == null) {
            throw new IllegalArgumentException("cfg == null");
        }
        this.cfg = cfg;
    }

    /** The live config, resolved fresh. */
    final HeadlightConfig config() {
        final HeadlightConfig live = this.cfg.get();
        if (live == null) {
            throw new IllegalStateException("no live config");
        }
        return live;
    }

    static ConfigWriteTarget global(final Supplier<HeadlightConfig> cfg) {
        return new GlobalTarget(cfg);
    }

    static ConfigWriteTarget siding(final Supplier<HeadlightConfig> cfg, final long sidingId) {
        return new SidingTarget(cfg, sidingId);
    }

    /** Written to the log by the self-check; also handy in a crash report. */
    abstract String describe();

    abstract HeadlightConfig.Effective effective();

    abstract double readValue(int which);

    abstract void writeValue(int which, double value);

    abstract boolean readToggle(int which);

    abstract void writeToggle(int which, boolean on);

    abstract int readColor(boolean head);

    abstract void writeColor(boolean head, int rgb);

    abstract boolean followsValue(int which);

    abstract boolean followsToggle(int which);

    abstract boolean followsColor(boolean head);

    /** True while this target has a row that can be reset (detail screen only). */
    abstract boolean hasOverrides();

    /**
     * One row's ↺ button - a single parameter. <b>The two targets mean different things by it</b>,
     * which is the whole point of routing it through this class:
     * <ul>
     *   <li>{@link GlobalTarget}: write that one field back to {@link HeadlightConfig#defaults()},
     *       leaving every other global field alone;</li>
     *   <li>{@link SidingTarget}: drop that one override ({@code field = null}), so the row follows
     *       the global value again. It deliberately does <b>not</b> copy the global default into the
     *       override - that would pin a value the player never chose and stop following a later
     *       change of the global one.</li>
     * </ul>
     */
    abstract void resetValue(int which);

    /** Per-row ↺ for one of the three switches; same global-default / drop-override split. */
    abstract void resetToggle(int which);

    /** Per-row ↺ for one of the two beam colours; same global-default / drop-override split. */
    abstract void resetColor(boolean head);

    /** "恢复为跟随全局": drops every override of this target. No-op for the global target. */
    abstract void reset();

    /**
     * 全部恢复默认 (footer, behind a confirmation screen):
     * <ul>
     *   <li>{@link GlobalTarget}: every global field the screens expose goes back to
     *       {@link HeadlightConfig#defaults()}, <b>and every siding override is dropped</b> - "reset
     *       everything" has to include the per-siding entries, or a siding would keep overriding the
     *       defaults that were just restored;</li>
     *   <li>{@link SidingTarget}: only this siding's entry is dropped (all of its overrides), the
     *       global values are untouched.</li>
     * </ul>
     */
    abstract void resetAll();

    /** Writes the live config to disk. Called only by 完成 / 保存并返回. */
    final void commit() {
        config().save();
    }

    // ---------------------------------------------------------------------------------------------
    // Global target
    // ---------------------------------------------------------------------------------------------

    private static final class GlobalTarget extends ConfigWriteTarget {
        GlobalTarget(final Supplier<HeadlightConfig> cfg) {
            super(cfg);
        }

        @Override
        String describe() {
            return "global";
        }

        @Override
        HeadlightConfig.Effective effective() {
            return config().effectiveFor(0L);
        }

        @Override
        double readValue(final int which) {
            final HeadlightConfig.Effective eff = effective();
            switch (which) {
                case DISTANCE:
                    return eff.maxDistance;
                case LUMINANCE:
                    return eff.headLuminance;
                case HEAD_LENGTH:
                    return eff.headLength;
                case BEAM_DISTANCE:
                    return eff.beamDistance;
                case BEAM_HEAD_LENGTH:
                    return eff.beamHeadLength;
                case BEAM_TAIL_LENGTH:
                    return eff.beamTailLength;
                case BEAM_WIDTH:
                    return eff.beamWidth;
                case BEAM_HEAD_HEIGHT:
                    return eff.beamHeadHeight;
                default:
                    return eff.beamTailHeight;
            }
        }

        @Override
        void writeValue(final int which, final double value) {
            switch (which) {
                case DISTANCE:
                    config().maxDistance = value;
                    break;
                case LUMINANCE:
                    config().headLuminance = (int) Math.round(value);
                    break;
                case HEAD_LENGTH:
                    config().headLength = value;
                    break;
                case BEAM_DISTANCE:
                    config().beamDistance = value;
                    break;
                case BEAM_HEAD_LENGTH:
                    config().beamHeadLength = value;
                    break;
                case BEAM_TAIL_LENGTH:
                    config().beamTailLength = value;
                    break;
                case BEAM_WIDTH:
                    config().beamWidth = value;
                    break;
                case BEAM_HEAD_HEIGHT:
                    config().beamHeadHeight = value;
                    break;
                default:
                    config().beamTailHeight = value;
                    break;
            }
        }

        @Override
        boolean readToggle(final int which) {
            final HeadlightConfig.Effective eff = effective();
            switch (which) {
                case MASTER:
                    return eff.enabled;
                case DYNAMIC_LIGHT:
                    return eff.dynamicLight;
                default:
                    return eff.beamGlow;
            }
        }

        @Override
        void writeToggle(final int which, final boolean on) {
            switch (which) {
                case MASTER:
                    config().enabled = on;
                    break;
                case DYNAMIC_LIGHT:
                    config().dynamicLight = on;
                    break;
                default:
                    config().beamGlow = on;
                    break;
            }
        }

        @Override
        int readColor(final boolean head) {
            return head ? effective().beamHeadColor : effective().beamTailColor;
        }

        @Override
        void writeColor(final boolean head, final int rgb) {
            final String text = HeadlightConfig.formatColor(rgb);
            if (head) {
                config().beamHeadColor = text;
            } else {
                config().beamTailColor = text;
            }
        }

        @Override
        boolean followsValue(final int which) {
            return false;
        }

        @Override
        boolean followsToggle(final int which) {
            return false;
        }

        @Override
        boolean followsColor(final boolean head) {
            return false;
        }

        @Override
        boolean hasOverrides() {
            return false;
        }

        /**
         * The one field this row shows goes back to the shipped default; every other field, and the
         * siding overrides, are left exactly as they are.
         */
        @Override
        void resetValue(final int which) {
            final HeadlightConfig d = HeadlightConfig.defaults();
            final HeadlightConfig c = config();
            switch (which) {
                case DISTANCE:
                    c.maxDistance = d.maxDistance;
                    break;
                case LUMINANCE:
                    c.headLuminance = d.headLuminance;
                    break;
                case HEAD_LENGTH:
                    c.headLength = d.headLength;
                    break;
                case BEAM_DISTANCE:
                    c.beamDistance = d.beamDistance;
                    break;
                case BEAM_HEAD_LENGTH:
                    c.beamHeadLength = d.beamHeadLength;
                    break;
                case BEAM_TAIL_LENGTH:
                    c.beamTailLength = d.beamTailLength;
                    break;
                case BEAM_WIDTH:
                    c.beamWidth = d.beamWidth;
                    break;
                case BEAM_HEAD_HEIGHT:
                    c.beamHeadHeight = d.beamHeadHeight;
                    break;
                default:
                    c.beamTailHeight = d.beamTailHeight;
                    break;
            }
        }

        @Override
        void resetToggle(final int which) {
            final HeadlightConfig d = HeadlightConfig.defaults();
            final HeadlightConfig c = config();
            switch (which) {
                case MASTER:
                    c.enabled = d.enabled;
                    break;
                case DYNAMIC_LIGHT:
                    c.dynamicLight = d.dynamicLight;
                    break;
                default:
                    c.beamGlow = d.beamGlow;
                    break;
            }
        }

        @Override
        void resetColor(final boolean head) {
            final HeadlightConfig d = HeadlightConfig.defaults();
            final HeadlightConfig c = config();
            if (head) {
                c.beamHeadColor = d.beamHeadColor;
            } else {
                c.beamTailColor = d.beamTailColor;
            }
        }

        @Override
        void reset() {
            // Nothing to reset: the global target is the thing a siding follows.
        }

        /** Every global field the screens show, plus every siding override (see {@link #resetAll()}). */
        @Override
        void resetAll() {
            final HeadlightConfig d = HeadlightConfig.defaults();
            final HeadlightConfig c = config();
            c.enabled = d.enabled;
            c.dynamicLight = d.dynamicLight;
            c.beamGlow = d.beamGlow;
            c.maxDistance = d.maxDistance;
            c.headLuminance = d.headLuminance;
            c.headLength = d.headLength;
            c.beamDistance = d.beamDistance;
            c.beamHeadLength = d.beamHeadLength;
            c.beamTailLength = d.beamTailLength;
            c.beamWidth = d.beamWidth;
            c.beamHeadColor = d.beamHeadColor;
            c.beamTailColor = d.beamTailColor;
            c.beamHeadHeight = d.beamHeadHeight;
            c.beamTailHeight = d.beamTailHeight;
            // Restoring the globals while a siding still overrode them would leave the world on the
            // old numbers - "全部恢复默认" means the overrides go too.
            if (c.sidingSettings != null) {
                c.sidingSettings.clear();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Per-siding target
    // ---------------------------------------------------------------------------------------------

    /**
     * Writes {@code sidingSettings[id]} and nothing else. The global fields of
     * {@link HeadlightConfig} are never assigned in this class - grep it, there is no
     * {@code cfg.<field> =} statement here.
     */
    private static final class SidingTarget extends ConfigWriteTarget {
        private final long sidingId;

        SidingTarget(final Supplier<HeadlightConfig> cfg, final long sidingId) {
            super(cfg);
            if (sidingId == 0L) {
                // effectiveFor(0) is the global config by definition, so an id of 0 would make
                // every read fall back to the globals and every write land in an entry nothing
                // reads - the exact "I changed this siding and the globals moved" confusion.
                throw new IllegalArgumentException("sidingId 0 means the global config");
            }
            this.sidingId = sidingId;
        }

        @Override
        String describe() {
            return "siding #" + this.sidingId;
        }

        @Override
        HeadlightConfig.Effective effective() {
            return config().effectiveFor(this.sidingId);
        }

        /** Get-or-create this siding's entry - the only object this class ever writes. */
        private HeadlightConfig.SidingSettings entry() {
            return config().settingsFor(this.sidingId);
        }

        @Override
        double readValue(final int which) {
            final HeadlightConfig.Effective eff = effective();
            switch (which) {
                case DISTANCE:
                    return eff.maxDistance;
                case LUMINANCE:
                    return eff.headLuminance;
                case HEAD_LENGTH:
                    return eff.headLength;
                case BEAM_DISTANCE:
                    return eff.beamDistance;
                case BEAM_HEAD_LENGTH:
                    return eff.beamHeadLength;
                case BEAM_TAIL_LENGTH:
                    return eff.beamTailLength;
                case BEAM_WIDTH:
                    return eff.beamWidth;
                case BEAM_HEAD_HEIGHT:
                    return eff.beamHeadHeight;
                default:
                    return eff.beamTailHeight;
            }
        }

        @Override
        void writeValue(final int which, final double value) {
            final HeadlightConfig.SidingSettings o = entry();
            switch (which) {
                case DISTANCE:
                    o.maxDistance = Double.valueOf(value);
                    break;
                case LUMINANCE:
                    o.headLuminance = Integer.valueOf((int) Math.round(value));
                    break;
                case HEAD_LENGTH:
                    o.headLength = Double.valueOf(value);
                    break;
                case BEAM_DISTANCE:
                    o.beamDistance = Double.valueOf(value);
                    break;
                case BEAM_HEAD_LENGTH:
                    o.beamHeadLength = Double.valueOf(value);
                    break;
                case BEAM_TAIL_LENGTH:
                    o.beamTailLength = Double.valueOf(value);
                    break;
                case BEAM_WIDTH:
                    o.beamWidth = Double.valueOf(value);
                    break;
                case BEAM_HEAD_HEIGHT:
                    o.beamHeadHeight = Double.valueOf(value);
                    break;
                default:
                    o.beamTailHeight = Double.valueOf(value);
                    break;
            }
        }

        @Override
        boolean readToggle(final int which) {
            final HeadlightConfig.Effective eff = effective();
            switch (which) {
                case MASTER:
                    return eff.enabled;
                case DYNAMIC_LIGHT:
                    return eff.dynamicLight;
                default:
                    return eff.beamGlow;
            }
        }

        @Override
        void writeToggle(final int which, final boolean on) {
            final HeadlightConfig.SidingSettings o = entry();
            switch (which) {
                case MASTER:
                    o.enabled = Boolean.valueOf(on);
                    break;
                case DYNAMIC_LIGHT:
                    o.dynamicLight = Boolean.valueOf(on);
                    break;
                default:
                    o.beamGlow = Boolean.valueOf(on);
                    break;
            }
        }

        @Override
        int readColor(final boolean head) {
            return head ? effective().beamHeadColor : effective().beamTailColor;
        }

        @Override
        void writeColor(final boolean head, final int rgb) {
            final HeadlightConfig.SidingSettings o = entry();
            final String text = HeadlightConfig.formatColor(rgb);
            if (head) {
                o.beamHeadColor = text;
            } else {
                o.beamTailColor = text;
            }
        }

        @Override
        boolean followsValue(final int which) {
            final HeadlightConfig.SidingSettings o = config().overrides(this.sidingId);
            if (o == null) {
                return true;
            }
            switch (which) {
                case DISTANCE:
                    return o.maxDistance == null;
                case LUMINANCE:
                    return o.headLuminance == null;
                case HEAD_LENGTH:
                    return o.headLength == null;
                case BEAM_DISTANCE:
                    return o.beamDistance == null;
                case BEAM_HEAD_LENGTH:
                    return o.beamHeadLength == null;
                case BEAM_TAIL_LENGTH:
                    return o.beamTailLength == null;
                case BEAM_WIDTH:
                    return o.beamWidth == null;
                case BEAM_HEAD_HEIGHT:
                    return o.beamHeadHeight == null;
                default:
                    return o.beamTailHeight == null;
            }
        }

        @Override
        boolean followsToggle(final int which) {
            final HeadlightConfig.SidingSettings o = config().overrides(this.sidingId);
            if (o == null) {
                return true;
            }
            switch (which) {
                case MASTER:
                    return o.enabled == null;
                case DYNAMIC_LIGHT:
                    return o.dynamicLight == null;
                default:
                    return o.beamGlow == null;
            }
        }

        @Override
        boolean followsColor(final boolean head) {
            final HeadlightConfig.SidingSettings o = config().overrides(this.sidingId);
            if (o == null) {
                return true;
            }
            return head ? o.beamHeadColor == null : o.beamTailColor == null;
        }

        @Override
        boolean hasOverrides() {
            final HeadlightConfig.SidingSettings o = config().overrides(this.sidingId);
            return o != null && !o.isEmpty();
        }

        /** The entry to clear fields on, or null when this siding follows everything anyway. */
        private HeadlightConfig.SidingSettings dropEntry() {
            return config().overrides(this.sidingId);
        }

        /** Drops the entry once nothing overrides anything, so the JSON stays sparse. */
        private void prune() {
            final HeadlightConfig.SidingSettings o = config().overrides(this.sidingId);
            if (o != null && o.isEmpty()) {
                config().clearSiding(this.sidingId);
            }
        }

        @Override
        void resetValue(final int which) {
            final HeadlightConfig.SidingSettings o = dropEntry();
            if (o == null) {
                return;
            }
            switch (which) {
                case DISTANCE:
                    o.maxDistance = null;
                    break;
                case LUMINANCE:
                    o.headLuminance = null;
                    break;
                case HEAD_LENGTH:
                    o.headLength = null;
                    break;
                case BEAM_DISTANCE:
                    o.beamDistance = null;
                    break;
                case BEAM_HEAD_LENGTH:
                    o.beamHeadLength = null;
                    break;
                case BEAM_TAIL_LENGTH:
                    o.beamTailLength = null;
                    break;
                case BEAM_WIDTH:
                    o.beamWidth = null;
                    break;
                case BEAM_HEAD_HEIGHT:
                    o.beamHeadHeight = null;
                    break;
                default:
                    o.beamTailHeight = null;
                    break;
            }
            prune();
        }

        @Override
        void resetToggle(final int which) {
            final HeadlightConfig.SidingSettings o = dropEntry();
            if (o == null) {
                return;
            }
            switch (which) {
                case MASTER:
                    o.enabled = null;
                    break;
                case DYNAMIC_LIGHT:
                    o.dynamicLight = null;
                    break;
                default:
                    o.beamGlow = null;
                    break;
            }
            prune();
        }

        @Override
        void resetColor(final boolean head) {
            final HeadlightConfig.SidingSettings o = dropEntry();
            if (o == null) {
                return;
            }
            if (head) {
                o.beamHeadColor = null;
            } else {
                o.beamTailColor = null;
            }
            prune();
        }

        @Override
        void reset() {
            config().clearSiding(this.sidingId);
        }

        /** 全部恢复默认 on a detail page: delete this siding's entry, leave the globals alone. */
        @Override
        void resetAll() {
            config().clearSiding(this.sidingId);
        }
    }
}
