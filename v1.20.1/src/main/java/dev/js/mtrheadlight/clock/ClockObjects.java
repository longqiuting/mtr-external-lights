// 时钟方块的注册点：方块 + 方块物品 + 方块实体类型 + 创造页签条目。
package dev.js.mtrheadlight.clock;

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
 * 时钟方块（{@code mtrlte:clock}）的注册入口，两端都要跑。
 *
 * <table border="1">
 *   <caption>id 一览</caption>
 *   <tr><th>id</th><th>类型</th><th>说明</th></tr>
 *   <tr><td>{@code mtrlte:clock}</td><td>方块 + 方块物品</td>
 *       <td>钟面由 {@link ClockRenderer} 用代码画，blockstate 全部指向 {@code mtrlte:block/empty}</td></tr>
 *   <tr><td>{@code mtrlte:clock}</td><td>方块实体类型</td>
 *       <td>同一个 id、另一个注册表（与液体安检机 / PIDS 的做法一致）；ticker 由
 *           {@link ClockBlock#getTicker} 交出去</td></tr>
 * </table>
 *
 * <p>进 {@code mtrlte:core}（「厦门风格物件」）页签 —— 本模组只有两个页签，方块一律在 core，
 * 从 {@link XiamenObjects#CORE_TAB_ID} 取 key，不另写一份 id。
 *
 * <p>注册风格与其它方块模块一致：{@code LteServer#blockModule} 包着调用，
 * 页签条目自己再 try/catch 一次 —— 页签没建出来只影响「创造栏里找不到」，方块照常工作。
 */
public final class ClockObjects {

    /** 模组命名空间。 */
    public static final String NAMESPACE = "mtrlte";

    /** 方块 / 物品 / 方块实体共用的 id：{@code mtrlte:clock}。 */
    public static final Identifier CLOCK_ID = new Identifier(NAMESPACE, "clock");

    private static ClockBlock clock;
    private static Item clockItem;
    private static BlockEntityType<ClockBlockEntity> entityType;
    private static boolean registered;

    private ClockObjects() {
    }

    public static ClockBlock clock() {
        return clock;
    }

    public static Item clockItem() {
        return clockItem;
    }

    /** 方块实体类型 {@code mtrlte:clock}。 */
    public static BlockEntityType<ClockBlockEntity> entityType() {
        return entityType;
    }

    /** 方块 + 方块物品 + 方块实体类型 + 创造页签条目。 */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        clock = Registry.register(Registries.BLOCK, CLOCK_ID, new ClockBlock());
        clockItem = Registry.register(Registries.ITEM, CLOCK_ID, new BlockItem(clock, new Item.Settings()));
        entityType = Registry.register(Registries.BLOCK_ENTITY_TYPE, CLOCK_ID,
                FabricBlockEntityTypeBuilder.create(ClockBlockEntity::new, clock).build());

        // 配置通道（刷子开界面 / 保存 / 试听）。两端都注册：客户端只收，服务端只收发。
        ClockNet.register();

        try {
            ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP,
                            XiamenObjects.CORE_TAB_ID))
                    .register(entries -> entries.add(clockItem));
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] clock could not be added to the core tab", t);
        }

        LteLog.LOGGER.info("[lte] clock: {}", CLOCK_ID);
    }
}
