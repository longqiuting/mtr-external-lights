// 时钟方块的配置对象：**纯数据**，不碰方块实体、不碰渲染。
package dev.js.mtrheadlight.clock;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import net.minecraft.util.StringIdentifiable;

/**
 * 一台时钟的全部可配置项（{@code mtrlte:clock}）。
 *
 * <h2>它存在哪</h2>
 * <p>整份结构存在 {@link ClockBlockEntity} 的 NBT 里 —— <b>每块钟一份</b>，拆了钟配置一起没，
 * 两台钟互不影响（与液体安检机的名单同一个思路）。全局配置文件做不到「这台钟是挂钟、那台是
 * 站台大钟」。
 *
 * <h2>为什么单独一个类</h2>
 * <p>同一份字段要在四个地方来回跑：方块实体 NBT、网络包（{@link ClockNet}）、服务端校验、
 * 客户端界面。字段散着写必然会有一处漏掉，所以读写都收在这里：
 * <ul>
 *   <li>{@link #writeNbt}/{@link #readNbt} —— 存档；</li>
 *   <li>{@link #writeBuf}/{@link #readBuf} —— 刷子开界面（S2C）与保存（C2S）；</li>
 *   <li>{@link #clamp} —— 服务端 / 客户端两边的最后一道闸（长度 0.1–50、颜色只留 24 位、
 *       偏移不超过一天、音效 id 必须是 {@code namespace:path} 形状）。</li>
 * </ul>
 *
 * <h2>默认值</h2>
 * <p>默认是一台「白面、黑针、红秒针、短横线刻度、不报时、不接红石」的普通挂钟，
 * 三根指针长度按格算：时针 0.20 / 分针 0.28 / 秒针 0.33；<b>钟面半径与刻度半径是独立字段</b>
 * （{@link #faceRadius} 0.36、{@link #tickRadius} 0.33），不再由最长针长度推算 —— 想让指针
 * 长过钟面（站台大钟的针常常伸到盘子外面）只要把针调长，面不会跟着变大。
 *
 * <p>五个显隐开关（{@link #showFace} / {@link #showHour} / {@link #showMinute} /
 * {@link #showSecond} / {@link #showHub}）默认全开。
 */
public final class ClockConfig {

    /** 指针长度 / 半径的下限（格）。 */
    public static final float MIN_LENGTH = 0.1F;
    /** 指针长度 / 半径的上限（格）。 */
    public static final float MAX_LENGTH = 50.0F;
    /** 长度 / 半径滑块的步进：0.1 格。 */
    public static final float LENGTH_STEP = 0.1F;
    /** 钟面半径默认值（格）：正好落在方块面里。 */
    public static final float DEFAULT_FACE_RADIUS = 0.36F;
    /** 刻度 / 数字的环绕半径默认值（格）：比钟面半径小一点点，刻度压在盘面边缘内侧。 */
    public static final float DEFAULT_TICK_RADIUS = 0.33F;
    /** 默认报时音效：原版钟。 */
    public static final String DEFAULT_SOUND = "minecraft:block.bell.use";
    /** 手动校准偏移的上限（分钟）：不超过一天，再大就是「换一天」了。 */
    public static final double MAX_OFFSET_MINUTES = 1440.0D;

    /** 走哪个钟。 */
    public enum TimeSource implements StringIdentifiable {
        /** 游戏内时间：一个 MC 日（24000 tick = 20 分钟）走完 24 小时。 */
        MC("mc"),
        /** 现实时间：{@code ZonedDateTime.now()} 的墙钟（与充电宝的租期同一个时间源）。 */
        REAL("real");

        private final String id;

        TimeSource(final String id) {
            this.id = id;
        }

        @Override
        public String asString() {
            return this.id;
        }

        public String labelKey() {
            return "screen.mtrlte.clock.source." + this.id;
        }

        static TimeSource byName(final String name, final TimeSource fallback) {
            for (final TimeSource v : values()) {
                if (v.id.equalsIgnoreCase(name) || v.name().equalsIgnoreCase(name)) {
                    return v;
                }
            }
            return fallback;
        }
    }

