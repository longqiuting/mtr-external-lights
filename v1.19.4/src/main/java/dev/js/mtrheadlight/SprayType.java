package dev.js.mtrheadlight;

import net.fabricmc.fabric.api.client.particle.v1.ParticleFactoryRegistry;
import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;
import net.minecraft.particle.DefaultParticleType;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

/**
 * {@code mtrlte:spray} 的注册与「用哪个类型」的最终裁决。
 *
 * <h2>Sodium Extra 为什么屏蔽不了它</h2>
 * Sodium Extra 的 {@code MixinParticleManager#addParticle}（HEAD，cancellable）逐类型查询它自己的
 * 设置表：
 *
 * <pre>
 * Identifier id = Registries.PARTICLE_TYPE.getId(effect.getType());
 * if (!options.particleSettings.otherMap.getOrDefault(id, TRUE)) cir.setReturnValue(null);
 * </pre>
 *
 * 表里只有它<b>列出来过</b>的键：{@code getOrDefault(id, TRUE)} 的默认值是 <b>TRUE</b>，所以只要
 * 用户没在 Sodium Extra 里手动关掉 {@code mtrlte:spray}，这条水雾就一直进 {@code ParticleManager}。
 * （Sodium Extra 0.5.9 的粒子页是<b>遍历运行时注册表</b>生成的 ——
 * {@code SodiumExtraGameOptionPages.particle()} 里 {@code Registries.PARTICLE_TYPE.getIds()} +
 * {@code Collectors.groupingBy(Identifier::getNamespace)}，所以 {@code mtrlte:spray} 会出现在它自己
 * 的 {@code mtrlte} 分组里，用户能单独开关它，默认开。这是「像 MrCrayfish 家具那样被 Sodium Extra
 * 单独控制」的正确形态：不是注入它的配置，而是让自己的类型被它枚举到。）
 *
 * <h2>它仍然可能失败</h2>
 * <ul>
 *   <li>注册 / 工厂注册抛异常（注册表在极早期被冻结、Fabric API 版本不符、别的模组抢了同一个 id）；</li>
 *   <li>将来 Sodium Extra 把默认值从 TRUE 改成 FALSE（未知类型一律屏蔽）；</li>
 *   <li>用户在 Sodium Extra 里手动加了一条 {@code mtrlte:spray=false}（如果那个版本允许）。</li>
 * </ul>
 * 这三种情况都由 {@link #resolve()} 在第一次用到时判一次，失败就整体退回
 * {@link ParticleTypes#CLOUD}（原行为），并把原因写进日志和诊断行（{@code sprayType=vanilla}）。
 */
public final class SprayType {

    /** 注册 id（同时是粒子贴图 json 的路径：{@code assets/mtrlte/particles/spray.json}）。 */
    public static final String ID = "mtrlte:spray";
    private static final Identifier KEY = new Identifier("mtrlte", "spray");

    /** 注册出来的类型；注册失败时为 {@code null}。 */
    private static DefaultParticleType type;
    private static boolean registered;
    private static boolean resolved;
    /** 最终裁决：true = 用我们自己的类型，false = 回退到 CLOUD。 */
    private static boolean self;
    private static String note = "not initialised";

    private SprayType() {
    }

    /** 客户端入口调用一次（{@code RenderHooks#init}）。任何异常都不会抛出。 */
    public static void init() {
        if (type != null) {
            return;
        }
        try {
            // alwaysShow = true：原版 WorldRenderer 的距离 / MINIMAL 两道闸门对它直接失效。
            final DefaultParticleType created = FabricParticleTypes.simple(true);
            Registry.register(Registries.PARTICLE_TYPE, KEY, created);
            ParticleFactoryRegistry.getInstance().register(created, sprites ->
                    (effect, world, x, y, z, vx, vy, vz) ->
                            new SprayParticle(world, x, y, z, vx, vy, vz, sprites));
            // 自检：注册表里这个 id 真的是我们刚放进去的对象。
            if (Registries.PARTICLE_TYPE.get(KEY) != created) {
                throw new IllegalStateException("registry holds a different object for " + ID);
            }
            type = created;
            registered = true;
            note = "registered";
        } catch (Throwable t) {
            type = null;
            registered = false;
            note = "registration failed: " + t;
            MtrHeadlightClient.LOGGER.warn(
                    "[lte] could not register the {} particle, falling back to minecraft:cloud", ID, t);
        }
        resolved = false;
        MtrHeadlightClient.LOGGER.info(
                "[lte] spray particle type={} registered={} ({})", ID, Boolean.valueOf(registered), note);
    }

    /**
     * 裁决一次（幂等）。
     *
     * <p><b>故意不在 {@link #init()} 里调用</b>：客户端入口的执行顺序不保证 Sodium Extra 已经初始化
     * 完成，太早读它的 {@code options()} 只会得到「查不到 = 没屏蔽」的假结论。第一次真正要发粒子
     * （客户端 tick，所有 initializer 都跑完了）时再判，结论才是可信的。
     */
    private static void resolve() {
        resolved = true;
        if (!registered || type == null) {
            self = false;
            MtrHeadlightClient.LOGGER.warn(
                    "[lte] spray: falling back to minecraft:cloud ({})", note);
            return;
        }
        if (ParticleCull.blocked(ID)) {
            // Sodium Extra 的逐类型表里明确写了 false（未来版本 / 用户手工加过）。
            self = false;
            note = "Sodium Extra culls " + ID;
            MtrHeadlightClient.LOGGER.warn(
                    "[lte] spray: {} is culled by Sodium Extra, falling back to minecraft:cloud", ID);
            return;
        }
        self = true;
        note = "Sodium Extra has no entry for " + ID + " -> getOrDefault(id, TRUE) lets it through";
        MtrHeadlightClient.LOGGER.info("[lte] spray: using {} ({})", ID, note);
    }

    private static void ensureResolved() {
        if (!resolved) {
            resolve();
        }
    }

    /**
     * 生成用的 effect：能用就 {@code mtrlte:spray}，否则 {@link ParticleTypes#CLOUD}。
     * 调用方不需要再判空 —— 这个函数永远返回一个可用的 effect。
     */
    public static ParticleEffect effect() {
        ensureResolved();
        return self && type != null ? type : ParticleTypes.CLOUD;
    }

    /** 诊断用：{@code self} = 自己的类型，{@code vanilla} = 回退到 CLOUD。 */
    public static String label() {
        ensureResolved();
        return self && type != null ? "self" : "vanilla";
    }

    /** 诊断用的一句话原因（注册失败 / 被屏蔽 / 未知类型放行）。 */
    public static String note() {
        ensureResolved();
        return note;
    }
}
