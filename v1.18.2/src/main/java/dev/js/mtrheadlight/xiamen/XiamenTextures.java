// Texture namespace switch for renderers that inherit MTR's RenderPSDAPGDoor.
package dev.js.mtrheadlight.xiamen;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dev.js.mtrheadlight.LteLog;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

/**
 * 把 MTR 写死的 {@code mtr:textures/block/...} 换成模组自带的 {@code mtrlte:textures/block/...}。
 *
 * <h2>为什么需要这一层</h2>
 * <p>门扇的几何 / UV / 门灯 / 开合动画全部来自 MTR 的 {@code RenderPSDAPGDoor#render}，贴图 id 在那个方法里
 * 是死写的格式串（javap 3.6.4.2：{@code 216: ldc "mtr:textures/block/psd_door_%s_%s_%s.png"} 等 15 处），
 * 外面没有字段可以改。于是 {@code RenderPsdDoorTextureMixin} 在 {@code new Identifier(String)} 之前把这
 * 一个字符串截下来交给这里。
 *
 * <h2>规则</h2>
 * <ol>
 *   <li>渲染器没有实现 {@link IXiamenTextureNamespace}（= MTR 自己的门）→ 原样返回，别的门一点都不受影响；</li>
 *   <li>实现了、但指向的 {@code mtrlte:} 资源不存在 → <b>退回 mtr:</b>（资源包那份），
 *       所以少拷一张图不会变成紫黑格，最坏就是和以前一样跟着资源包走；</li>
 *   <li>{@link #useModTextures} 置 {@code false} → 整个开关关掉，行为回到「用当前生效资源包」。</li>
 * </ol>
 *
 * <p>结果按 id 缓存（每帧要多查十几次）。资源包重载时整表清掉 —— 判据是
 * {@code MinecraftClient#getResourceManager()} 换了实例，不需要额外的 reload 监听。
 */
public final class XiamenTextures {

    /** 模组自带的贴图命名空间。 */
    public static final String MOD_NAMESPACE = "mtrlte";

    /** 资源包用的命名空间（MTR 自己）。 */
    public static final String PACK_NAMESPACE = "mtr";

    /** 总开关：关掉就完全按以前那样用资源包的 {@code mtr:} 贴图。 */
    public static volatile boolean useModTextures = true;

    /** {@code namespace|rawId -> 最终 id}（两种门共用一张表，namespace 已经并进 key）。 */
    private static final Map<String, String> RESOLVED = new ConcurrentHashMap<>();
    /** 已经报过「模组里没有这张图」的 id，避免刷日志。 */
    private static final Set<String> REPORTED_MISSING = ConcurrentHashMap.newKeySet();
    /** 上次看到的资源管理器实例 —— 变了就是重载过资源包，缓存作废。 */
    private static volatile ResourceManager lastManager;

    /**
     * 「当前正在画的是哪个命名空间的门」。
     *
     * <p>MTR 的 {@code RenderPSDAPGDoor#render} 是模板方法，贴图 id 全在它内部拼；我们继承它以后只能在
     * 外面把命名空间塞进来。渲染线程单线程、{@code render} 内所有 {@code String.format} 都在同一次调用里
     * 同步完成，所以 ThreadLocal 是最小、最不会误伤的通道（MTR 自己的门不会设置它）。
     */
    private static final ThreadLocal<String> ACTIVE_NAMESPACE = new ThreadLocal<>();

    /**
     * 「当前正在画的门，门灯该用哪个颜色」（{@code orange} / {@code green} / {@code blue}）。
     *
     * <p>和命名空间同一个 ThreadLocal 通道：门灯贴图 id 也是在 {@code RenderPSDAPGDoor#render} 里
     * {@code String.format} 出来的（{@code light_%s.png} / {@code apg_door_light_%s.png}），
     * 只有在这一层能改。颜色由 {@link XiamenDoorLights#colorFor} 按“门所在的站台属于哪条线路”算出来。
     */
    private static final ThreadLocal<String> ACTIVE_LIGHT_COLOR = new ThreadLocal<>();

    /**
     * 「当前正在画的这条线路，贴图文件名要加什么后缀」——{@code null} / {@code ""} = 不加（1 号线）。
     *
     * <p>第二条 ThreadLocal 通道，和 {@link #ACTIVE_NAMESPACE} 同一套生命周期。三条线路的门共用
     * 同一个 {@code BlockEntityType} 与同一个渲染器实例，渲染器在每帧一开始按<b>方块实例</b>
     * 反查线路（{@link XiamenObjects#textureVariantOf}），再从这个通道传给 {@code String.format} 的拦截点。
     */
    private static final ThreadLocal<String> ACTIVE_VARIANT = new ThreadLocal<>();

    private XiamenTextures() {
    }

    /**
     * 在 {@code body} 执行期间把「当前门用的命名空间」设成 {@code namespace}，结束后恢复原值。
     *
     * <p>{@link #useModTextures} 关掉时直接执行，不碰 ThreadLocal。
     */
    public static void withNamespace(final String namespace, final Runnable body) {
        withNamespace(namespace, null, body);
    }