    /** 发光（= 颜色不衰减）的时机。 */
    public enum GlowMode implements StringIdentifiable {
        /** 从不：夜里按光照变暗。 */
        OFF("off"),
        /** 只在白天。 */
        DAY("day"),
        /** 只在夜里。 */
        NIGHT("night"),
        /** 一直亮。 */
        ALWAYS("always");

        private final String id;

        GlowMode(final String id) {
            this.id = id;
        }

        @Override
        public String asString() {
            return this.id;
        }

        public String labelKey() {
            return "screen.mtrlte.clock.glow." + this.id;
        }

        static GlowMode byName(final String name, final GlowMode fallback) {
            for (final GlowMode v : values()) {
                if (v.id.equalsIgnoreCase(name) || v.name().equalsIgnoreCase(name)) {
                    return v;
                }
            }
            return fallback;
        }
    }

    /** 刻度样式。 */
    public enum TickStyle implements StringIdentifiable {
        NONE("none"),
        /** 短横线（径向）。 */
        BAR("bar"),
        /** 圆点。 */
        DOT("dot"),
        /** 小方块。 */
        SQUARE("square"),
        /** 三角形（尖朝外）。 */
        TRIANGLE("triangle"),
        /** 阿拉伯数字 1–12。 */
        ARABIC("arabic"),
        /** 罗马数字 I–XII。 */
        ROMAN("roman");

        private final String id;

        TickStyle(final String id) {
            this.id = id;
        }

        @Override
        public String asString() {
            return this.id;
        }

        public String labelKey() {
            return "screen.mtrlte.clock.tick." + this.id;
        }

        /** 这一种是不是「画数字」而不是「画小块」。 */
        public boolean isNumeral() {
            return this == ARABIC || this == ROMAN;
        }

        static TickStyle byName(final String name, final TickStyle fallback) {
            for (final TickStyle v : values()) {
                if (v.id.equalsIgnoreCase(name) || v.name().equalsIgnoreCase(name)) {
                    return v;
                }
            }
            return fallback;
        }
    }

    /** 整点报时的模式。 */
    public enum ChimeMode implements StringIdentifiable {
        /** 敲一下。 */
        SINGLE("single"),
        /** 几点敲几下（12 小时制，最多 12 下）。 */
        HOURLY("hourly");

        private final String id;

        ChimeMode(final String id) {
            this.id = id;
        }

        @Override
        public String asString() {
            return this.id;
        }

        public String labelKey() {
            return "screen.mtrlte.clock.chime." + this.id;
        }

        static ChimeMode byName(final String name, final ChimeMode fallback) {
            for (final ChimeMode v : values()) {
                if (v.id.equalsIgnoreCase(name) || v.name().equalsIgnoreCase(name)) {
                    return v;
                }
            }
            return fallback;
        }
    }

    // ------------------------------------------------------------------ 字段

    /** 时间源。 */
    public TimeSource timeSource = TimeSource.MC;
    /** 发光时机。 */
    public GlowMode glow = GlowMode.OFF;

    /** 时针长度（格）。 */
    public float hourLength = 0.20F;
    /** 分针长度（格）。 */
    public float minuteLength = 0.28F;
    /** 秒针长度（格）。 */
    public float secondLength = 0.33F;

    /**
     * 钟面圆半径（格）。<b>独立字段</b>：以前是 {@code max(三针长度) × 1.08}，把针调长盘子就跟着长；
     * 现在两者分开（站台大钟的针可以伸到盘外）。
     */
    public float faceRadius = DEFAULT_FACE_RADIUS;
    /** 刻度 / 数字的环绕半径（格）：12 个刻度均匀摆在这个圆上，与钟面半径无关。 */
    public float tickRadius = DEFAULT_TICK_RADIUS;

    /** 画不画钟面圆盘（刻度 / 数字跟着它一起显隐）。 */
    public boolean showFace = true;

    /** 刻度 / 数字的显隐（和钟面盘分开控制）。 */
    public boolean showTicks = true;
    /** 画不画时针。 */
    public boolean showHour = true;
    /** 画不画分针。 */
    public boolean showMinute = true;
    /** 画不画秒针。 */
    public boolean showSecond = true;
    /** 画不画中心轴（盖住针根的那个小圆点）。 */
    public boolean showHub = true;

