package dev.js.mtrheadlight.compat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import net.fabricmc.fabric.api.client.itemgroup.FabricItemGroupBuilder;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;

/**
 * 1.19.2 的创造页签工厂：替 {@code Registries.ITEM_GROUP} + {@code FabricItemGroup.builder()}。
 *
 * <p>1.19.2 没有页签注册表 —— {@code ItemGroup} 只是一个静态数组（{@code ItemGroup.GROUPS}），
 * 页签由 {@code FabricItemGroupBuilder.create(id).icon(..).appendItems(..).build()} 建出来并自己塞进去。
 * 所以这里：
 * <ul>
 *   <li>{@link #create(Identifier, Supplier)} —— 建页签，同时把 {@link ItemGroupEvents} 里登记到这个
 *       id 的监听挂到 {@code appendItems} 上（老线就是在「填创造栏」时回调）；</li>
 *   <li>{@link #get(Identifier)} —— 替 {@code Registries.ITEM_GROUP.get(id)}，返回建好的那一份。</li>
 * </ul>
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
        return group;
    }

    /** 已经建好的页签（{@code Registries.ITEM_GROUP.get(id)} 的对应物）；没建过返回 {@code null}。 */
    public static ItemGroup get(final Identifier id) {
        return BUILT.get(id);
    }
}
