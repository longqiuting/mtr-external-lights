// 方块注册表自检：把「建了但没注册 / 没有 state」的方块点名，并清掉会炸掉冻结校验的残留引用。
package dev.js.mtrheadlight.server;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.js.mtrheadlight.LteLog;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.Identifier;
import net.minecraft.util.registry.Registry;

/**
 * 方块注册表自检 —— 给「专用服务端启动时
 * {@code IllegalStateException: Some intrusive holders were not registered}」这类崩溃点名。
 *
 * <h2>崩溃长什么样、为什么看不出是谁</h2>
 * <pre>
 *   java.lang.IllegalStateException: Some intrusive holders were not registered:
 *     [Reference{null=Block{minecraft:air}}, ... × N]
 *       at net.minecraft.util.registry.SimpleRegistry.freeze
 * </pre>
 * <p>1.18.2 里 {@code Block} 的构造器一执行就把它自己挂进注册表的「待绑定」表
 * （{@code SimpleRegistry.unfrozenValueToEntry} / {@code field_36465}）：
 * <pre>
 *   // net.minecraft.block.Block#&lt;init&gt;
 *   this.registryEntry = Registry.BLOCK.createEntry(this);
 * </pre>
 * 只有 {@code Registry.register(BLOCK, id, block)}（内部走 {@code SimpleRegistry.set}）才会把那个
 * {@code RegistryEntry.Reference} 的键和值补上。某处 {@code new XxxBlock(...)} 抛了、或者注册那一步
 * 被跳过，条目就留在表里，注册表冻结（数据包加载末期）时原版直接抛。
 *
 * <p>而 {@code Block#toString()} 是 {@code "Block{" + Registry.BLOCK.getId(this) + "}"}，
 * 未注册的方块查 id 拿到的是<b>默认 id</b>（BLOCK 是 {@code DefaultedRegistry}，默认 =
 * {@code minecraft:air}），于是不管漏了几个、是哪几个，崩溃信息里全是 {@code Block{minecraft:air}}。
 *
 * <h2>1.18.2 的 freeze 有两处同名校验</h2>
 * <pre>
 *   public Registry&lt;T&gt; freeze() {
 *       frozen = true;
 *       // ① keyToEntry 里「有键没值」的 standalone 引用（Registry#getOrCreateEntry 造的）
 *       if (!list.isEmpty()) throw new IllegalStateException(getKey() + ": Some intrusive holders were not registered: " + list);
 *       // ② unfrozenValueToEntry 里「有值没键」的引用（Block 构造器 createEntry 造的）
 *       if (!list2.isEmpty()) throw new IllegalStateException("Some intrusive holders were not registered: " + list2);
 *       unfrozenValueToEntry = null;
 *       return this;
 *   }
 * </pre>
 * <p>两处抛的都是同一句话，本类处理 <b>②</b>：方块构造器产生的就是这一类，所有模组的方块都走这条路。
 * ①那一类要靠 {@code getOrCreateEntry(RegistryKey)} 才会出现，本模组不用它，也就不去动它
 * （改它得删 {@code keyToEntry} 条目，比删一张纯缓存的表风险大）。
 *
 * <h2>本类做三件事</h2>
 * <ol>
 *   <li>{@link #reportBlocks}：遍历 {@code Registry.BLOCK}，数出总数 N，并把「默认态是空气」的方块
 *       连类名一起 WARN（{@code [lte] unregistered block: class=…}），末尾一行
 *       {@code [lte] block registry: N blocks, M unregistered}。这一条对<b>任何模组</b>建的方块都有效；</li>
 *   <li>{@link #purgePending}：读「待绑定」表 —— 表里的方块<b>根本不在注册表里</b>，
 *       遍历注册表看不到它们，必须走这张表。点名（类名 + 默认态）后把残留引用删掉：
 *       这些实例除了这张表没有任何引用（不在注册表、也不在任何字段里），留着只会让冻结校验抛，
 *       删掉之后服务端能正常起到 {@code Done}；</li>
 *   <li>{@link #onFreeze}：由 {@code SimpleRegistryFreezeMixin} 在
 *       {@code SimpleRegistry.freeze()} 的 HEAD 调用 —— 这是<b>冻结前最后的位置</b>，
 *       此时所有模组的注册都已完成，任何模组的漏网方块都会被点名。</li>
 * </ol>
 *
 * <h2>为什么用反射读私有字段</h2>
 * <p>字段名在开发环境（{@code unfrozenValueToEntry}）与生产环境（{@code field_36465}）不同，
 * 反射按两个名字都试一遍，比加 accessor mixin + refmap 便宜。读不到就静默跳过：自检永远不许把服务端弄崩。
 */