    /** 钟面颜色（0xRRGGBB）。 */
    public int faceColor = 0xF2EAD8;
    /** 时针颜色。 */
    public int hourColor = 0x1B1B1B;
    /** 分针颜色。 */
    public int minuteColor = 0x1B1B1B;
    /** 秒针颜色。 */
    public int secondColor = 0xC0392B;
    /** 中心轴颜色。 */
    public int hubColor = 0x1B1B1B;
    /** 刻度 / 数字颜色。 */
    public int tickColor = 0x2A2A2A;

    /** 整点报时开关。 */
    public boolean chime = false;
    /** 报时音效 id（{@code namespace:path}）。 */
    public String chimeSound = DEFAULT_SOUND;
    /** 报时模式。 */
    public ChimeMode chimeMode = ChimeMode.SINGLE;

    /** 刻度样式。 */
    public TickStyle tickStyle = TickStyle.BAR;

    /** 红石控制：开 = 未充能时停摆且不报时。 */
    public boolean redstone = false;

    /** 手动校准偏移（分钟，可为小数；加在时间源之上）。 */
    public double offsetMinutes = 0.0D;

    /** 一份默认配置。 */
    public ClockConfig() {
    }

    /** 深拷贝（方块实体对外只给副本，别让人直接改到存档里的那份）。 */
    public ClockConfig copy() {
        final ClockConfig out = new ClockConfig();
        out.timeSource = this.timeSource;
        out.glow = this.glow;
        out.hourLength = this.hourLength;
        out.minuteLength = this.minuteLength;
        out.secondLength = this.secondLength;
        out.faceRadius = this.faceRadius;
        out.tickRadius = this.tickRadius;
        out.showFace = this.showFace;
        out.showTicks = this.showTicks;
        out.showHour = this.showHour;
        out.showMinute = this.showMinute;
        out.showSecond = this.showSecond;
        out.showHub = this.showHub;
        out.faceColor = this.faceColor;
        out.hourColor = this.hourColor;
        out.minuteColor = this.minuteColor;
        out.secondColor = this.secondColor;
        out.hubColor = this.hubColor;
        out.tickColor = this.tickColor;
        out.chime = this.chime;
        out.chimeSound = this.chimeSound;
        out.chimeMode = this.chimeMode;
        out.tickStyle = this.tickStyle;
        out.redstone = this.redstone;
        out.offsetMinutes = this.offsetMinutes;
        return out;
    }

    /**
     * 把每个字段压回合法范围。**两边都调**：客户端只是为了界面里好看，
     * 真正作数的是服务端 {@link ClockNet} 收到包之后那一次。
     */
    public void clamp() {
        if (this.timeSource == null) {
            this.timeSource = TimeSource.MC;
        }
        if (this.glow == null) {
            this.glow = GlowMode.OFF;
        }
        if (this.chimeMode == null) {
            this.chimeMode = ChimeMode.SINGLE;
        }
        if (this.tickStyle == null) {
            this.tickStyle = TickStyle.BAR;
        }
        this.hourLength = snapLength(this.hourLength, 0.20F);
        this.minuteLength = snapLength(this.minuteLength, 0.28F);
        this.secondLength = snapLength(this.secondLength, 0.33F);
        this.faceRadius = snapLength(this.faceRadius, DEFAULT_FACE_RADIUS);
        this.tickRadius = snapLength(this.tickRadius, DEFAULT_TICK_RADIUS);
        this.faceColor &= 0xFFFFFF;
        this.hourColor &= 0xFFFFFF;
        this.minuteColor &= 0xFFFFFF;
        this.secondColor &= 0xFFFFFF;
        this.hubColor &= 0xFFFFFF;
        this.tickColor &= 0xFFFFFF;
        this.chimeSound = normalizeSound(this.chimeSound);
        this.offsetMinutes = Math.max(-MAX_OFFSET_MINUTES, Math.min(MAX_OFFSET_MINUTES, this.offsetMinutes));
        if (Double.isNaN(this.offsetMinutes)) {
            this.offsetMinutes = 0.0D;
        }
    }

