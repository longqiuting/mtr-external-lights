// 挂墙发车倒数器的注册：一个方块 + 一个物品 + 一个方块实体类型 + 创造页签条目。
package dev.js.mtrheadlight.xiamen.dep;

import dev.js.mtrheadlight.LteLog;
import dev.js.mtrheadlight.xiamen.XiamenObjects;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

/**
 * 挂墙发车倒数器 {@code mtrlte:dep_on_wall} 的注册入口（两端都要跑）。
 *
 * <p>方块 / 物品 / 方块实体类型用<b>同一个</b> id（{@link #ID}）—— 本模组的厦门物件都是这个规矩。
 * 由 {@code LteServer} 在 {@code PidsXiamenObjects.register()} 之后调用，物品进
 * 「厦门风格物件」页签（{@link XiamenObjects#CORE_TAB_ID}）。
 */
public final class DepOnWallObjects {

    /** 模组命名空间（与 {@link XiamenObjects#NAMESPACE} 一致）。 */
    public static final String NAMESPACE = XiamenObjects.NAMESPACE;

    /** 方块 / 物品 / 方块实体共用的 id（不带命名空间）：{@code mtrlte:dep_on_wall}。 */
    public static final String ID = "dep_on_wall";

    public static final Identifier IDENTIFIER = new Identifier(NAMESPACE, ID);

    private static DepOnWallBlock block;
    private static Item item;
    private static BlockEntityType<DepOnWallBlockEntity> entityType;
    private static boolean registered;

    private DepOnWallObjects() {
    }

    public static DepOnWallBlock block() {
        return block;
    }

    public static Item item() {
        return item;
    }

    public static BlockEntityType<DepOnWallBlockEntity> entityType() {
        return entityType;
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        block = Registry.register(Registries.BLOCK, IDENTIFIER, new DepOnWallBlock());
        item = Registry.register(Registries.ITEM, IDENTIFIER, new BlockItem(block, new Item.Settings()));

        // 方块实体类型必须在方块之后：Fabric 的 builder 会把方块记进去（校验方块的 BE 类型）。
        entityType = Registry.register(Registries.BLOCK_ENTITY_TYPE, IDENTIFIER,
                FabricBlockEntityTypeBuilder.create(DepOnWallBlockEntity::new, block).build());

        // 进「厦门风格物件」页签（和 PIDS 一样：页签在 XiamenObjects 里建好，这里追加条目）。
        try {
            ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, XiamenObjects.CORE_TAB_ID))
                    .register(entries -> entries.add(item));
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] dep on wall: could not add the creative tab entry", t);
        }

        LteLog.LOGGER.info("[lte] dep on wall: block={} be={}", IDENTIFIER, IDENTIFIER);
    }
}
