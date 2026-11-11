package dev.js.mtrheadlight.mtr;

import dev.js.mtrheadlight.HeadlightConfig;
import dev.js.mtrheadlight.MtrHeadlightClient;
import mtr.client.ClientData;
import mtr.client.TrainClientRegistry;
import mtr.client.TrainProperties;
import mtr.data.Siding;
import mtr.data.TrainClient;
import mtr.data.TransportMode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 屏蔽 MTR 内置车型（{@code disableBuiltInTrains}），同时保证<b>资源包 / 附属模组注册的车型
 * 一个都不会掉</b>。
 *
 * <h2>MTR 3.6.4.2 的真实结构（javap 证据）</h2>
 * <pre>
 * mtr.client.TrainClientRegistry
 *   private static final Map&lt;String, TrainProperties&gt; REGISTRY;
 *   private static final Map&lt;TransportMode, List&lt;String&gt;&gt; KEY_ORDERS;
 *   public static void reset();     // REGISTRY.clear() + KEY_ORDERS.clear() + 114 个内置
 *   public static void register(String, TrainProperties);   // 只 put，不 clear
 * mtr.client.CustomResources
 *   public static void reload(ResourceManager)
 *       0: invokestatic TrainClientRegistry.reset:()V      // 第 0 条指令就清空
 *      随后才读 mtr_custom_resources.json，逐个 register(...)
 * </pre>
 *
 * <h2>真正的坑：reset() 会把资源包的车型一起清掉</h2>
 * {@code reset()} 的第一条指令是 {@code REGISTRY.clear()}。资源包的车型<b>只在
 * {@code CustomResources.reload} 里注册一次</b>，而 {@code reload} 除了启动/换包时不会再被调用。
 * 于是任何在资源包加载之后发生的 {@code reset()}（旧实现里 {@code tick()} 一进世界就会调一次）
 * 都会把资源包车型从注册表里抹掉，而且<b>之后没有任何代码会把它们放回去</b>——车厂选车面板里
 * 资源包的车就全没了，进来的列车 {@code getTrainProperties} 回落到
 * {@code getBlankProperties()}，连渲染器都没有。这正是"开了过滤之后资源包的车也不见了"。
 *
 * <p>旧实现还有第二个隐患（本类注释里原先写着的推测）：{@code onReset} 把每次 {@code reset()}
 * 的 keys <b>并进</b> {@code BUILT_IN}。实测 {@code reset()} 先 clear 再注册，所以 TAIL 看到的
 * 永远只有 114 个内置 id，这个并集其实并不会混进资源包 id——它不是这次的病根，但仍然不该存在
 * （任何改了这句 clear 的宿主分支都会让它变成病根），所以现在只认第一次快照。
 *
 * <h2>做法</h2>
 * <ol>
 *   <li>{@link #beforeReset()}：记下这次 {@code reset()} 是不是我们自己发起的。MTR 自己的
 *       {@code reload} 会紧跟着重新注册资源包，所以那次清空 {@link #EXTERNAL}；我们自己发起的
 *       reset 之后没有任何东西会重新注册，所以保留 {@link #EXTERNAL} 并在 TAIL 手动补回。</li>
 *   <li>{@link #afterReset(Map)}：只在<b>第一次</b> {@code reset()} 的 TAIL 采集内置 id 快照
 *       （此刻注册表里只有 114 个内置车型）。然后（只有我们自己发起的 reset）把
 *       {@link #EXTERNAL} 里丢了的一一 {@code register} 回去。</li>
 *   <li>{@link #onRegister(String, TrainProperties)}：{@code reset()} 之外发生的每一次
 *       {@code register} 都记进 {@link #EXTERNAL}——那一定是资源包 / 附属模组。</li>
 *   <li>{@link #prune(Map, Map)}：只删"快照里的内置 id ∩ 不在 EXTERNAL ∩ 没在用"。资源包 id
 *       根本不在快照里，无论如何都碰不到。</li>
 * </ol>
 *
 * <h2>在用 id 从哪来</h2>
 * {@code ClientData.TRAINS} 的 {@code trainId} 与 {@code ClientData.SIDINGS} 的
 * {@code getTrainId()}，两边都转小写比较（{@code register} 写进去的 key 就是小写）。列车开出
 * 同步半径会从 {@code TRAINS} 里消失，所以取"曾经用到过"的并集，避免裁剪反复来回。
 *
 * <h2>时序</h2>
 * 进世界后先等 {@value #JOIN_GRACE_TICKS} tick（2 s）让 {@code ClientData} 同步，之后只在
 * "在用集合变了"时重来一次，并带 {@value #RESET_COOLDOWN_TICKS} tick 冷却。
 *
 * <h2>失败静默</h2>
 * 全类每一处对外调用都包了 {@code Throwable}。mixin 没应用（{@code defaultRequire: 0}）时
 * {@code resetSafely()} 的自检会发现"调用过 reset() 但 TAIL 从没触发"并永久关掉自己。
 */
public final class BuiltInTrains {
    /** 进世界后等多久才允许第一次裁剪：40 tick = 2 s（ClientData 的列车/侧线同步）。 */
    private static final int JOIN_GRACE_TICKS = 40;
    /** 两次 {@code reset()} 之间的冷却，防止"在用集合一直对不上"时每 tick 重建 114 个模型。 */
    private static final int RESET_COOLDOWN_TICKS = 60;
    /** 数据为空（没有列车也没有侧线）时最多等这么久再裁，避免空世界永远不生效。 */
    private static final int EMPTY_DATA_GRACE_TICKS = 200;
    /** 诊断行里最多列几个被删 id。 */
    private static final int DIAG_ID_COUNT = 5;

    /**
     * 第一次 {@code reset()} 结束时注册表里的 id 全集 = MTR 内置车型全集（小写）。
     * <b>只写一次</b>：后续 reset 不得追加。
     */
    private static final Set<String> BUILT_IN = new LinkedHashSet<>();

    /**
     * 资源包 / 附属模组通过 {@code TrainClientRegistry.register} 注册的车型（小写 id → 属性）。
     * 这些 id 永远不进 {@link #BUILT_IN}，也永远不被 {@code prune} 删除；我们自己 {@code reset()}
     * 之后还要靠这份表把它们注册回去。
     */
    private static final Map<String, TrainProperties> EXTERNAL = new LinkedHashMap<>();

    /** {@code reset()} HEAD 与 TAIL 之间为 true：这期间的 register 全是内置车型。 */
    private static boolean inReset;
    /** 当前这次 reset() 是不是 {@link #resetSafely()} 发起的（= 需要手工补回资源包车型）。 */
    private static boolean ourReset;
    /** {@link #resetSafely()} 在调用 {@code reset()} 前设 true，调用后立刻清掉。 */
    private static boolean ourResetPending;

    /** 上一次裁剪时保留的"在用 id"快照；null = 还没裁过。 */
    private static Set<String> appliedUsed;
    /**
     * 这个存档里"曾经用到过"的车型（只增不减，换存档才清）。
     *
     * <p>MTR 的列车数据是按范围同步的：一列车开出同步半径就从 {@code ClientData.TRAINS} 里消失，
     * 再开回来又出现。只用"当前这一 tick 的集合"会让裁剪反复来回（每次回来都要 reset() 一遍、
     * 重建 114 个模型）。所以这里取并集，代价只是几个字符串。
     */
    private static final Set<String> SEEN_USED = new HashSet<>();
    /** 曾经真的删过东西（用于开关关掉时把注册表恢复原样）。 */
    private static boolean trimmed;
    /** 出过一次错就永久停手。 */
    private static boolean disabled;
    /** 本轮 {@code reset()} 里 mixin 的 TAIL 有没有跑过（自检用）。 */
    private static boolean pruneRan;

    // ---- 最近一次裁剪的结果（{@link #reloadNow()} 的聊天回执与诊断行共用） ----------------------
    /** 上一次 prune 删掉的 id 数。 */
    private static int lastRemoved;
    /** 上一次 prune 之后仍然留在注册表里的内置 id 数。 */
    private static int lastKept;
    /** 上一次 prune 之后仍然在注册表里的资源包车型数（应与 {@link #EXTERNAL} 的总数相等）。 */
    private static int lastExternalPresent;
    /** 上一次 prune 是否因为「还没进存档」而什么都没做。 */
    private static boolean lastPruneSawWorld = true;

    private static ClientWorld lastWorld;
    private static long worldTicks;
    private static long lastResetTick = -RESET_COOLDOWN_TICKS;
    private static boolean loggedFirstTrim;
    private static boolean loggedWaitingForWorld;
    private static boolean loggedSnapshot;

    private BuiltInTrains() {
    }

    /**
     * 由 mixin 在 {@code TrainClientRegistry#reset()} 的 HEAD 调用：标记"现在在 reset 里"，
     * 并判断这次 reset 是我们自己发起的还是 MTR 的资源重载。
     *
     * <p>MTR 自己的 {@code CustomResources.reload} 会在 reset 之后重新注册所有资源包车型，所以
     * 那一刻起 {@link #EXTERNAL} 就该作废（否则删掉的包会阴魂不散）；我们自己发起的 reset 没有人
     * 会补注册，所以必须保留并在 TAIL 手工补回。
     */
    public static void beforeReset() {
        inReset = true;
        ourReset = ourResetPending;
        if (!ourReset) {
            EXTERNAL.clear();
        }
    }

    /**
     * 由 mixin 在同一个 TAIL 里、{@link #prune} <b>之前</b>调用。
     *
     * <ol>
     *   <li>第一次 reset：把此刻的 keySet 存成内置车型快照（此后不再改动）。</li>
     *   <li>我们自己发起的 reset：把 {@link #EXTERNAL} 里被 clear 掉的车型注册回去。</li>
     * </ol>
     */
    public static void afterReset(final Map<String, TrainProperties> registry) {
        try {
            if (registry != null) {
                if (BUILT_IN.isEmpty()) {
                    for (final String key : registry.keySet()) {
                        if (key != null) {
                            BUILT_IN.add(key.toLowerCase(Locale.ENGLISH));
                        }
                    }
                    if (!loggedSnapshot && !BUILT_IN.isEmpty()) {
                        loggedSnapshot = true;
                        MtrHeadlightClient.LOGGER.info(
                                "[lte] builtin trains: snapshot={} (first reset, built-in ids only)",
                                Integer.valueOf(BUILT_IN.size()));
                    }
                }
                if (ourReset) {
                    restoreExternal(registry);
                }
            }
        } catch (Throwable t) {
            fail(t);
        } finally {
            inReset = false;
            ourReset = false;
        }
    }

    /**
     * 由 mixin 在 {@code TrainClientRegistry#register(String, TrainProperties)} 的 TAIL 调用。
     *
     * <p>{@code reset()} 内部的 114 次 register 全是内置车型，跳过；其余任何时候的 register
     * 都只可能来自资源包 / 附属模组，记进 {@link #EXTERNAL} —— 它们从此受保护。
     */
    public static void onRegister(final String id, final TrainProperties props) {
        if (disabled || id == null || props == null) {
            return;
        }
        if (inReset) {
            // reset() 自己注册的内置车型：快照由 afterReset 统一采集，这里不掺和。
            return;
        }
        try {
            final String key = id.toLowerCase(Locale.ENGLISH);
            if (BUILT_IN.contains(key)) {
                // 同名覆盖（附属模组替换某个内置车型）：按内置处理，不保护——否则会挡住裁剪。
                return;
            }
            EXTERNAL.put(key, props);
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** 把我们 reset() 掉的资源包车型按原样注册回去，返回补回的数量。 */
    private static int restoreExternal(final Map<String, TrainProperties> registry) {
        if (EXTERNAL.isEmpty()) {
            return 0;
        }
        int restored = 0;
        for (final Map.Entry<String, TrainProperties> entry : EXTERNAL.entrySet()) {
            final String id = entry.getKey();
            if (id == null || registry.containsKey(id)) {
                continue;
            }
            try {
                // register() 自己会处理 KEY_ORDERS（含去重），所以顺序 / 显示列表都不用我们操心。
                TrainClientRegistry.register(id, entry.getValue());
                restored++;
            } catch (Throwable t) {
                // 单个车型失败不能连累其它车型。
            }
        }
        if (restored > 0) {
            MtrHeadlightClient.LOGGER.info(
                    "[lte] builtin trains: restored {} resource-pack train(s) after reset (external={})",
                    Integer.valueOf(restored), Integer.valueOf(EXTERNAL.size()));
        }
        return restored;
    }

    /**
     * 由 mixin 在同一个 TAIL 里紧随 {@link #afterReset} 调用：把"内置 且 没在用"的 id 从
     * {@code REGISTRY} 与 {@code KEY_ORDERS} 里一起删掉。世界还没加载（拿不到在用集合）时什么都不做。
     *
     * @param registry MTR 的 {@code REGISTRY}（key 已小写）
     * @param orders   MTR 的 {@code KEY_ORDERS}（每个 TransportMode 的显示顺序）
     */
    public static void prune(final Map<String, TrainProperties> registry,
                             final Map<TransportMode, List<String>> orders) {
        pruneRan = true;
        lastRemoved = 0;
        lastPruneSawWorld = true;
        if (disabled || registry == null) {
            return;
        }
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        if (cfg == null || !cfg.disableBuiltInTrains || BUILT_IN.isEmpty()) {
            lastKept = countPresent(registry, BUILT_IN);
            lastExternalPresent = countPresent(registry, EXTERNAL.keySet());
            return;
        }
        final Set<String> used = usedIds();
        if (used == null) {
            // 还没进世界：无法判断哪些在用，一个字都不删（下次进世界后的 tick 会重来）。
            lastPruneSawWorld = false;
            lastKept = countPresent(registry, BUILT_IN);
            lastExternalPresent = countPresent(registry, EXTERNAL.keySet());
            if (cfg.builtinTrainsDiagnostics && !loggedWaitingForWorld) {
                loggedWaitingForWorld = true;
                MtrHeadlightClient.LOGGER.info(
                        "[lte] builtin trains: snapshot={} external={} waiting for world (nothing pruned)",
                        Integer.valueOf(BUILT_IN.size()), Integer.valueOf(EXTERNAL.size()));
            }
            return;
        }
        loggedWaitingForWorld = false;

        final List<String> removedIds = new ArrayList<>();
        try {
            for (final Iterator<Map.Entry<String, TrainProperties>> it = registry.entrySet().iterator();
                 it.hasNext(); ) {
                final Map.Entry<String, TrainProperties> entry = it.next();
                final String key = entry.getKey();
                if (key == null) {
                    continue;
                }
                final String id = key.toLowerCase(Locale.ENGLISH);
                // 三道闸：必须是内置快照里的 id、必须不是资源包注册的、必须没在用。
                if (!BUILT_IN.contains(id) || EXTERNAL.containsKey(id) || used.contains(id)) {
                    continue;
                }
                it.remove();
                removedIds.add(id);
            }
            if (!removedIds.isEmpty() && orders != null) {
                // KEY_ORDERS 必须同步：forEach 按它遍历再 REGISTRY.get(key)，只删 REGISTRY 会让
                // SidingScreen 拿到 null 的 TrainProperties（选车面板直接 NPE）。
                for (final List<String> order : orders.values()) {
                    if (order != null) {
                        order.removeAll(removedIds);
                    }
                }
            }
        } catch (Throwable t) {
            fail(t);
            return;
        }
        appliedUsed = new HashSet<>(used);
        if (!removedIds.isEmpty()) {
            trimmed = true;
        }
        lastRemoved = removedIds.size();
        lastKept = countPresent(registry, BUILT_IN);
        lastExternalPresent = countPresent(registry, EXTERNAL.keySet());
        if (cfg.builtinTrainsDiagnostics) {
            MtrHeadlightClient.LOGGER.info(
                    "[lte] builtin trains: snapshot={} builtin={} external={}/{} used={} removed={} kept={}",
                    Integer.valueOf(BUILT_IN.size()), Integer.valueOf(BUILT_IN.size()),
                    Integer.valueOf(lastExternalPresent), Integer.valueOf(EXTERNAL.size()),
                    Integer.valueOf(used.size()), Integer.valueOf(lastRemoved),
                    Integer.valueOf(lastKept));
            if (!removedIds.isEmpty()) {
                MtrHeadlightClient.LOGGER.info("[lte] builtin trains: removed ids {}",
                        removedIds.subList(0, Math.min(DIAG_ID_COUNT, removedIds.size())));
            }
        } else if (!removedIds.isEmpty() && !loggedFirstTrim) {
            loggedFirstTrim = true;
            MtrHeadlightClient.LOGGER.info(
                    "[lte] builtin trains: {} ids, kept {} (in use), removed {}", Integer.valueOf(BUILT_IN.size()),
                    Integer.valueOf(used.size()), Integer.valueOf(removedIds.size()));
        }
    }

    /**
     * 客户端 tick 钩子（1.20.1 的 entrypoint 注册）。
     *
     * <p>只做三件事：开关关掉时把注册表恢复原样、进世界后等数据稳定、在"在用集合变化"时
     * {@code reset()} 一次（TAIL 会立刻按新集合裁剪，并把资源包车型补回来）。
     */
    public static void tick(final HeadlightConfig cfg, final MinecraftClient client) {
        if (disabled || cfg == null || client == null) {
            return;
        }
        if (!cfg.disableBuiltInTrains) {
            if (trimmed) {
                // 开关关掉：reset() 会把内置车型原样注册回来（TAIL 里 flag 为 false，不会删）。
                trimmed = false;
                appliedUsed = null;
                loggedFirstTrim = false;
                resetSafely();
            }
            return;
        }
        if (BUILT_IN.isEmpty()) {
            // 还没见过一次 reset()：要么 mixin 没应用，要么资源还没加载。静默等着。
            return;
        }
        final ClientWorld world = client.world;
        if (world == null) {
            lastWorld = null;
            worldTicks = 0;
            return;
        }
        if (world != lastWorld) {
            lastWorld = world;
            worldTicks = 0;
            // 车型 id 是每个存档各自的，换世界就重新开始记（否则上一个存档的车型会一直留着）。
            SEEN_USED.clear();
            appliedUsed = null;
        }
        worldTicks++;

        // 数据就绪门槛：进世界头 2 秒（或侧线/列车都还空着的头 10 秒）不动注册表。
        if (worldTicks < JOIN_GRACE_TICKS && !dataReady()) {
            return;
        }
        if (!dataReady() && worldTicks < EMPTY_DATA_GRACE_TICKS) {
            return;
        }

        final Set<String> used = usedIds();
        if (used == null || used.equals(appliedUsed)) {
            return;
        }
        if (worldTicks - lastResetTick < RESET_COOLDOWN_TICKS) {
            return;
        }
        lastResetTick = worldTicks;
        resetSafely();
    }

    /** 当前存档"用到过"的车型 id（小写，跨 tick 只增不减）。世界没加载时返回 null（= 不判断）。 */
    private static Set<String> usedIds() {
        final MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null) {
            return null;
        }
        try {
            for (final TrainClient train : ClientData.TRAINS) {
                if (train != null && train.trainId != null) {
                    SEEN_USED.add(train.trainId.toLowerCase(Locale.ENGLISH));
                }
            }
            for (final Siding siding : ClientData.SIDINGS) {
                if (siding == null) {
                    continue;
                }
                final String id = siding.getTrainId();
                if (id != null && !id.isEmpty()) {
                    SEEN_USED.add(id.toLowerCase(Locale.ENGLISH));
                }
            }
        } catch (RuntimeException e) {
            // 同步中途集合被换掉：这一 tick 不判断，下一 tick 还有机会。
            return null;
        }
        return SEEN_USED;
    }

    private static boolean dataReady() {
        try {
            return !ClientData.TRAINS.isEmpty() || !ClientData.SIDINGS.isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * {@code /mtr_lte trains reload} 与配置界面「重载内置车型」按钮的唯一实现：把
     * {@code TrainClientRegistry} 整个重建一遍（MTR 的 {@code reset()} 先清空再注册 114 个内置
     * 车型），TAIL 里把受保护的资源包车型注册回去，再按当前 {@code disableBuiltInTrains} 决定是否
     * 裁掉没在用的内置车型。
     *
     * <p>这也是「切换开关后立即生效」的实现：它不依赖 {@link #tick} 的进世界宽限和 60 tick 冷却，
     * 调用一次就按现在的开关状态重建一次注册表。开关由调用方（指令 / 界面）先写进配置。
     *
     * @return 一句可以直接放进聊天栏的结果（保留 / 删除数量 + 资源包车型是否完整）
     */
    public static String reloadNow() {
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        if (disabled) {
            return "LTE：内置车型过滤已停用（mixin 未生效，详见日志）";
        }
        if (cfg == null) {
            return "LTE：内置车型重载失败（配置尚未加载）";
        }
        final boolean trim = cfg.disableBuiltInTrains;
        // 重建之后由 prune() 重新决定注册表内容，所以先把上次的记账清掉：否则 tick() 可能还按
        // 「上次裁过」的状态在同一个 tick 里再 reset() 一次。
        trimmed = false;
        appliedUsed = null;
        loggedFirstTrim = false;
        loggedWaitingForWorld = false;
        lastResetTick = worldTicks;
        resetSafely();
        if (disabled) {
            return "LTE：内置车型重载失败（TrainClientRegistry.reset() 的 mixin 未生效）";
        }
        if (!trim) {
            return "LTE：内置车型已恢复 — 保留 " + lastKept + " / 删除 0（内置 " + BUILT_IN.size()
                    + "，资源包 " + lastExternalPresent + "/" + EXTERNAL.size() + "）";
        }
        if (!lastPruneSawWorld) {
            return "LTE：内置车型已重载 — 未进入存档，暂不裁剪（内置 " + BUILT_IN.size()
                    + "，资源包 " + lastExternalPresent + "/" + EXTERNAL.size() + "）";
        }
        return "LTE：内置车型已重载 — 保留 " + lastKept + " / 删除 " + lastRemoved
                + "（内置 " + BUILT_IN.size() + "，资源包 " + lastExternalPresent + "/"
                + EXTERNAL.size() + "）";
    }

    /** {@code ids} 里有多少个此刻还在 {@code registry} 中（回执与诊断行共用）。 */
    private static int countPresent(final Map<String, TrainProperties> registry,
                                    final Iterable<String> ids) {
        int present = 0;
        try {
            for (final String id : ids) {
                if (id != null && registry.containsKey(id)) {
                    present++;
                }
            }
        } catch (RuntimeException ignored) {
            // 同步中途集合被换掉：报个偏小的数，不为它抛异常。
        }
        return present;
    }

    /** {@code reset()} 重建全部内置车型；TAIL 若没跑到，说明 mixin 不在，永久停手。 */
    private static void resetSafely() {
        pruneRan = false;
        ourResetPending = true;
        try {
            TrainClientRegistry.reset();
        } catch (Throwable t) {
            fail(t);
            return;
        } finally {
            ourResetPending = false;
        }
        if (!pruneRan) {
            fail(new IllegalStateException(
                    "TrainClientRegistry.reset() ran but the mixin never fired"));
        }
    }

    /** 永久停手并留一条 WARN（mixin 与主类共用）。 */
    public static void fail(final Throwable t) {
        if (disabled) {
            return;
        }
        disabled = true;
        try {
            MtrHeadlightClient.LOGGER.warn(
                    "[lte] built-in train filter disabled: {}", String.valueOf(t));
        } catch (Throwable ignored) {
            // 日志本身也不许抛出。
        }
    }
}