    /** 夹到 0.1–50 并对齐到 0.1 的格点（界面上的滑块也是这个粒度）。 */
    public static float snapLength(final float value, final float fallback) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return fallback;
        }
        float v = Math.max(MIN_LENGTH, Math.min(MAX_LENGTH, value));
        v = Math.round(v / LENGTH_STEP) * LENGTH_STEP;
        return Math.max(MIN_LENGTH, Math.min(MAX_LENGTH, v));
    }

    /** 音效 id 只接受 {@code namespace:path}；空 / 畸形一律回默认音效。 */
    public static String normalizeSound(final String raw) {
        if (raw == null || raw.isEmpty()) {
            return DEFAULT_SOUND;
        }
        final Identifier id = Identifier.tryParse(raw.trim().toLowerCase(java.util.Locale.ROOT));
        return id == null ? DEFAULT_SOUND : id.toString();
    }

    // ------------------------------------------------------------------ NBT

    public void writeNbt(final NbtCompound nbt) {
        nbt.putString("timeSource", this.timeSource.asString());
        nbt.putString("glow", this.glow.asString());
        nbt.putFloat("hourLen", this.hourLength);
        nbt.putFloat("minuteLen", this.minuteLength);
        nbt.putFloat("secondLen", this.secondLength);
        nbt.putFloat("faceRadius", this.faceRadius);
        nbt.putFloat("tickRadius", this.tickRadius);
        nbt.putBoolean("showFace", this.showFace);
        nbt.putBoolean("showTicks", this.showTicks);
        nbt.putBoolean("showHour", this.showHour);
        nbt.putBoolean("showMinute", this.showMinute);
        nbt.putBoolean("showSecond", this.showSecond);
        nbt.putBoolean("showHub", this.showHub);
        nbt.putInt("faceColor", this.faceColor);
        nbt.putInt("hourColor", this.hourColor);
        nbt.putInt("minuteColor", this.minuteColor);
        nbt.putInt("secondColor", this.secondColor);
        nbt.putInt("hubColor", this.hubColor);
        nbt.putInt("tickColor", this.tickColor);
        nbt.putBoolean("chime", this.chime);
        nbt.putString("chimeSound", this.chimeSound);
        nbt.putString("chimeMode", this.chimeMode.asString());
        nbt.putString("tickStyle", this.tickStyle.asString());
        nbt.putBoolean("redstone", this.redstone);
        nbt.putDouble("offsetMinutes", this.offsetMinutes);
    }

    public void readNbt(final NbtCompound nbt) {
        final ClockConfig fallback = new ClockConfig();
        this.timeSource = nbt.contains("timeSource")
                ? TimeSource.byName(nbt.getString("timeSource"), fallback.timeSource) : fallback.timeSource;
        this.glow = nbt.contains("glow")
                ? GlowMode.byName(nbt.getString("glow"), fallback.glow) : fallback.glow;
        this.hourLength = nbt.contains("hourLen") ? nbt.getFloat("hourLen") : fallback.hourLength;
        this.minuteLength = nbt.contains("minuteLen") ? nbt.getFloat("minuteLen") : fallback.minuteLength;
        this.secondLength = nbt.contains("secondLen") ? nbt.getFloat("secondLen") : fallback.secondLength;
        // 老存档（拆分半径之前）没有这两个字段：按当年的公式 1.08 × 最长针 / ×0.92 反推，
        // 保证读进来的钟和升级前长得一模一样，不会因为多了默认值突然变大变小。
        final float legacyFace = 1.08F * Math.max(this.hourLength, Math.max(this.minuteLength, this.secondLength));
        this.faceRadius = nbt.contains("faceRadius") ? nbt.getFloat("faceRadius") : legacyFace;
        this.tickRadius = nbt.contains("tickRadius") ? nbt.getFloat("tickRadius") : legacyFace * 0.92F;
        this.showFace = !nbt.contains("showFace") || nbt.getBoolean("showFace");
        this.showTicks = !nbt.contains("showTicks") || nbt.getBoolean("showTicks");
        this.showHour = !nbt.contains("showHour") || nbt.getBoolean("showHour");
        this.showMinute = !nbt.contains("showMinute") || nbt.getBoolean("showMinute");
        this.showSecond = !nbt.contains("showSecond") || nbt.getBoolean("showSecond");
        this.showHub = !nbt.contains("showHub") || nbt.getBoolean("showHub");
        this.faceColor = nbt.contains("faceColor") ? nbt.getInt("faceColor") : fallback.faceColor;
        this.hourColor = nbt.contains("hourColor") ? nbt.getInt("hourColor") : fallback.hourColor;
        this.minuteColor = nbt.contains("minuteColor") ? nbt.getInt("minuteColor") : fallback.minuteColor;
        this.secondColor = nbt.contains("secondColor") ? nbt.getInt("secondColor") : fallback.secondColor;
        this.hubColor = nbt.contains("hubColor") ? nbt.getInt("hubColor") : fallback.hubColor;
        this.tickColor = nbt.contains("tickColor") ? nbt.getInt("tickColor") : fallback.tickColor;
        this.chime = nbt.contains("chime") ? nbt.getBoolean("chime") : fallback.chime;
        this.chimeSound = nbt.contains("chimeSound") ? nbt.getString("chimeSound") : fallback.chimeSound;
        this.chimeMode = nbt.contains("chimeMode")
                ? ChimeMode.byName(nbt.getString("chimeMode"), fallback.chimeMode) : fallback.chimeMode;
        this.tickStyle = nbt.contains("tickStyle")
                ? TickStyle.byName(nbt.getString("tickStyle"), fallback.tickStyle) : fallback.tickStyle;
        this.redstone = nbt.contains("redstone") ? nbt.getBoolean("redstone") : fallback.redstone;
        this.offsetMinutes = nbt.contains("offsetMinutes") ? nbt.getDouble("offsetMinutes") : fallback.offsetMinutes;
        this.clamp();
    }

    // ------------------------------------------------------------------ 网络

    /** 写进网络包（一个字段都不许漏 —— 界面里能改的就得能传）。 */
    public void writeBuf(final PacketByteBuf buf) {
        buf.writeString(this.timeSource.asString());
        buf.writeString(this.glow.asString());
        buf.writeFloat(this.hourLength);
        buf.writeFloat(this.minuteLength);
        buf.writeFloat(this.secondLength);
        buf.writeFloat(this.faceRadius);
        buf.writeFloat(this.tickRadius);
        buf.writeBoolean(this.showFace);
        buf.writeBoolean(this.showHour);
        buf.writeBoolean(this.showMinute);
        buf.writeBoolean(this.showSecond);
        buf.writeBoolean(this.showHub);
        buf.writeBoolean(this.showTicks);
        buf.writeInt(this.faceColor);
        buf.writeInt(this.hourColor);
        buf.writeInt(this.minuteColor);
        buf.writeInt(this.secondColor);
        buf.writeInt(this.hubColor);
        buf.writeInt(this.tickColor);
        buf.writeBoolean(this.chime);
        buf.writeString(this.chimeSound);
        buf.writeString(this.chimeMode.asString());
        buf.writeString(this.tickStyle.asString());
        buf.writeBoolean(this.redstone);
        buf.writeDouble(this.offsetMinutes);
    }

    public static ClockConfig readBuf(final PacketByteBuf buf) {
        final ClockConfig cfg = new ClockConfig();
        cfg.timeSource = TimeSource.byName(buf.readString(16), cfg.timeSource);
        cfg.glow = GlowMode.byName(buf.readString(16), cfg.glow);
        cfg.hourLength = buf.readFloat();
        cfg.minuteLength = buf.readFloat();
        cfg.secondLength = buf.readFloat();
        cfg.faceRadius = buf.readFloat();
        cfg.tickRadius = buf.readFloat();
        cfg.showFace = buf.readBoolean();
        cfg.showHour = buf.readBoolean();
        cfg.showMinute = buf.readBoolean();
        cfg.showSecond = buf.readBoolean();
        cfg.showHub = buf.readBoolean();
        cfg.showTicks = buf.readBoolean();
        cfg.faceColor = buf.readInt();
        cfg.hourColor = buf.readInt();
        cfg.minuteColor = buf.readInt();
        cfg.secondColor = buf.readInt();
        cfg.hubColor = buf.readInt();
        cfg.tickColor = buf.readInt();
        cfg.chime = buf.readBoolean();
        cfg.chimeSound = buf.readString(128);
        cfg.chimeMode = ChimeMode.byName(buf.readString(16), cfg.chimeMode);
        cfg.tickStyle = TickStyle.byName(buf.readString(16), cfg.tickStyle);
        cfg.redstone = buf.readBoolean();
        cfg.offsetMinutes = buf.readDouble();
        cfg.clamp();
        return cfg;
    }
}
