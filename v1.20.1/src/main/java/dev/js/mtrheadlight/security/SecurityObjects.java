package dev.js.mtrheadlight.security;

import dev.js.mtrheadlight.LteLog;
import dev.js.mtrheadlight.xiamen.XiamenObjects;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

/**
 * 安检类方块（目前只有液体安检机 {@code mtrlte:liquid_scanner}）的注册点。
 *
 * <h2>为什么单开一个 {@code security} 包</h2>
 * 这个方块和厦门屏蔽门没有任何关系 —— 它是「站内安检」的设施，行为（药水检测）与
 * {@code xiamen} 那一族完全不同。方块本体见 {@link LiquidScannerBlock}，
 * 客户端渲染层见 {@link SecurityClient}。
 *
 * <h2>进哪个创造页签</h2>
 * 进 {@code mtrlte:core}（「厦门风格物件 / Xiamen Style Objects」）——
 * 本模组只有两个页签，{@code mtrlte:tools} 是连接器 / 配置界面这类<b>物品</b>，
 * 而 {@code core} 是唯一放<b>方块</b>的页签。页签 id 从 {@link XiamenObjects#CORE_TAB_ID} 取，
 * 不另写一份 {@code new Identifier("mtrlte", "core")}，免得两边写岔。
 *
 * <p>顺序上排在所有屏蔽门之后（页签里最右）：屏蔽门五条线路是一整组，安检机不该插在中间。
 * 注册顺序不敏感 —— {@code modifyEntriesEvent} 是以页签 key 挂监听，页签本身由
 * {@code XiamenObjects.register()} 建出来，两者谁先谁后都行。
 */
public final class SecurityObjects {

    /** 模组命名空间。 */
    public static final String NAMESPACE = "mtrlte";

    /** 方块 / 物品共用的 id：{@code mtrlte:liquid_scanner}。 */
    public static final Identifier LIQUID_SCANNER_ID = new Identifier(NAMESPACE, "liquid_scanner");

    private static LiquidScannerBlock liquidScanner;
    private static Item liquidScannerItem;
    private static BlockEntityType<LiquidScannerBlockEntity> liquidScannerEntityType;
    private static boolean registered;

    private SecurityObjects() {
    }

    public static LiquidScannerBlock liquidScanner() {
        return liquidScanner;
    }

    public static Item liquidScannerItem() {
        return liquidScannerItem;
    }

    /** 方块实体类型 {@code mtrlte:liquid_scanner}（同一个 id、另一个注册表，和 PIDS 的做法一致）。 */
    public static BlockEntityType<LiquidScannerBlockEntity> liquidScannerEntityType() {
        return liquidScannerEntityType;
    }

    /** 方块 + 方块物品 + 创造页签条目，{@code main} 入口（{@code LteServer}）调一次。 */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        liquidScanner = Registry.register(Registries.BLOCK, LIQUID_SCANNER_ID, new LiquidScannerBlock());
        // 物品 id 与方块 id 相同（{@code liquid_scanner}），与原版箱子 / 熔炉的命名习惯一致。
        liquidScannerItem = Registry.register(Registries.ITEM, LIQUID_SCANNER_ID,
                new LiquidScannerItem(liquidScanner, new Item.Settings()));
        // block entity：存「这台机器直接没收哪些液体」+ 灯的结果状态由 blockstate 承载（不需要实体参与）。
        liquidScannerEntityType = Registry.register(Registries.BLOCK_ENTITY_TYPE, LIQUID_SCANNER_ID,
                FabricBlockEntityTypeBuilder.create(LiquidScannerBlockEntity::new, liquidScanner).build());

        // 配置通道（刷子开界面 / 保存名单）。两端都注册：客户端只收，服务端只收发。
        LiquidScannerNet.register();

        try {
            ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP,
                            XiamenObjects.CORE_TAB_ID))
                    .register(entries -> entries.add(liquidScannerItem));
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] liquid scanner could not be added to the core tab", t);
        }

        LteLog.LOGGER.info("[lte] security: {}", LIQUID_SCANNER_ID);
    }
}
