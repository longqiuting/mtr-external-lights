// 挂顶 DEP 挂杆的注册：一个方块 + 一个物品 + 一个方块实体类型 + 创造页签条目（DEP 那一组）。
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
 * 挂顶 DEP 挂杆 {@code mtrlte:dep_pole} 的注册入口（两端都要跑）。
 *
 * <p>方块 / 物品 / 方块实体类型用<b>同一个</b> id —— 和 {@code dep_on_wall} / {@code dep_on_roof} 一个规矩。
 * 由 {@code LteServer} 在 {@link DepOnRoofObjects#register()} 之后调用（也就是 DEP 那三件连着排），
 * 物品进「厦门风格物件」页签（{@link XiamenObjects#CORE_TAB_ID}）。
 *
 * <p>渲染资源：几何 {@code assets/mtrlte/rawmodels/dep_pole.json}、贴图
 * {@code assets/mtrlte/textures/eyecandy/dep_on_wall.png}（64×64），全部走运行时的
 * {@link DepPoleRenderer}（{@link DepPoleBlock} 的 blockstate 是 {@code mtrlte:block/empty}）。
 */
public final class DepPoleObjects {

    /** 模组命名空间（与 {@link XiamenObjects#NAMESPACE} 一致）。 */
    public static final String NAMESPACE = XiamenObjects.NAMESPACE;

    /** 方块 / 物品 / 方块实体共用的 id（不带命名空间）：{@code mtrlte:dep_pole}。 */
    public static final String ID = "dep_pole";

    /** rawmodel 的名字（{@code assets/mtrlte/rawmodels/<MODEL>.json}）。 */
    public static final String MODEL = "dep_pole";

    public static final Identifier IDENTIFIER = new Identifier(NAMESPACE, ID);

    private static DepPoleBlock block;
    private static Item item;
    private static BlockEntityType<DepPoleBlockEntity> entityType;
    private static boolean registered;

    private DepPoleObjects() {
    }

    public static DepPoleBlock block() {
        return block;
    }

    public static Item item() {
        return item;
    }

    public static BlockEntityType<DepPoleBlockEntity> entityType() {
        return entityType;
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        block = Registry.register(Registries.BLOCK, IDENTIFIER, new DepPoleBlock());
        item = Registry.register(Registries.ITEM, IDENTIFIER, new BlockItem(block, new Item.Settings()));

        // 方块实体类型必须在方块之后：Fabric 的 builder 会把方块记进去（校验方块的 BE 类型）。
        entityType = Registry.register(Registries.BLOCK_ENTITY_TYPE, IDENTIFIER,
                FabricBlockEntityTypeBuilder.create(DepPoleBlockEntity::new, block).build());

        try {
            ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, XiamenObjects.CORE_TAB_ID))
                    .register(entries -> entries.add(item));
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] dep pole: could not add the creative tab entry", t);
        }

        LteLog.LOGGER.info("[lte] dep pole: block={} be={} model={}", IDENTIFIER, IDENTIFIER,
                "rawmodels/" + MODEL + ".json");
    }
}
