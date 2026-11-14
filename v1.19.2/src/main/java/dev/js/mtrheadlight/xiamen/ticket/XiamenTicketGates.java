// 六个闸机方块（厦门地铁 / 厦门BRT × 进站 / 出站 的本体，加两款共用侧壳）的注册点：方块 + 物品 + 方块实体类型 + 创造页签。
package dev.js.mtrheadlight.xiamen.ticket;

import net.minecraft.block.Material;
import java.util.List;

import dev.js.mtrheadlight.LteLog;
import dev.js.mtrheadlight.xiamen.XiamenObjects;
import dev.js.mtrheadlight.compat.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.util.Identifier;

/**
 * 闸机的注册入口：<b>4 台闸机本体 + 2 款侧壳，共 6 个方块</b>，全部共用一个方块实体类型。
 *
 * <table border="1">
 *   <caption>四台闸机本体</caption>
 *   <tr><th>本体 id</th><th>原始模型（rawmodels/*）</th><th>配套侧壳</th><th>{@code isEntrance}</th></tr>
 *   <tr><td>{@code mtrlte:ticket_gate_xiamen_in}</td>
 *       <td>{@code ticket_barrier_closed / _open}</td><td>{@code ticket_gate_xiamen_side}</td><td>{@code true}</td></tr>
 *   <tr><td>{@code mtrlte:ticket_gate_xiamen_out}</td><td>同上（<b>进出共用</b>）</td>
 *       <td>{@code ticket_gate_xiamen_side}</td><td>{@code false}</td></tr>
 *   <tr><td>{@code mtrlte:ticket_gate_brt_in}</td>
 *       <td>{@code brt_barrier_closed / _open}</td><td>{@code ticket_gate_brt_side}</td><td>{@code true}</td></tr>
 *   <tr><td>{@code mtrlte:ticket_gate_brt_out}</td><td>同上（<b>进出共用</b>）</td>
 *       <td>{@code ticket_gate_brt_side}</td><td>{@code false}</td></tr>
 * </table>
 *
 * <h2>为什么进出共用一套模型</h2>
 * <p>地面上看，进站闸机和出站闸机长得一模一样（厦门地铁的实际闸机就是同一款），差别只在判定：
 * 同一张 {@code OPEN} 状态图，两端靠 {@code isEntrance} 决定走 {@code onEnter} 还是 {@code onExit}。
 *
 * <h2>侧壳为什么只有两款（不再分进 / 出）</h2>
 * <p>侧壳是一只<b>纯柜体</b>：没有 {@code OPEN}、没有判定、也不参与开合，进站闸机旁边和出站闸机旁边
 * 摆的是同一只柜子。所以侧壳按「素材」分两款就够 —— {@link Side}：地铁一款
 * （{@code rawmodels/ticket_barrier_side.json}）、BRT 一款（{@code brt_barrier_side.json}），
 * 四台本体共用。<b>侧壳也是独立方块 + 独立物品</b>：自己摆在通道另一侧，不跟本体联动
 * （一个方块塞不下两个柜体 —— 碰撞 / 框选 / 破坏都只能按格算）。
 *
 * <h2>几何从哪来</h2>
 * <p>直接读 Blockbench 导出的原始 JSON（{@code assets/mtrlte/rawmodels/*.json}），
 * 由 {@link XiamenTicketGateRenderer} 运行时逐元素旋转绘制 —— 素材里的
 * {@code -11.5 / -3.25 / 3.25 / 44} 这类任意角和 BRT 的三轴旋转都能原样表达。
 * 方块本身只剩一份空模型（{@code mtrlte:block/empty}），blockstate 只负责状态与朝向。
 * 物品模型则是真正的原版 block model（{@code models/block/ticket_gate_*_item.json}，
 * 由 {@code tools/gen-ticket-gate-item-models.js} 生成）—— 方块不存几何，物品必须自带一份。
 *
 * <p>由 {@code LteServer} 在厦门售票机 / 余额查询机之后调用；六个物品进「厦门风格物件」页签
 * （{@link XiamenObjects#CORE_TAB_ID}）。
 */
public final class XiamenTicketGates {

    /** 一款侧壳：一只柜体，四台本体按素材（地铁 / BRT）共用。 */
    public static final class Side {

        /** 方块 / 物品共用的 registry id。 */
        public final Identifier id;
        /** 侧壳原始模型名：{@code ticket_barrier_side} / {@code brt_barrier_side}。 */
        public final String rawModel;

        private XiamenTicketGateSide block;
        private Item item;

        private Side(final String path, final String rawModel) {
            this.id = new Identifier(XiamenObjects.NAMESPACE, path);
            this.rawModel = rawModel;
        }

        /** 注册好的侧壳方块（{@link XiamenTicketGates#register()} 之后非 null）。 */
        public XiamenTicketGateSide block() {
            return this.block;
        }

        /** 侧壳物品（侧壳是独立方块，能单独拿、单独摆）。 */
        public Item item() {
            return this.item;
        }
    }

    /** 厦门地铁的侧壳。 */
    public static final Side XIAMEN_SIDE = new Side("ticket_gate_xiamen_side", "ticket_barrier_side");

    /** 厦门BRT 的侧壳。 */
    public static final Side BRT_SIDE = new Side("ticket_gate_brt_side", "brt_barrier_side");

    /** 两款侧壳（注册 + 客户端的遍历顺序）。 */
    private static final List<Side> SIDES = List.of(XIAMEN_SIDE, BRT_SIDE);

