package dev.js.mtrheadlight.compat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

import net.minecraft.item.ItemConvertible;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;

/**
 * 1.19.2 上的 {@code net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents} 替身。
 *
 * <h2>为什么要替身</h2>
 * <p>{@code fabric-item-group-api-v1}（{@code FabricItemGroup.builder()} + {@code ItemGroupEvents} +
 * {@code Registries.ITEM_GROUP}）是 1.19.3 才进 fabric-api 的；1.19.2 的 fabric-api 0.77.0 只有老线的
 * {@code FabricItemGroupBuilder}（{@code create(id).icon(..).appendItems(Consumer<List<ItemStack>>)}），
 * 页签本身也不是注册表条目，而是塞在 {@code ItemGroup.GROUPS} 数组里。
 *
 * <p>老线的 {@code appendItems} 只给一个「正在拼的 {@code List<ItemStack>}」，没有「按 id 追加」的概念，
 * 而 1.20.1 侧的代码是<b>按 id 分散</b>在十几个模块里追加条目的（谁注册谁追加，顺序 = 注册顺序）。
 * 所以这里做一层登记表：{@link #modifyEntriesEvent(Identifier)} 只登记回调，等页签真正在建列表时
 * （{@code ItemGroupTabs} 里那个 {@code appendItems}）按登记顺序一次性执行。
 *
 * <h2>语义对齐点</h2>
 * <ul>
 *   <li><b>顺序</b>：1.20.1 的回调按注册顺序执行，这里也是（{@link LinkedHashMap} + {@link List}）。</li>
 *   <li><b>{@code getDisplayStacks()}</b>：1.20.1 的 {@code ItemGroup.Entries#getDisplayStacks()} 返回
 *       页签正在拼的<b>可变</b>列表，{@code LteCoreItems#pinToolsTabOrder} 那套「摘出来再插回 0」的
 *       重排算法依赖它。这里返回同一个列表，语义一致。</li>
 *   <li><b>重复填充</b>：如果 fabric 复用了同一个列表实例重填（老线实现里通常每次新建），第二次就不再
 *       追加，避免页签里出现重复条目。</li>
 * </ul>
 */
public final class ItemGroupEvents {

    private static final Map<Identifier, List<Consumer<Entries>>> BY_TAB = new LinkedHashMap<>();

    /** 已经填过的列表实例（fabric 复用列表时防止重复追加）。 */
    private static final Map<List<ItemStack>, Boolean> FILLED = new WeakHashMap<>();

    private ItemGroupEvents() {
    }

    /**
     * 1.20.1 的 {@code ItemGroupEvents.modifyEntriesEvent(RegistryKey)}；1.19.2 没有页签注册表，
     * 所以直接收页签的 {@link Identifier}。
     */
    public static EntryModifier modifyEntriesEvent(final Identifier tabId) {
        return new EntryModifier(tabId);
    }

    /** 由 {@link ItemGroupTabs#create} 的 {@code appendItems} 回调：把登记过的监听按顺序跑一遍。 */
    public static void appendTo(final Identifier tabId, final List<ItemStack> stacks) {
        if (stacks == null || FILLED.containsKey(stacks)) {
            return;
        }
        FILLED.put(stacks, Boolean.TRUE);
        final List<Consumer<Entries>> listeners = BY_TAB.get(tabId);
        if (listeners == null || listeners.isEmpty()) {
            return;
        }
        final Entries entries = new Entries(stacks);
        // 监听里可能再登记新的监听（见 LteCoreItems#pinToolsTabOrder 的调用点），所以按 size 动态循环。
        for (int i = 0; i < listeners.size(); i++) {
            try {
                listeners.get(i).accept(entries);
            } catch (final Throwable ignored) {
                // 页签内容不该把创造栏拖崩：某一条失败就跳过。
            }
        }
    }

    /** {@code modifyEntriesEvent(..)} 的返回值：就是 1.20.1 的 {@code ItemGroupEvents.EntryModifier}。 */
    public static final class EntryModifier {
        private final Identifier tabId;

        EntryModifier(final Identifier tabId) {
            this.tabId = tabId;
        }

        public EntryModifier register(final Consumer<Entries> listener) {
            if (this.tabId != null && listener != null) {
                BY_TAB.computeIfAbsent(this.tabId, k -> new ArrayList<>()).add(listener);
            }
            return this;
        }
    }

    /**
     * 1.20.1 的 {@code ItemGroup.Entries} 里本工程用到的那一小块：
     * {@code add(..)} 四种形态 + {@code getDisplayStacks()}。
     */
    public static final class Entries {
        private final List<ItemStack> stacks;

        Entries(final List<ItemStack> stacks) {
            this.stacks = stacks;
        }

        public void add(final ItemStack stack) {
            if (stack != null && !stack.isEmpty()) {
                this.stacks.add(stack);
            }
        }

        /** {@code entries.add(item)} / {@code entries.add(block)}（1.19.2 的 {@code Block} 也是它）。 */
        public void add(final ItemConvertible item) {
            if (item != null) {
                this.add(new ItemStack(item));
            }
        }

        public void addAll(final Iterable<ItemStack> more) {
            if (more != null) {
                for (final ItemStack stack : more) {
                    this.add(stack);
                }
            }
        }

        /** 页签正在拼的那个<b>可变</b>列表（1.20.1 同名方法）。 */
        public List<ItemStack> getDisplayStacks() {
            return this.stacks;
        }

        public boolean isEmpty() {
            return this.stacks.isEmpty();
        }

        public int size() {
            return this.stacks.size();
        }
    }
}
