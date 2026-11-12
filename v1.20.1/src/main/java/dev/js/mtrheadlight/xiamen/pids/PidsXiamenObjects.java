// 厦门 PIDS 的注册：两块方块 + 两个物品 + 一个方块实体类型 + 创造页签条目。
package dev.js.mtrheadlight.xiamen.pids;

import dev.js.mtrheadlight.LteLog;
import dev.js.mtrheadlight.xiamen.XiamenObjects;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

/**
 * 厦门 PIDS 两个方块的注册入口。
 *
 * <table border="1">
 *   <caption>注册出来的东西</caption>
 *   <tr><th>kind</th><th>方块 id</th><th>物品 id</th><th>屏幕贴图</th></tr>
 *   <tr><td>METRO</td><td>{@code mtrlte:pids_xiamen_metro}</td>
 *       <td>{@code mtrlte:pids_xiamen_metro}</td>
 *       <td>{@code mtrlte:block/pids_xiamen_metro.png}</td></tr>
 *   <tr><td>BRT</td><td>{@code mtrlte:pids_xiamen_brt}</td>
 *       <td>{@code mtrlte:pids_xiamen_brt}</td>
 *       <td>{@code mtrlte:block/pids_xiamen_brt.png}</td></tr>
 *   <tr><td>挂杆</td><td>{@code mtrlte:pids_xiamen_pole}</td>
 *       <td>{@code mtrlte:pids_xiamen_pole}</td>
 *       <td>—（{@code mtrlte:block/pids_xiamen_pole} 模型，只能放在 PIDS 上）</td></tr>
 * </table>
 *
 * <p>方块实体只有<b>一个</b>类型 {@code mtrlte:pids_xiamen}（两个方块共用）——
 * 就像本模组的屏蔽门那样，{@code BlockEntityType} 本来就是一个类型对多块方块，
 * 拆成两个只会多一个 id，没有任何好处。
 *
 * <p>由 {@code LteServer} 在 {@code XiamenObjects.register()} 之后调用（两端都要跑）。
 */
public final class PidsXiamenObjects {

    /** 模组命名空间（与 {@link XiamenObjects#NAMESPACE} 一致）。 */
    public static final String NAMESPACE = XiamenObjects.NAMESPACE;

    /** 广告 / 配置的资源 id 用的命名空间。 */

    /** 方块实体 id：{@code mtrlte:pids_xiamen}（两个方块共用）。 */
    public static final Identifier ENTITY_ID = new Identifier(NAMESPACE, "pids_xiamen");

    /** 挂杆方块的 id（不带命名空间）：{@code mtrlte:pids_xiamen_pole}。 */
    public static final String POLE_ID = "pids_xiamen_pole";

    private static PidsXiamenBlock metro;
    private static PidsXiamenBlock brt;
    private static PidsXiamenPoleBlock pole;
    private static Item metroItem;
    private static Item brtItem;
    private static Item poleItem;
    private static BlockEntityType<PidsXiamenBlockEntity> entityType;
    private static boolean registered;

    private PidsXiamenObjects() {
    }

    public static PidsXiamenBlock metro() {
        return metro;
    }

    public static PidsXiamenBlock brt() {
        return brt;
    }

    public static PidsXiamenPoleBlock pole() {
        return pole;
    }

    public static Item metroItem() {
        return metroItem;
    }

    public static Item brtItem() {
        return brtItem;
    }

    public static Item poleItem() {
        return poleItem;
    }

    public static BlockEntityType<PidsXiamenBlockEntity> entityType() {
        return entityType;
    }

    /** 方块 → kind（不是本模组的 PIDS → {@code null}）。 */
    public static PidsXiamenBlock.Kind kindOf(final Block block) {
        return block instanceof PidsXiamenBlock pids ? pids.kind() : null;
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        metro = register(PidsXiamenBlock.Kind.METRO);
        metroItem = registerItem(PidsXiamenBlock.Kind.METRO, metro);
        brt = register(PidsXiamenBlock.Kind.BRT);
        brtItem = registerItem(PidsXiamenBlock.Kind.BRT, brt);

        pole = Registry.register(Registries.BLOCK, new Identifier(NAMESPACE, POLE_ID),
                new PidsXiamenPoleBlock(AbstractBlock.Settings.create().strength(1.0F)));
        poleItem = Registry.register(Registries.ITEM, new Identifier(NAMESPACE, POLE_ID),
                new BlockItem(pole, new Item.Settings()));

        entityType = Registry.register(Registries.BLOCK_ENTITY_TYPE, ENTITY_ID,
                FabricBlockEntityTypeBuilder.create(PidsXiamenBlockEntity::new, metro, brt).build());

        // 进「厦门风格物件」页签，排在已有的屏蔽门之后。
        try {
            ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, XiamenObjects.CORE_TAB_ID))
                    .register(entries -> {
                        entries.add(metroItem);
                        entries.add(brtItem);
                        entries.add(poleItem);
                    });
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] xiamen pids: could not add the creative tab entries", t);
        }

        LteLog.LOGGER.info("[lte] xiamen pids: metro={} brt={} pole={} be={}",
                new Identifier(NAMESPACE, PidsXiamenBlock.Kind.METRO.id()),
                new Identifier(NAMESPACE, PidsXiamenBlock.Kind.BRT.id()),
                new Identifier(NAMESPACE, POLE_ID),
                ENTITY_ID);
    }

    private static PidsXiamenBlock register(final PidsXiamenBlock.Kind kind) {
        final Identifier id = new Identifier(NAMESPACE, kind.id());
        return Registry.register(Registries.BLOCK, id, new PidsXiamenBlock(kind));
    }

    private static Item registerItem(final PidsXiamenBlock.Kind kind, final PidsXiamenBlock block) {
        final Identifier id = new Identifier(NAMESPACE, kind.id());
        return Registry.register(Registries.ITEM, id, new BlockItem(block, new Item.Settings()));
    }
}
