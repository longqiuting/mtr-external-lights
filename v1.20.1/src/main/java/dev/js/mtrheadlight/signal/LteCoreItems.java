package dev.js.mtrheadlight.signal;

import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * LTE 的物品与创造模式页签（{@code main} 入口，两端都注册）。
 *
 * <p>1.20.1 的做法（Fabric Item Group API v1）：
 * <ol>
 *   <li>{@code FabricItemGroup.builder()} 造 {@code ItemGroup}，注册进 {@code Registries.ITEM_GROUP}
 *       —— 页签本身是一个 registry 条目，不再像 1.16 那样靠 mixin 往
 *       {@code ItemGroup.GROUPS} 数组里塞；</li>
 *   <li>物品用 {@code ItemGroupEvents.modifyEntriesEvent(RegistryKey)} 追加，而不是
 *       {@code Item.Settings#group(...)}（1.19.3 起后者已删除）。</li>
 * </ol>
 *
 * <p>页签图标用本模组自己的物品：{@code () -> new ItemStack(SIGNAL_LINKER)}，
 * 是 supplier，注册顺序不敏感。
 */
public final class LteCoreItems {

    /** 物品 id：{@code mtrlte:signal_linker}。 */
    public static final Identifier SIGNAL_LINKER_ID = new Identifier("mtrlte", "signal_linker");

    /** 页签 id：{@code mtrlte:core}。 */
    public static final Identifier CORE_TAB_ID = new Identifier("mtrlte", "core");

    private static SignalLinkItem signalLinker;
    private static boolean registered;

    private LteCoreItems() {
    }

    public static SignalLinkItem signalLinker() {
        return signalLinker;
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        signalLinker = Registry.register(Registries.ITEM, SIGNAL_LINKER_ID,
                new SignalLinkItem(new Item.Settings().maxCount(1)));

        Registry.register(Registries.ITEM_GROUP, CORE_TAB_ID,
                FabricItemGroup.builder()
                        .displayName(Text.translatable("itemGroup.mtrlte.core"))
                        .icon(() -> new ItemStack(signalLinker))
                        .build());

        ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, CORE_TAB_ID))
                .register(entries -> entries.add(signalLinker));
    }

    /** 页签在 registry 里的那一份（调试 / 其它模组想往里加东西时用）。 */
    public static ItemGroup tab() {
        return Registries.ITEM_GROUP.get(CORE_TAB_ID);
    }
}