public final class RegistrySelfCheck {

    /** 私有字段名：开发环境（yarn 名）/ 生产环境（intermediary 名）。 */
    private static final String[] PENDING_FIELDS = {"unfrozenValueToEntry", "field_36465"};

    private RegistrySelfCheck() {
    }

    // ------------------------------------------------------------------ 注册表扫描

    /**
     * 扫一遍已注册方块：{@code [lte] block registry: N blocks, M unregistered}。
     *
     * <p>方块在注册表里但默认态是空气 = 它的 {@code StateManager} 没建起来（构造器中途退出）。
     * 正常模组不会有这种条目，M 一般就是 0 —— 有值时这一行 + 上面那些
     * {@code [lte] unregistered block: class=…} 就是真凶名单。
     */
    public static void reportBlocks(final String phase) {
        int total = 0;
        int unregistered = 0;
        for (final Block block : Registry.BLOCK) {
            total++;
            if (block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR) {
                continue;
            }
            try {
                final BlockState state = block.getDefaultState();
                if (state == null || !state.isAir()) {
                    continue;
                }
                unregistered++;
                LteLog.LOGGER.warn("[lte] unregistered block: class={} id={} default_state={} ({} phase)",
                        block.getClass().getName(), String.valueOf(idOf(block)), String.valueOf(state), phase);
            } catch (final Throwable t) {
                unregistered++;
                LteLog.LOGGER.warn("[lte] unregistered block: class={} id={} default_state=<unreadable: {}> ({} phase)",
                        block.getClass().getName(), String.valueOf(idOf(block)), t.toString(), phase);
            }
        }
        LteLog.LOGGER.info("[lte] block registry: {} blocks, {} unregistered", Integer.valueOf(total),
                Integer.valueOf(unregistered));
    }

    private static Identifier idOf(final Block block) {
        try {
            return Registry.BLOCK.getId(block);
        } catch (final Throwable t) {
            return null;
        }
    }

    /** 方块注册表里现在有多少条目（含其它模组的）。 */
    public static int countBlocks() {
        int n = 0;
        for (final Block ignored : Registry.BLOCK) {
            n++;
        }
        return n;
    }

    /** 某个命名空间有多少方块 —— 用来量「这个模块到底注册进去了几块」。 */
    public static int countBlocks(final String namespace) {
        int n = 0;
        for (final Block block : Registry.BLOCK) {
            final Identifier id = idOf(block);
            if (id != null && namespace.equals(id.getNamespace())) {
                n++;
            }
        }
        return n;
    }

