// 四个闸机方块（厦门地铁 / 厦门BRT × 进站 / 出站）的注册点：方块 + 物品 + 方块实体类型 + 创造页签。
package dev.js.mtrheadlight.xiamen.ticket;

import java.util.List;

import dev.js.mtrheadlight.LteLog;
import dev.js.mtrheadlight.xiamen.XiamenObjects;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
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
 * 闸机的注册入口：<b>4 个方块</b> = 2 款外观 × 进 / 出，共用一个方块实体类型。
 *
 * <table border="1">
 *   <caption>四个方块</caption>
 *   <tr><th>id</th><th>原始模型（rawmodels/*）</th><th>{@code isEntrance}</th></tr>
 *   <tr><td>{@code mtrlte:ticket_gate_xiamen_in}</td>
 *       <td>{@code ticket_barrier_closed} / {@code _open}</td><td>{@code true}</td></tr>
 *   <tr><td>{@code mtrlte:ticket_gate_xiamen_out}</td><td>同上（<b>进出共用</b>）</td><td>{@code false}</td></tr>
 *   <tr><td>{@code mtrlte:ticket_gate_brt_in}</td>
 *       <td>{@code brt_barrier_closed} / {@code _open}</td><td>{@code true}</td></tr>
 *   <tr><td>{@code mtrlte:ticket_gate_brt_out}</td><td>同上（<b>进出共用</b>）</td><td>{@code false}</td></tr>
 * </table>
 *
 * <h2>为什么进出共用一套模型</h2>
 * <p>地面上看，进站闸机和出站闸机长得一模一样（厦门地铁的实际闸机就是同一款），差别只在判定：
 * 同一张 {@code OPEN} 状态图，两端靠 {@code isEntrance} 决定走 {@code onEnter} 还是 {@code onExit}。
 *
 * <h2>几何从哪来</h2>
 * <p>直接读 Blockbench 导出的原始 JSON（{@code assets/mtrlte/rawmodels/*.json}），
 * 由 {@link XiamenTicketGateRenderer} 运行时逐元素旋转绘制 —— 素材里的
 * {@code -11.5 / -3.25 / 3.25 / 44} 这类任意角和 BRT 的三轴旋转都能原样表达。
 * 方块本身只剩一份空模型（{@code mtrlte:block/empty}），blockstate 只负责状态与朝向。
 *
 * <p>由 {@code LteServer} 在厦门售票机 / 余额查询机之后调用；四个物品进「厦门风格物件」页签
 * （{@link XiamenObjects#CORE_TAB_ID}）。
 */
public final class XiamenTicketGates {

    /** 一个闸机方块：id + 进 / 出 + 用哪一套素材。 */
    public static final class Gate {

        /** 方块 / 物品共用的 registry id。 */
        public final Identifier id;
        /** {@code true} = 进站（{@code BlockTicketBarrier(true)}）。 */
        public final boolean entrance;
        /** 物品模型前缀：{@code ticket_gate_xiamen} / {@code ticket_gate_brt}（item 模型名由它拼）。 */
        public final String model;
        /** 原始模型前缀：{@code ticket_barrier} / {@code brt_barrier}（渲染器再拼 {@code _open / _closed}）。 */
        public final String rawModel;

        private XiamenTicketGate block;
        private Item item;

        private Gate(final String path, final boolean entrance, final String model, final String rawModel) {
            this.id = new Identifier(XiamenObjects.NAMESPACE, path);
            this.entrance = entrance;
            this.model = model;
            this.rawModel = rawModel;
        }

        /** 注册好的方块（{@link XiamenTicketGates#register()} 之后非 null）。 */
        public XiamenTicketGate block() {
            return this.block;
        }

        /** 方块物品。 */
        public Item item() {
            return this.item;
        }
    }

    /** 页签里的顺序：地铁进 → 地铁出 → BRT 进 → BRT 出。 */
    private static final List<Gate> GATES = List.of(
            new Gate("ticket_gate_xiamen_in", true, "ticket_gate_xiamen", "ticket_barrier"),
            new Gate("ticket_gate_xiamen_out", false, "ticket_gate_xiamen", "ticket_barrier"),
            new Gate("ticket_gate_brt_in", true, "ticket_gate_brt", "brt_barrier"),
            new Gate("ticket_gate_brt_out", false, "ticket_gate_brt", "brt_barrier"));

    /** 四个闸机共用的方块实体类型（渲染器按方块实例上的 rawModel 选模型）。 */
    private static final Identifier ENTITY_ID = new Identifier(XiamenObjects.NAMESPACE, "ticket_gate");

    private static BlockEntityType<XiamenTicketGateBlockEntity> entityType;
    private static boolean registered;

    private XiamenTicketGates() {
    }

    /** 四个方块（客户端挂渲染器时按它遍历）。 */
    public static List<Gate> gates() {
        return GATES;
    }

    /** 闸机的方块实体类型（{@link XiamenTicketGateBlockEntity} 构造器要用）。 */
    public static BlockEntityType<XiamenTicketGateBlockEntity> entityType() {
        return entityType;
    }

    /** 音效 + 4 个方块 + 4 个物品 + 方块实体类型 + 创造页签条目，{@code main} 入口（{@code LteServer}）调一次。 */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        // 音效是 registry 条目，服务端也要有（passThrough 在服务端 playSound）。
        XiamenTicketGateSounds.register();

        final Block[] blocks = new Block[GATES.size()];
        for (int i = 0; i < GATES.size(); i++) {
            final Gate gate = GATES.get(i);
            // 方块实例自己记着进 / 出与素材名，几何由 BER 画，所以一款方块只需要一个类。
            gate.block = Registry.register(Registries.BLOCK, gate.id,
                    new XiamenTicketGate(gate.entrance, gate.rawModel));
            blocks[i] = gate.block;
            // 方块与物品同一个 id（本模组厦门物件的一贯做法）。
            gate.item = Registry.register(Registries.ITEM, gate.id, new BlockItem(gate.block, new Item.Settings()));
        }

        // 方块实体类型：四个方块共用一个（服务端也要注册 —— 方块实体的创建/保存走的是它）。
        entityType = Registry.register(Registries.BLOCK_ENTITY_TYPE, ENTITY_ID,
                FabricBlockEntityTypeBuilder.create(XiamenTicketGateBlockEntity::new, blocks).build());

        try {
            ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, XiamenObjects.CORE_TAB_ID))
                    .register(entries -> {
                        for (final Gate gate : GATES) {
                            entries.add(gate.item);
                        }
                    });
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] ticket gates could not be added to the core tab", t);
        }

        for (final Gate gate : GATES) {
            LteLog.LOGGER.info("[lte] ticket gate {} (entrance={}, rawmodel={}_*)",
                    gate.id, gate.entrance, gate.rawModel);
        }
    }
}