    /**
     * 同上，另外指定门灯颜色（{@code orange} / {@code green} / {@code blue}，null = 用基础贴图）。
     *
     * <p>{@code orange} 与 null 等价：模组自带的 {@code light_on.png} / {@code apg_door_light_on.png}
     * 就是 MTR 原版那张橙色图，不必再放一份 {@code _orange} 变体。
     */
    public static void withNamespace(final String namespace, final String doorLightColor, final Runnable body) {
        withVariant(namespace, null, doorLightColor, body);
    }

    /**
     * 完整形态：在 {@code body} 执行期间把「命名空间 + 贴图变体 + 门灯颜色」一起设好。
     *
     * @param variant 贴图文件名后缀（{@code "_l2"} / {@code "_l3"}；{@code null} = 不加）
     */
    public static void withVariant(final String namespace, final String variant, final String doorLightColor,
                                   final Runnable body) {
        if (body == null) {
            return;
        }
        if (!useModTextures || namespace == null || namespace.isEmpty() || PACK_NAMESPACE.equals(namespace)) {
            body.run();
            return;
        }
        final String previous = ACTIVE_NAMESPACE.get();
        final String previousColor = ACTIVE_LIGHT_COLOR.get();
        final String previousVariant = ACTIVE_VARIANT.get();
        ACTIVE_NAMESPACE.set(namespace);
        if (doorLightColor != null) {
            ACTIVE_LIGHT_COLOR.set(doorLightColor);
        }
        if (variant != null && !variant.isEmpty()) {
            ACTIVE_VARIANT.set(variant);
        }
        try {
            body.run();
        } finally {
            if (previous == null) {
                ACTIVE_NAMESPACE.remove();
            } else {
                ACTIVE_NAMESPACE.set(previous);
            }
            if (previousColor == null) {
                ACTIVE_LIGHT_COLOR.remove();
            } else {
                ACTIVE_LIGHT_COLOR.set(previousColor);
            }
            if (previousVariant == null) {
                ACTIVE_VARIANT.remove();
            } else {
                ACTIVE_VARIANT.set(previousVariant);
            }
        }
    }

    /**
     * 渲染器用的快捷入口：{@code namespace = mtrlte}，变体「{@code null} = 1 号线」。
     */
    public static void withVariant(final String variant, final String doorLightColor, final Runnable body) {
        withVariant(MOD_NAMESPACE, variant, doorLightColor, body);
    }

    /**
     * 给 {@code RenderPsdDoorTextureMixin} 用：把 MTR 刚 {@code String.format} 出来的 id 按当前门的
     * 命名空间改写。没有正在渲染的门 → 原样返回（MTR 自己的门走这条）。
     */
    public static String resolveActive(final String formatted) {
        final String namespace = ACTIVE_NAMESPACE.get();
        if (namespace == null) {
            return formatted;
        }
        try {
            return resolve(namespace, formatted, ACTIVE_VARIANT.get(), ACTIVE_LIGHT_COLOR.get());
        } catch (Throwable t) {
            // 渲染线程上任何异常都不能抛出去。
            return formatted;
        }
    }

    /**
     * MTR 的 {@code new Identifier(String)} 被拦截后走这里。
     *
     * @param renderer 正在渲染的渲染器实例（就是 {@code this}）
     * @param raw      MTR 拼出来的 id 字符串，例如 {@code "mtr:textures/block/psd_door_top_left_1.png"}
     * @return 实际应该使用的 id 字符串
     */
    public static String resolve(final Object renderer, final String raw) {
        if (!useModTextures || raw == null || !(renderer instanceof IXiamenTextureNamespace provider)) {
            return raw;
        }
        return resolve(provider.lte$textureNamespace(), raw);
    }
    /**
     * 同一个 switch 的「直接给命名空间」形态（给渲染器自己的日志 / 自检用，
     * 免得为了打一行日志再造一个假实例）。
     */
    public static String resolve(final String namespace, final String raw) {
        return resolve(namespace, raw, null);
    }

    /** 完整形态：带上门灯颜色（{@code null} / {@code orange} = 基础贴图）。 */
    public static String resolve(final String namespace, final String raw, final String doorLightColor) {
        return resolve(namespace, raw, null, doorLightColor);
    }