    /**
     * 「待绑定」表里<b>还没绑定</b>的条目数 —— 也就是「建了但还没注册」的方块数。
     *
     * <p>1.18.2 与 1.19.4 有一处关键差别：1.19.4 的 {@code intrusiveValues} 在
     * {@code Registry.register} 时会把条目移掉，所以「表大小」就等于漏网数；1.18.2 的表
     * （{@code unfrozenValueToEntry}）是一张<b>按值索引的引用缓存</b>，注册只是把那个
     * {@code RegistryEntry.Reference} 原地绑定（{@code Reference.setKeyAndValue}，
     * {@code valueToEntryFunction == Block::getRegistryEntry}），条目<b>留在表里不删</b>。
     * 所以这里不能直接返回 {@code size()}（那会把所有方块都数进来），要按原版 freeze 的判据
     * 逐条挑出「还没进注册表」的。
     *
     * <p>注册表冻结之后这张表会被原版置空（{@code freeze()} 末尾），此时返回 0。
     */
    public static int pendingCount() {
        final Map<Object, Object> pending = pendingTable(Registry.BLOCK);
        if (pending == null) {
            return 0;
        }
        int n = 0;
        for (final Object key : pending.keySet()) {
            // 不能用 getId() 判：DefaultedRegistry 对未注册的方块返回默认 id（minecraft:air）。
            if (!(key instanceof Block) || Registry.BLOCK.getKey((Block) key).isEmpty()) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ 待绑定表（真正的漏网户）

    /**
     * 读注册表实例上的「待绑定」表，点名 + 删除还没拿到 id 的方块。
     *
     * <p>只有<b>冻结之前</b>调用才有意义：原版冻结成功后会把这张表置空。
     * 表为空 / 已经置空 / 读不到都直接返回，不产生任何日志（自检要便宜）。
     */
    @SuppressWarnings("unchecked")
    public static void purgePending(final String phase) {
        final Map<Object, Object> pending = pendingTable(Registry.BLOCK);
        if (pending == null || pending.isEmpty()) {
            return;
        }

        final List<Object> leaked = new ArrayList<>();
        for (final Map.Entry<Object, Object> entry : pending.entrySet()) {
            final Object key = entry.getKey();
            if (!(key instanceof Block)) {
                leaked.add(key);
                continue;
            }
            final Block block = (Block) key;
            // 不能用 getId() 判：DefaultedRegistry 对未注册的方块返回默认 id（minecraft:air）。
            if (Registry.BLOCK.getKey(block).isEmpty()) {
                leaked.add(key);
                LteLog.LOGGER.warn("[lte] unregistered block: class={} state={} (never reached the registry,"
                                + " id lookup would fall back to minecraft:air) [{} phase]",
                        block.getClass().getName(), safeState(block), phase);
            }
        }
        if (leaked.isEmpty()) {
            return;
        }
        for (final Object key : leaked) {
            pending.remove(key);
        }
        LteLog.LOGGER.warn("[lte] dropped {} leaked block reference(s) before the block registry froze [{} phase]"
                        + " - without this the server would crash with 'Some intrusive holders were not registered'."
                        + " The blocks named above are missing from this run (their registration threw).",
                Integer.valueOf(leaked.size()), phase);
    }

    private static String safeState(final Block block) {
        try {
            return String.valueOf(block.getDefaultState());
        } catch (final Throwable t) {
            return "<unreadable: " + t + ">";
        }
    }

    /**
     * 由 {@code SimpleRegistryFreezeMixin} 在 {@code freeze()} 的 HEAD 调用。
     *
     * <p>只处理方块注册表：别的注册表（物品 / 声音 / 方块实体类型）同理可查，
     * 但本条崩溃只关心方块，少做一次全表扫描更便宜。
     *
     * <p>1.18.2 的 {@code Registry.BLOCK} 是 {@code DefaultedRegistry}（{@code SimpleRegistry} 的子类），
     * 那张待绑定表就定义在父类上，反射沿继承链能找到。
     */
    public static void onFreeze(final Registry<?> registry) {
        if (registry != Registry.BLOCK) {
            return;
        }
        try {
            purgePending("freeze");
        } catch (final Throwable t) {
            // 自检失败绝不能让 freeze 跟着失败。
            LteLog.LOGGER.debug("[lte] freeze-time block self-check failed: {}", t.toString());
        }
    }

    /** 取「待绑定」表（{@code null} = 已经冻结过，或读不到）。 */
    @SuppressWarnings("unchecked")
    private static Map<Object, Object> pendingTable(final Object registry) {
        final Field field = findPendingField(registry.getClass());
        if (field == null) {
            return null;
        }
        try {
            field.setAccessible(true);
            final Object raw = field.get(registry);
            return raw instanceof Map ? (Map<Object, Object>) raw : null;
        } catch (final Throwable t) {
            return null;
        }
    }

    /** 沿类继承链找「待绑定」表字段（yarn 名 / intermediary 名）。 */
    private static Field findPendingField(final Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (final String name : PENDING_FIELDS) {
                try {
                    return c.getDeclaredField(name);
                } catch (final NoSuchFieldException ignored) {
                    // 试下一个名字 / 下一个父类。
                }
            }
        }
        return null;
    }
}
