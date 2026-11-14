package dev.js.mtrheadlight.compat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import dev.js.mtrheadlight.LteLog;
import net.minecraft.item.ItemConvertible;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;

/**
 * 1.19.2 上的 {@code net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents} 替身。
 *
 * <h2>1.19.2 的真实形态（javap 自 {@code fabric-item-groups-v0-0.3.16+91896a4960}）</h2>
 * <pre>
 * FabricItemGroupBuilder.create(Identifier)
 *     .icon(Supplier&lt;ItemStack&gt;)
 *     .appendItems(Consumer&lt;List&lt;ItemStack&gt;&gt;)                       // 没有 displayItems，没有 displayContext
 *     .appendItems(BiConsumer&lt;List&lt;ItemStack&gt;, ItemGroup&gt;)
 *     .build() -&gt; ItemGroup
 * </pre>
 * {@code build()} 造的是一个匿名 {@code ItemGroup}，重写了
 * {@code appendStacks(DefaultedList<ItemStack>)}：
 * <pre>
 * public void appendStacks(DefaultedList&lt;ItemStack&gt; stacks) {
 *     if (stacksForDisplay != null) { stacksForDisplay.accept(stacks, this); return; }
 *     super.appendStacks(stacks);          // 否则退回「遍历 Registry.ITEM，看 item.getGroup()」
 * }
 * </pre>
 * 而 {@code CreativeInventoryScreen#setSelectedTab(group)} 真的会调
 * {@code group.appendStacks(itemList)} —— <b>所以 1.19.2 的「填页签」= 一次
 * {@code appendItems} 回调，参数就是屏幕自己的那个 {@code itemList}</b>
 * （{@code CreativeScreenHandler#itemList}，<b>同一个实例</b>在切页签时被
 * {@code clear()} 后复用；没有 1.19.3+ 的 {@code DisplayContext} / {@code displayItems}）。
 *
 * <h2>为什么上一版页签全空</h2>
 * <p>上一版拿 {@code WeakHashMap<List<ItemStack>, Boolean> FILLED} 当「这个列表已经填过」的标记。
 * 但 1.19.2 的创造栏<b>只有一个列表实例</b>：第一次填（任何一个 mtrlte 页签）就把它标记成
 * 「已填」，之后每一次切页签（包括再切回来）都被这层守卫直接 return —
 * 于是四个页签全空。列表实例根本不是「一次填充」的身份，不能当缓存键。
 *
 * <h2>现在的结构</h2>
 * <ol>
 *   <li>{@link #modifyEntriesEvent(Identifier)} 只登记回调（顺序 = 注册顺序）；</li>
 *   <li>页签真正要被画出来时（{@code appendItems} → {@link #appendTo}），把该页签的回调
 *       <b>按注册顺序跑一遍</b>，结果落进一个 <b>per-tab 的静态列表</b>（{@link #CONTENTS}）——
 *       这个列表也是 {@link Entries#getDisplayStacks()} 返回的东西，所以
 *       {@code pinToolsTabOrder()} / {@code pinCoreTabOrder()} 那种「摘出来再插回 0」的重排
 *       能在同一趟里直接改它，排在所有 {@code add} 回调之后执行；</li>
 *   <li>最后把这批条目一次性 add 到创造栏的列表上。</li>
 * </ol>
 * <p>静态列表只拼一次（之后每次开创造栏只是把它复制过去），所以不会重复、也不依赖列表实例；
 * 万一有模块在第一次填充之后才登记回调，{@link #modifyEntriesEvent} 会把缓存作废、下次重拼。
 */
public final class ItemGroupEvents {

    /** 页签 id → 往这个页签加条目的回调（顺序 = 注册顺序）。 */
    private static final Map<Identifier, List<Consumer<Entries>>> LISTENERS = new LinkedHashMap<>();

    /** 页签 id → 已经拼好的条目（静态列表；{@code null} 不存在 = 需要重拼）。 */
    private static final Map<Identifier, List<ItemStack>> CONTENTS = new LinkedHashMap<>();

    /** 正在拼的页签（拼的过程中又有回调登记时，不能把正在用的列表作废）。 */
    private static final Set<Identifier> BUILDING = new HashSet<>();

