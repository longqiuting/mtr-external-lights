// 厦门售票机 / 余额查询机的注册入口（两端都要跑）。
package dev.js.mtrheadlight.xiamen.machine;

import dev.js.mtrheadlight.LteLog;
import dev.js.mtrheadlight.xiamen.XiamenObjects;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;
import net.minecraft.block.Material;

/**
 * 两台机器的注册：{@code mtrlte:ticket_machine_xiamen}（售票机）与
 * {@code mtrlte:balance_machine_xiamen}（余额查询机），各一个方块 + 一个物品。
 *
 * <p>两台都是<b>一个 id 两格高</b>（{@code half=lower|upper}），上段没有物品 —— 见
 * {@link XiamenMachineBlock}。方块与物品用同一个 id，这是本模组厦门物件的一贯做法。
 *
 * <p>几何来源分两种：售票机仍是原版 blockstate 模型（`block/ticket_machine_xiamen_{bottom,top}`，
 * 它的角度都是 ±22.5°，原版能表达）；余额查询机改由 {@link XiamenBalanceMachineRenderer}
 * 运行时画 {@code rawmodels/balance_machine_{bottom,top}.json}（素材里屏幕是 x:-12.5 的斜面），
 * 所以它多一个方块实体类型 {@link #BALANCE_ENTITY_ID}。
 *
 * <h2>属性</h2>
 * <ul>
 *   <li>{@code requiresTool().strength(2.0F)} + 镐子可挖（进 {@code mineable/pickaxe}）；</li>
 *   <li>{@code sounds(BlockSoundGroup.METAL)}：地铁站里的机器都是铁壳；</li>
 *   <li>{@code nonOpaque()}：模型不是满方块（正面有斜出去的屏幕 / 操作台），
 *       不这样邻居朝我们的面会被按满方块剔除，缝隙里能看穿邻居；</li>
 *   <li>发微弱<b>方块光</b>（{@code luminance}）：上段（屏幕那半）8 级、下段 6 级 ——
 *       夜里机器自己照亮周围一圈，但不像灯那样刺眼（见 {@link XiamenMachineBlock#screenGlow}）；
 *       售票机切到节能关机（{@code ui=eco}）时整机断电，两段都不发光。</li>
 * </ul>
 *
 * <p>由 {@code LteServer} 在 {@code StationSignObjects.register()} 之后调用，两个物品进
 * 「厦门风格物件」页签（{@link XiamenObjects#CORE_TAB_ID}）。
 */
public final class XiamenMachines {

    /** 模组命名空间（与 {@link XiamenObjects#NAMESPACE} 一致）。 */
    public static final String NAMESPACE = XiamenObjects.NAMESPACE;

    /** 售票机（两格高）。 */
    public static final Identifier TICKET_ID = new Identifier(NAMESPACE, "ticket_machine_xiamen");

    /** 余额查询机（两格高）。 */
    public static final Identifier BALANCE_ID = new Identifier(NAMESPACE, "balance_machine_xiamen");

    /** 余额查询机的方块实体类型（上下两段共用，渲染器按 HALF 选模型）。 */
    public static final Identifier BALANCE_ENTITY_ID = new Identifier(NAMESPACE, "balance_machine");

    private static XiamenTicketMachineBlock ticket;
    private static XiamenBalanceMachineBlock balance;
    private static BlockEntityType<XiamenBalanceMachineBlockEntity> balanceEntityType;
    private static Item ticketItem;
    private static Item balanceItem;
    private static boolean registered;

    private XiamenMachines() {
    }

    public static XiamenTicketMachineBlock ticket() {
        return ticket;
    }

    public static XiamenBalanceMachineBlock balance() {
        return balance;
    }

    /** 余额查询机的方块实体类型（{@link XiamenBalanceMachineBlockEntity} 构造器要用）。 */
    public static BlockEntityType<XiamenBalanceMachineBlockEntity> balanceEntityType() {
        return balanceEntityType;
    }

    /** 方块 + 物品 + 创造页签条目。 */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        ticket = Registry.register(Registries.BLOCK, TICKET_ID, new XiamenTicketMachineBlock(settings()));
        ticketItem = Registry.register(Registries.ITEM, TICKET_ID, new BlockItem(ticket, new Item.Settings()));

        balance = Registry.register(Registries.BLOCK, BALANCE_ID, new XiamenBalanceMachineBlock(settings()));
        balanceItem = Registry.register(Registries.ITEM, BALANCE_ID, new BlockItem(balance, new Item.Settings()));

        // 余额查询机的方块实体类型（服务端也要注册；上下两段是同一个类型的两个实例）。
        balanceEntityType = Registry.register(Registries.BLOCK_ENTITY_TYPE, BALANCE_ENTITY_ID,
                FabricBlockEntityTypeBuilder.create(XiamenBalanceMachineBlockEntity::new, balance).build());

        try {
            ItemGroupEvents.modifyEntriesEvent(XiamenObjects.CORE_TAB_ID)
                    .register(entries -> {
                        entries.add(ticketItem);
                        entries.add(balanceItem);
                    });
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] ticket machines: could not add the creative tab entries", t);
        }

        LteLog.LOGGER.info("[lte] ticket machines: {} (default ui={}) / {}",
                TICKET_ID, XiamenTicketMachineBlock.defaultUi(), BALANCE_ID);
    }

    /**
     * 每台机器一份设置 —— 不能共用同一个 {@code Settings} 实例：{@code AbstractBlock} 构造时
     * 会把战利品表 id 之类的字段写进这个对象，两个方块共用会被后建的那个覆盖。
     */
    private static AbstractBlock.Settings settings() {
        return AbstractBlock.Settings.of(Material.STONE)
                .mapColor(MapColor.IRON_GRAY)
                .requiresTool()
                .strength(2.0F)
                .sounds(BlockSoundGroup.METAL)
                .nonOpaque();
    }
}
