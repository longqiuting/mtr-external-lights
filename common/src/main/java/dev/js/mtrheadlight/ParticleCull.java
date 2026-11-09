package dev.js.mtrheadlight;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import net.minecraft.util.Identifier;

/**
 * <b>「粒子生成了但一颗也看不到」的第三方闸门检测（Sodium Extra ≤0.5.x）。</b>
 *
 * <p>Sodium Extra 在 {@code ParticleManager#addParticle} 的 HEAD 上有一条 {@code @Inject(cancellable)}
 * （{@code me.flashyreese.mods.sodiumextra.mixin.particle.MixinParticleManager}，字节码已核对）：
 *
 * <pre>
 * if (!options.particleSettings.particles) { cir.setReturnValue(null); return; }   // 总开关
 * Identifier id = Registries.PARTICLE_TYPE.getId(effect.getType());
 * if (!options.particleSettings.otherMap.getOrDefault(id, TRUE)) {                 // 逐类型
 *     cir.setReturnValue(null);          // ← 返回 null，粒子**根本没进** ParticleManager
 * }
 * </pre>
 *
 * <p>也就是说：<b>逐类型开关关掉之后，{@code force=true}、{@code ParticlesMode.MINIMAL}、距离、
 * 位置、粒子类型选择全都无所谓</b> —— 粒子在进入粒子系统的第一道门就被换成 null 了。日志里
 * 「spawned=600」是我们的计数器，屏幕上什么都不会有。反过来，<b>表里没有的 id 走默认值 TRUE</b>，
 * 这正是 {@code mtrlte:spray}（{@code SprayType}）能绕过这套开关的依据。
 *
 * <p>{@code otherMap} 的声明类型是 {@code Map<Identifier, Boolean>}（默认值是空的
 * {@code Object2BooleanArrayMap}），所以查表必须用 {@link Identifier} 当键 —— 用字符串查永远
 * 命中不了，这正是旧实现里 {@code culled=} 一直显示 {@code none} 的原因。{@link #lookup} 两种键
 * 都试一次（配置文件被别的工具重写成字符串键时也还能认出来）。
 *
 * <p>这个类只做检测：查不到（没装 Sodium Extra、版本换了、字段改名）一律按「没屏蔽」处理，
 * 检测本身绝不能影响任何东西。
 */
public final class ParticleCull {

    /**
     * 我们真正用到的粒子类型：水雾 {@code mtrlte:spray}（自己的类型，见 {@code SprayType}）、
     * 回退雾体 {@code minecraft:cloud}、水花 {@code minecraft:splash}、探针 {@code minecraft:flame}
     * 以及最小档补的 {@code minecraft:large_smoke}。
     */
    public static final String[] USED = {
            "mtrlte:spray", "minecraft:cloud", "minecraft:large_smoke",
            "minecraft:splash", "minecraft:flame"
    };

    private static boolean probed;
    private static String cached = "";

    private ParticleCull() {
    }

    /**
     * 单个类型现在是否被 Sodium Extra 的逐类型开关屏蔽。
     *
     * <p>查表语义与 Sodium Extra 自己一致：<b>缺省 = 不屏蔽</b>（{@code getOrDefault(id, TRUE)}）。
     * 所以自注册的 {@code mtrlte:spray} 只要没被显式写进表里就返回 {@code false}。查不到
     * Sodium Extra / 字段改名 / 反射失败一律 {@code false}。
     *
     * @param id 完整的粒子类型 id，例如 {@code mtrlte:spray}
     */
    public static boolean blocked(final String id) {
        return Boolean.FALSE.equals(lookup(id));
    }

    /**
     * 被 Sodium Extra 逐类型屏蔽掉的类型（逗号分隔的短名，无前缀）；空串 = 没有被屏蔽，
     * 或者根本查不到 Sodium Extra。
     */
    public static String culled() {
        if (probed) {
            return cached;
        }
        probed = true;
        try {
            final Object map = otherMap();
            if (!(map instanceof Map<?, ?>)) {
                return cached = "";
            }
            final Object master = field(particleSettings(), "particles");
            if (master instanceof Boolean b && !b.booleanValue()) {
                // 总开关关掉：全部类型都不渲染。
                return cached = "ALL(particles=false)";
            }
            final StringBuilder out = new StringBuilder();
            for (final String id : USED) {
                if (Boolean.FALSE.equals(lookupIn(map, id))) {
                    if (out.length() > 0) {
                        out.append(',');
                    }
                    out.append(id.startsWith("minecraft:") ? id.substring(10) : id);
                }
            }
            return cached = out.toString();
        } catch (Throwable t) {
            return cached = "";
        }
    }

    /**
     * 在 {@code otherMap} 里查一个 id：先按 Sodium Extra 真实的键类型（{@link Identifier}）查，
     * 再退回字符串键。表里没有这个 id 时返回 {@code null}（= 走默认值 TRUE = 不屏蔽）。
     */
    private static Boolean lookup(final String id) {
        try {
            return lookupIn(otherMap(), id);
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@link #lookup(String)} 的本体；{@code map} 为 {@code null} 时返回 {@code null}。 */
    private static Boolean lookupIn(final Object map, final String id) {
        if (!(map instanceof Map<?, ?> other)) {
            return null;
        }
        try {
            Object value = other.get(new Identifier(id));
            if (value == null) {
                value = other.get(id);
            }
            return value instanceof Boolean b ? b : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Sodium Extra 的 {@code particleSettings}；拿不到就 {@code null}。 */
    private static Object particleSettings() {
        try {
            final Class<?> mod = Class.forName(
                    "me.flashyreese.mods.sodiumextra.client.SodiumExtraClientMod");
            final Method options = mod.getMethod("options");
            final Object gameOptions = options.invoke(null);
            return gameOptions == null ? null : field(gameOptions, "particleSettings");
        } catch (Throwable t) {
            return null;
        }
    }

    /** Sodium Extra 的 {@code particleSettings.otherMap}；拿不到就 {@code null}。 */
    private static Object otherMap() {
        final Object particleSettings = particleSettings();
        return particleSettings == null ? null : field(particleSettings, "otherMap");
    }

    /** 有没有粒子类型被屏蔽（诊断行 / 探针消息用）。 */
    public static boolean anyCulled() {
        return !culled().isEmpty();
    }

    /** 一行给人看的原因；没被屏蔽时是 {@code null}。 */
    public static String reason() {
        final String culled = culled();
        if (culled.isEmpty()) {
            return null;
        }
        return "Sodium Extra 屏蔽了粒子类型 " + culled
                + " —— 在 视频设置 → Sodium Extra → 粒子 里打开（或关掉 Particle Culling），"
                + "否则水雾/探针一颗都不会渲染";
    }

    /** 读一个 public 字段（拿不到就 null）。 */
    private static Object field(final Object owner, final String name) {
        try {
            final Field f = owner.getClass().getField(name);
            return f.get(owner);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 世界切换 / 配置改动之后重查一次（诊断用）。 */
    public static void reset() {
        probed = false;
        cached = "";
    }
}