    private ItemGroupEvents() {
    }

    /**
     * 1.20.1 的 {@code ItemGroupEvents.modifyEntriesEvent(RegistryKey)}；1.19.2 没有页签注册表，
     * 所以直接收页签的 {@link Identifier}。
     */
    public static EntryModifier modifyEntriesEvent(final Identifier tabId) {
        return new EntryModifier(tabId);
    }

    /**
     * 由 {@link ItemGroupTabs#create} 的 {@code appendItems} 回调：
     * 把这个页签的静态条目列表整批 add 到创造栏正在拼的列表上。
     *
     * <p>1.19.2 每次切页签都会 {@code itemList.clear()} 再调这里，所以正常情况下列表是空的；
     * 万一同一批条目已经在里面（别的路径重复调了一次），按条目去重，避免页签里出现两份。
     */
    public static void appendTo(final Identifier tabId, final List<ItemStack> target) {
        if (tabId == null || target == null) {
            return;
        }
        for (final ItemStack stack : contentOf(tabId)) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            boolean duplicate = false;
            for (final ItemStack existing : target) {
                if (existing != null && ItemStack.areEqual(existing, stack)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                target.add(stack.copy());
            }
        }
    }

    /** 该页签已经登记了多少条「加条目」回调（调试用）。 */
    public static int listenerCount(final Identifier tabId) {
        final List<Consumer<Entries>> listeners = LISTENERS.get(tabId);
        return listeners == null ? 0 : listeners.size();
    }

    /** 该页签的静态条目列表（已拼好 / 没拼过就先拼；只读用途请自己 copy）。 */
    public static List<ItemStack> contentOf(final Identifier tabId) {
        if (tabId == null) {
            return List.of();
        }
        final List<ItemStack> cached = CONTENTS.get(tabId);
        if (cached != null) {
            return cached;
        }
        final List<ItemStack> built = new ArrayList<>();
        CONTENTS.put(tabId, built);
        final List<Consumer<Entries>> listeners = LISTENERS.get(tabId);
        if (listeners == null || listeners.isEmpty()) {
            return built;
        }
        BUILDING.add(tabId);
        final int registered = listeners.size();
        try {
            final Entries entries = new Entries(built);
            // 监听里可能再登记新的监听，所以按 size 动态循环。
            for (int i = 0; i < listeners.size(); i++) {
                try {
                    listeners.get(i).accept(entries);
                } catch (final Throwable t) {
                    // 页签内容不该把创造栏拖崩：某一条失败就跳过。
                    LteLog.LOGGER.warn("[lte] tab {}: entry callback #{} failed", tabId, Integer.valueOf(i), t);
                }
            }
        } finally {
            BUILDING.remove(tabId);
        }
        if (listeners.size() != registered) {
            // 拼的过程中又有回调进来（极端情况）：作废这一次的缓存，下次填时重拼。
            CONTENTS.remove(tabId);
        }
        return built;
    }

    /** {@code modifyEntriesEvent(..)} 的返回值：就是 1.20.1 的 {@code ItemGroupEvents.EntryModifier}。 */
    public static final class EntryModifier {
        private final Identifier tabId;

        EntryModifier(final Identifier tabId) {
            this.tabId = tabId;
        }

        public EntryModifier register(final Consumer<Entries> listener) {
            if (this.tabId != null && listener != null) {
                LISTENERS.computeIfAbsent(this.tabId, k -> new ArrayList<>()).add(listener);
                // 已经有拼好的内容就把缓存作废：下次填充时按新的注册顺序重拼。
                if (!BUILDING.contains(this.tabId)) {
                    CONTENTS.remove(this.tabId);
                }
            }
            return this;
        }
    }

    /**
     * 1.20.1 的 {@code ItemGroup.Entries} 里本工程用到的那一小块：
     * {@code add(..)} 几种形态 + {@code getDisplayStacks()}。
     *
     * <p>{@link #getDisplayStacks()} 返回的是该页签的<b>静态条目列表</b>本身（可变），
     * 所以 pin-order 那种「摘出来再插回 0」的重排能直接改到最终结果。
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

        /** 该页签正在拼的那个<b>可变</b>列表（1.20.1 同名方法）。 */
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