    /**
     * 最终形态：{@code namespace} + 线路变体（{@code _l2} / {@code _l3}）+ 门灯颜色。
     *
     * <p>查找顺序是一条<b>三级降级链</b>，任何一级都不存在就往下走，所以少拷一张图只会「退化成
     * 1 号线那张」，不会变成紫黑格：
     * <ol>
     *   <li>{@code mtrlte:...psd_door_top_left_1_l2.png}（本线路专用）；</li>
     *   <li>{@code mtrlte:...psd_door_top_left_1.png}（模组自带的基础图 = 1 号线）；</li>
     *   <li>{@code mtr:...psd_door_top_left_1.png}（当前资源包，即改造前的行为）。</li>
     * </ol>
     */
    public static String resolve(final String namespace, final String raw, final String variant,
                                 final String doorLightColor) {
        if (!useModTextures || raw == null) {
            return raw;
        }
        if (namespace == null || namespace.isEmpty() || PACK_NAMESPACE.equals(namespace)
                || !raw.startsWith(PACK_NAMESPACE + ":")) {
            return raw;
        }
        // 缓存作废必须发生在查表之外（映射函数里不许改这张表）。
        resetIfResourceManagerChanged();
        final String tint = doorLightColor == null ? "" : doorLightColor;
        final String suffix = variant == null ? "" : variant;
        final String key = namespace + '|' + suffix + '|' + tint + '|' + raw;
        final String cached = RESOLVED.get(key);
        if (cached != null) {
            return cached;
        }
        final String resolved = remap(namespace, raw, suffix, tint);
        RESOLVED.put(key, resolved);
        return resolved;
    }

    /**
     * {@code mtr:path} → {@code mtrlte:path_variant} → {@code mtrlte:path}，都不存在就退回原样。
     *
     * <p>门灯变体的命名：{@code light_on.png} → {@code light_on_green.png}、
     * {@code apg_door_light_off.png} → {@code apg_door_light_off_green.png}；
     * 线路变体加在<b>最后</b>（{@code light_on_l2.png}）。三门色（orange/green/blue）的图都在
     * {@code assets/mtrlte/textures/block/} 里，橙色就是 MTR 原版那一份，所以 {@code orange}
     * 直接走基础贴图；<b>1/2/3 号线的门灯都是橙色</b>，所以线路变体在门灯上基本打不中，会落到基础图。
     */
    private static String remap(final String namespace, final String raw, final String variant,
                                final String lightColor) {
        final String path = raw.substring(PACK_NAMESPACE.length() + 1);
        final boolean hasVariant = variant != null && !variant.isEmpty();
        final boolean colored = lightColor != null && !lightColor.isEmpty() && !"orange".equals(lightColor)
                && isDoorLight(path);

        // 候选顺序：线路 + 颜色 → 颜色 → 线路 → 基础图。
        final java.util.List<String> tries = new java.util.ArrayList<>(4);
        if (colored) {
            if (hasVariant) {
                tries.add(insertBeforeExtension(path, variant + '_' + lightColor));
            }
            tries.add(insertBeforeExtension(path, '_' + lightColor));
        }
        if (hasVariant) {
            tries.add(insertBeforeExtension(path, variant));
        }
        tries.add(path);

        for (final String candidatePath : tries) {
            final String candidate = namespace + ':' + candidatePath;
            if (exists(candidate)) {
                return candidate;
            }
        }
        if (REPORTED_MISSING.add(raw + '#' + (hasVariant ? variant : "") + '@'
                + (colored ? lightColor : ""))) {
            LteLog.LOGGER.info("[lte] texture {} is not in {}{} -> keeping the resource-pack id",
                    raw, namespace, hasVariant ? " as " + tries.get(0) : "");
        }
        return raw;
    }

    /** {@code a/b.png} + {@code _l2} → {@code a/b_l2.png}。 */
    private static String insertBeforeExtension(final String path, final String suffix) {
        return path.endsWith(".png")
                ? path.substring(0, path.length() - 4) + suffix + ".png"
                : path + suffix + ".png";
    }

    /** 门灯贴图：{@code light_on/off.png} 与 {@code apg_door_light_on/off.png}。 */
    private static boolean isDoorLight(final String path) {
        return path.endsWith("light_on.png") || path.endsWith("light_off.png");
    }

    /** 资源包栈里有没有这张图（{@code getAllResources} 只在完全找不到时返回空表）。 */
    private static boolean exists(final String id) {
        try {
            final MinecraftClient client = MinecraftClient.getInstance();
            if (client == null) {
                return false;
            }
            final Identifier identifier = Identifier.tryParse(id);
            if (identifier == null) {
                return false;
            }
            return !client.getResourceManager().getAllResources(identifier).isEmpty();
        } catch (Throwable t) {
            // 资源管理器没就绪（启动第一帧）/ 被别的模组换掉：当作没有，下一帧再试。
            return false;
        }
    }

    /** 换了资源管理器（= 资源包重载）就把缓存清空：旧的「没有这张图」结论不再成立。 */
    private static void resetIfResourceManagerChanged() {
        try {
            final MinecraftClient client = MinecraftClient.getInstance();
            if (client == null) {
                return;
            }
            final ResourceManager manager = client.getResourceManager();
            if (manager != lastManager) {
                lastManager = manager;
                RESOLVED.clear();
                REPORTED_MISSING.clear();
            }
        } catch (Throwable ignored) {
            // 启动阶段拿不到资源管理器：保持现有缓存即可。
        }
    }

    /** 调试用：当前缓存了多少条映射。 */
    public static int cachedCount() {
        return RESOLVED.size();
    }
}