    /** 一台闸机本体：id + 进 / 出 + 用哪一套素材 + 用哪款侧壳。 */
    public static final class Gate {

        /** 本体方块 / 物品共用的 registry id。 */
        public final Identifier id;
        /** {@code true} = 进站（{@code BlockTicketBarrier(true)}）。 */
        public final boolean entrance;
        /** 物品模型前缀：{@code ticket_gate_xiamen} / {@code ticket_gate_brt}（item 模型名由它拼）。 */
        public final String model;
        /** 本体原始模型前缀：{@code ticket_barrier} / {@code brt_barrier}（渲染器再拼 {@code _open / _closed}）。 */
        public final String rawModel;
        /** 配套侧壳（按素材共用，与进 / 出无关）。 */
        public final Side side;

        private XiamenTicketGate block;
        private Item item;

        private Gate(final String path, final boolean entrance, final String model, final String rawModel,
                     final Side side) {
            this.id = new Identifier(XiamenObjects.NAMESPACE, path);
            this.entrance = entrance;
            this.model = model;
            this.rawModel = rawModel;
            this.side = side;
        }

        /** 注册好的本体方块（{@link XiamenTicketGates#register()} 之后非 null）。 */
        public XiamenTicketGate block() {
            return this.block;
        }

        /** 方块物品。 */
        public Item item() {
            return this.item;
        }
    }

    /** 页签里的顺序：地铁进 → 地铁出 → BRT 进 → BRT 出（侧壳另按材料排在后头）。 */
    private static final List<Gate> GATES = List.of(
            new Gate("ticket_gate_xiamen_in", true, "ticket_gate_xiamen", "ticket_barrier", XIAMEN_SIDE),
            new Gate("ticket_gate_xiamen_out", false, "ticket_gate_xiamen", "ticket_barrier", XIAMEN_SIDE),
            new Gate("ticket_gate_brt_in", true, "ticket_gate_brt", "brt_barrier", BRT_SIDE),
            new Gate("ticket_gate_brt_out", false, "ticket_gate_brt", "brt_barrier", BRT_SIDE));

    /** 六个闸机方块共用的方块实体类型（渲染器按方块实例选模型）。 */
    private static final Identifier ENTITY_ID = new Identifier(XiamenObjects.NAMESPACE, "ticket_gate");

    private static BlockEntityType<XiamenTicketGateBlockEntity> entityType;
    private static boolean registered;

    private XiamenTicketGates() {
    }

    /** 四台闸机本体（客户端挂渲染器时按它遍历）。 */
    public static List<Gate> gates() {
        return GATES;
    }

    /** 两款侧壳。 */
    public static List<Side> sides() {
        return SIDES;
    }

    /** 闸机的方块实体类型（{@link XiamenTicketGateBlockEntity} 构造器要用）。 */
    public static BlockEntityType<XiamenTicketGateBlockEntity> entityType() {
        return entityType;
    }

    /** 音效 + 6 个方块 + 6 个物品 + 方块实体类型 + 创造页签条目，{@code main} 入口（{@code LteServer}）调一次。 */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        // 音效是 registry 条目，服务端也要有（passThrough 在服务端 playSound）。
        XiamenTicketGateSounds.register();

        final Block[] blocks = new Block[GATES.size() + SIDES.size()];
        int cursor = 0;
        for (final Gate gate : GATES) {
            // 方块实例自己记着进 / 出与素材名，几何由 BER 画，所以一款方块只需要一个类。
            gate.block = Registry.register(Registry.BLOCK, gate.id,
                    new XiamenTicketGate(gate.entrance, gate.rawModel));
            blocks[cursor++] = gate.block;
            // 方块与物品同一个 id（本模组厦门物件的一贯做法）。
            gate.item = Registry.register(Registry.ITEM, gate.id, new BlockItem(gate.block, new Item.Settings()));
        }
        for (final Side side : SIDES) {
            // 侧壳：纯装饰柜体。设置与本体对齐（需要工具、非满方块不让邻居面被剔除）。
            side.block = Registry.register(Registry.BLOCK, side.id,
                    new XiamenTicketGateSide(Block.Settings.of(Material.METAL).strength(2.0F).requiresTool().nonOpaque(),
                            side.rawModel));
            blocks[cursor++] = side.block;
            side.item = Registry.register(Registry.ITEM, side.id, new BlockItem(side.block, new Item.Settings()));
        }

        // 方块实体类型：六个方块共用一个（服务端也要注册 —— 方块实体的创建/保存走的是它）。
        entityType = Registry.register(Registry.BLOCK_ENTITY_TYPE, ENTITY_ID,
                FabricBlockEntityTypeBuilder.create(XiamenTicketGateBlockEntity::new, blocks).build());

        try {
            ItemGroupEvents.modifyEntriesEvent(XiamenObjects.CORE_TAB_ID)
                    .register(entries -> {
                        for (final Gate gate : GATES) {
                            entries.add(gate.item);
                        }
                        for (final Side side : SIDES) {
                            entries.add(side.item);
                        }
                    });
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] ticket gates could not be added to the core tab", t);
        }

        for (final Gate gate : GATES) {
            LteLog.LOGGER.info("[lte] ticket gate {} (entrance={}, rawmodel={}_*, side={})",
                    gate.id, gate.entrance, gate.rawModel, gate.side.id);
        }
        for (final Side side : SIDES) {
            LteLog.LOGGER.info("[lte] ticket gate side shell {} (rawmodel={})", side.id, side.rawModel);
        }
    }
}
