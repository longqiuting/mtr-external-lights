package dev.js.mtrheadlight.xiamen;

import dev.js.mtrheadlight.LteLog;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.MapColor;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 厦门风格物件：创造模式页签 {@code mtrlte:core}（「厦门风格物件 / Xiamen Style Objects」）
 * 以及里面的方块。
 *
 * <h2>模型复用 MTR，贴图用我们的</h2>
 * <p>方块模型没有手搓 cuboid，而是在
 * {@code assets/mtrlte/models/block/xiamen_psd_*.json} 里直接
 * {@code "parent": "mtr:block/psd_glass_left_base"} —— 也就是 MTR 自己那套屏蔽门
 * 基础几何（2px 厚、满高、16×16 满 UV 的板）。子模型只覆盖四个面贴图
 * （front/back/bottom/top），几何完全继承 MTR，MTR 改版时形状也不用跟着改。
 *
 * <p>贴图从玩家资源包 {@code 厦门地铁一号线车辆} 的
 * {@code assets/mtr/textures/block/} 拷成本模组自己的
 * {@code assets/mtrlte/textures/block/}，而不是运行时去引用 {@code mtr:} 路径 ——
 * 否则只有开着那个资源包的世界才看得到厦门样式。拷贝来源：
 * <ul>
 *   <li>{@code psd_door_top_1.png} → {@code xiamen_psd_door.png}</li>
 *   <li>{@code psd_door_top_back_1.png} → {@code xiamen_psd_door_back.png}</li>
 *   <li>{@code psd_glass_top_1.png} → {@code xiamen_psd_glass.png}</li>
 *   <li>{@code psd_glass_top_back_1.png} → {@code xiamen_psd_glass_back.png}</li>
 * </ul>
 *
 * <p>注册放在 {@code main} 入口（两端都要有方块），半透明渲染层在
 * {@link XiamenClient}（仅客户端）里挂。
 */
public final class XiamenObjects {

    /** 屏蔽门门扇：{@code mtrlte:xiamen_psd_door}。 */
    public static final Identifier PSD_DOOR_ID = new Identifier("mtrlte", "xiamen_psd_door");

    /** 屏蔽门玻璃板：{@code mtrlte:xiamen_psd_glass}。 */
    public static final Identifier PSD_GLASS_ID = new Identifier("mtrlte", "xiamen_psd_glass");

    /** 页签 id：{@code mtrlte:core} —— 名字由 {@code itemGroup.mtrlte.core} 决定。 */
    public static final Identifier CORE_TAB_ID = new Identifier("mtrlte", "core");

    private static XiamenPsdBlock psdDoor;
    private static XiamenPsdBlock psdGlass;
    private static boolean registered;

    private XiamenObjects() {
    }

    public static XiamenPsdBlock psdDoor() {
        return psdDoor;
    }

    public static XiamenPsdBlock psdGlass() {
        return psdGlass;
    }

    /** 方块 + 方块物品 + 创造页签，{@code main} 入口调一次。 */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        psdDoor = registerBlock(PSD_DOOR_ID);
        psdGlass = registerBlock(PSD_GLASS_ID);

        Registry.register(Registries.ITEM_GROUP, CORE_TAB_ID,
                FabricItemGroup.builder()
                        .displayName(Text.translatable("itemGroup.mtrlte.core"))
                        .icon(() -> new ItemStack(psdDoor))
                        .build());

        ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, CORE_TAB_ID))
                .register(entries -> {
                    entries.add(psdDoor);
                    entries.add(psdGlass);
                });

        LteLog.LOGGER.info("[lte] xiamen objects registered ({} / {})", PSD_DOOR_ID, PSD_GLASS_ID);
    }

    /**
     * 一块薄板：硬度跟玻璃一个量级，声音用玻璃，{@code nonOpaque()} 让它不挡光
     * （否则整格不透光、贴图透明的地方会看起来发黑）。
     */
    private static XiamenPsdBlock registerBlock(Identifier id) {
        final AbstractBlock.Settings settings = AbstractBlock.Settings.create()
                .mapColor(MapColor.LIGHT_GRAY)
                .strength(0.5F, 1.0F)
                .sounds(BlockSoundGroup.GLASS)
                .nonOpaque();
        final XiamenPsdBlock block = new XiamenPsdBlock(settings);
        Registry.register(Registries.BLOCK, id, block);
        Registry.register(Registries.ITEM, id, new BlockItem(block, new Item.Settings()));
        return block;
    }

    /** 页签在 registry 里的那一份（调试用）。 */
    public static ItemGroup tab() {
        return Registries.ITEM_GROUP.get(CORE_TAB_ID);
    }
}
