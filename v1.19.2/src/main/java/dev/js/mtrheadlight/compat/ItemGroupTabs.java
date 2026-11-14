package dev.js.mtrheadlight.compat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import dev.js.mtrheadlight.LteLog;
import net.fabricmc.fabric.api.client.itemgroup.FabricItemGroupBuilder;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;

/**
 * 1.19.2 的创造页签工厂：替 {@code Registries.ITEM_GROUP} + {@code FabricItemGroup.builder()}。
 *
 * <p>1.19.2 没有页签注册表 —— {@code ItemGroup} 只是一个静态数组（{@code ItemGroup.GROUPS}），
 * 页签由 {@code FabricItemGroupBuilder.create(id).icon(..).appendItems(..).build()} 建出来，
 * {@code build()} 内部先 {@code fabric_expandArray()} 再把新实例放进数组末尾。
 *
 * <p>1.19.2 没有 {@code displayItems(DisplayItemsGenerator)}，等价物是
 * {@code appendItems(Consumer<List<ItemStack>>)}（javap 自
 * {@code fabric-item-groups-v0-0.3.16+91896a4960}）：
 * <pre>
 * public FabricItemGroupBuilder appendItems(Consumer&lt;List&lt;ItemStack&gt;&gt; stacksForDisplay);
 * public FabricItemGroupBuilder appendItems(BiConsumer&lt;List&lt;ItemStack&gt;, ItemGroup&gt; stacksForDisplay);
 * </pre>
 * 回调由 {@code CreativeInventoryScreen#setSelectedTab(group)} → {@code group.appendStacks(itemList)}
 * 触发，参数就是屏幕里那个<b>会被反复复用</b>的列表。所以这里只做一件事：
 * 把 {@link ItemGroupEvents} 里登记的回调拼出来的「静态条目列表」交给它 —— 细节见
 * {@link ItemGroupEvents} 的类注释（上一版页签全空就是把它当成「每次都新建的列表」来缓存导致的）。
 *
 * <p>显示名：1.19.2 的页签名只能靠翻译键 {@code itemGroup.<namespace>.<path>}，1.20.1 侧
 * {@code .displayName(Text.translatable("itemGroup.mtrlte.xxx"))} 用的正是同一批键，所以名字一致，
 * 只是不再需要显式给。
 */
public final class ItemGroupTabs {

    private static final Map<Identifier, ItemGroup> BUILT = new LinkedHashMap<>();

    private ItemGroupTabs() {
    }

    /** 建并「注册」一个创造页签（1.19.2 的注册 = 进 {@code ItemGroup.GROUPS}）。 */
    public static ItemGroup create(final Identifier id, final Supplier<ItemStack> icon) {
        final ItemGroup group = FabricItemGroupBuilder.create(id)
                .icon(icon)
                .appendItems(stacks -> ItemGroupEvents.appendTo(id, stacks))
                .build();
        BUILT.put(id, group);
        LteLog.LOGGER.info("[lte] tab {}: registered (index {})", id, Integer.valueOf(group.getIndex()));
        return group;
    }

    /** 已经建好的页签（{@code Registries.ITEM_GROUP.get(id)} 的对应物）；没建过返回 {@code null}。 */
    public static ItemGroup get(final Identifier id) {
        return BUILT.get(id);
    }

    /** 所有建好的页签 id（顺序 = 创建顺序）。 */
    public static List<Identifier> ids() {
        return new ArrayList<>(BUILT.keySet());
    }

    /**
     * 把每个页签的最终条目数打一行日志：{@code [lte] tab <id>: N items}。
     *
     * <p>1.19.2 的页签内容只在对创造栏填充时才拼，光看启动日志不知道对不对；在
     * {@code onInitialize} 末尾调一次这里，等于把拼列表这一步提前跑掉（拼出来的静态列表会被
     * 缓存下来，填创造栏时直接复用），启动日志里就能确认四页各有几件、有没有整页漏空。
     */
    public static void logContents() {
        for (final Identifier id : ids()) {
            final List<ItemStack> content = ItemGroupEvents.contentOf(id);
            LteLog.LOGGER.info("[lte] tab {}: {} items", id, Integer.valueOf(content.size()));
        }
    }
}
