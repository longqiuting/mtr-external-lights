package dev.js.mtrheadlight.mixin.mtr;

import dev.js.mtrheadlight.mtr.BuiltInTrains;
import mtr.client.TrainClientRegistry;
import mtr.client.TrainProperties;
import mtr.data.TransportMode;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;

/**
 * MTR 内置车型注册表的注入点（{@code disableBuiltInTrains}）。
 *
 * <h2>为什么是 {@code reset()} 而不是构造器</h2>
 * javap 1.20.1 的 MTR 3.6.4.2：
 * <pre>
 * public mtr.client.TrainClientRegistry();                        // 空构造器
 * public static void reset();
 *    0: getstatic REGISTRY / clear        &lt;-- 资源包已注册的车型在这里被清掉
 *   11: getstatic KEY_ORDERS / clear
 *   16: ... 114 个内置车型 ...
 * public static void register(String, TrainProperties);           // 只 put，不 clear
 * mtr.client.CustomResources#reload 的第 0 条指令就是 reset()
 * </pre>
 *
 * <p>三个注入点各管一件事：
 * <ol>
 *   <li>{@code reset} HEAD —— 标记"进入 reset"，并区分是 MTR 的资源重载还是我们自己发起的；</li>
 *   <li>{@code reset} TAIL —— 第一次采集内置 id 快照；我们自己发起的 reset 之后把资源包车型
 *       注册回来；然后裁剪没在用的内置车型；</li>
 *   <li>{@code register} TAIL —— reset 之外发生的注册 = 资源包 / 附属模组，登记到受保护表里，
 *       从此不会被裁剪碰到。</li>
 * </ol>
 *
 * <p>{@code require = 0}（mixin 配置里 {@code defaultRequire: 0} + 插件里的类存在探测）：注入失败
 * 只是不生效，不会让客户端起不来；{@link BuiltInTrains} 的自检会发现并静默退场。
 */
@Mixin(value = TrainClientRegistry.class, remap = false)
public abstract class TrainClientRegistryMixin {
    /**
     * {@code private static final Map<String, TrainProperties> REGISTRY}。
     * 只做 {@code Map} 的内容增删，从不给字段本身赋值，所以带 {@code @Final}。
     */
    @Shadow
    @Final
    private static Map<String, TrainProperties> REGISTRY;

    /**
     * {@code private static final Map<TransportMode, List<String>> KEY_ORDERS}。
     * 必须一起维护：{@code forEach} 按它遍历，再从 {@code REGISTRY.get(key)} 取对象。
     */
    @Shadow
    @Final
    private static Map<TransportMode, List<String>> KEY_ORDERS;

    /**
     * HEAD：{@code REGISTRY.clear()} 之前。
     *
     * <p>顺序有意义——TAIL 之后资源包的注册才开始，所以 HEAD 是唯一能区分"这次清空会不会有人
     * 重新填回来"的位置。
     */
    @Inject(method = "reset()V", at = @At("HEAD"), remap = false)
    private static void mtrheadlight$beforeReset(final CallbackInfo ci) {
        try {
            BuiltInTrains.beforeReset();
        } catch (Throwable t) {
            BuiltInTrains.fail(t);
        }
    }

    @Inject(method = "reset()V", at = @At("TAIL"), remap = false)
    private static void mtrheadlight$afterReset(final CallbackInfo ci) {
        try {
            // 先记快照 / 补回资源包车型，再裁剪。两步都在 TAIL，此刻注册表已经完整。
            BuiltInTrains.afterReset(REGISTRY);
            BuiltInTrains.prune(REGISTRY, KEY_ORDERS);
        } catch (Throwable t) {
            // 这一个模组永远不该让 MTR 的注册表把客户端带崩。
            BuiltInTrains.fail(t);
        }
    }

    /**
     * {@code register(String, TrainProperties)} 的 TAIL。
     *
     * <p>reset 之外的注册只有一个来源：{@code CustomResources.reload} 读
     * {@code mtr_custom_resources.json}，或附属模组在客户端初始化时自己调。这类 id 一律记进
     * 受保护表，并且永远不会被 {@code prune} 删除。
     */
    @Inject(method = "register(Ljava/lang/String;Lmtr/client/TrainProperties;)V",
            at = @At("TAIL"), remap = false)
    private static void mtrheadlight$afterRegister(final String id, final TrainProperties properties,
                                                   final CallbackInfo ci) {
        try {
            BuiltInTrains.onRegister(id, properties);
        } catch (Throwable t) {
            BuiltInTrains.fail(t);
        }
    }
}
