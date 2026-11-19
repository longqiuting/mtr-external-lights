// PSD-LCD 挂板的注册：1 个方块 + 1 个物品 + 1 个方块实体类型 + 创造页签条目。
package dev.js.mtrheadlight.xiamen.pids;

import dev.js.mtrheadlight.LteLog;
import dev.js.mtrheadlight.xiamen.XiamenObjects;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

/**
 * 厦门地铁 PSD-LCD 挂板（{@code mtrlte:psd_lcd_xiamen}）的注册入口，两端都要跑。
 *
 * <table border="1">
 *   <caption>注册出来的东西</caption>
 *   <tr><th>东西</th><th>id</th></tr>
 *   <tr><td>方块</td><td>{@code mtrlte:psd_lcd_xiamen}</td></tr>
 *   <tr><td>物品</td><td>{@code mtrlte:psd_lcd_xiamen}</td></tr>
 *   <tr><td>方块实体</td><td>{@code mtrlte:psd_lcd_xiamen}</td></tr>
 * </table>
 *
 * <p>进「厦门风格物件」页签，排在厦门 PIDS 之后（由 {@code LteServer} 的调用顺序决定）。
 * 由 {@code LteServer} 在 {@link PidsXiamenObjects#register()} 之后调用 ——
 * 方块实体、界面、广告表都复用厦门 PIDS 那一套。
 */
public final class PsdLcdObjects {

    /** 模组命名空间。 */
    public static final String NAMESPACE = XiamenObjects.NAMESPACE;

    /** 方块 / 物品 / 方块实体共用的 id。 */
    public static final Identifier ID = new Identifier(NAMESPACE, PsdLcdBlock.ID);

    private static PsdLcdBlock block;
    private static Item item;
    private static BlockEntityType<PsdLcdBlockEntity> entityType;
    private static boolean registered;

    private PsdLcdObjects() {
    }

    public static PsdLcdBlock block() {
        return block;
    }

    public static Item item() {
        return item;
    }

    public static BlockEntityType<PsdLcdBlockEntity> entityType() {
        return entityType;
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        // 一块薄板：挖得动、要工具、不挡光、不遮挡邻居（culling shape 在方块里返回空集）。
        block = Registry.register(Registries.BLOCK, ID, new PsdLcdBlock(AbstractBlock.Settings.create()
                .requiresTool()
                .strength(1.5F)
                .nonOpaque()));
        item = Registry.register(Registries.ITEM, ID, new BlockItem(block, new Item.Settings()));

        // 方块实体类型必须在方块之后：Fabric 的 builder 会把方块记进去。
        entityType = Registry.register(Registries.BLOCK_ENTITY_TYPE, ID,
                FabricBlockEntityTypeBuilder.create(PsdLcdBlockEntity::new, block).build());

        try {
            ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, XiamenObjects.CORE_TAB_ID))
                    .register(entries -> entries.add(item));
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] psd lcd: could not add the creative tab entry", t);
        }

        LteLog.LOGGER.info("[lte] psd lcd: block={} be={}", ID, ID);
    }
}
