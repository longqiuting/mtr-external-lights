// 八个闸机方块（厦门地铁 / 厦门BRT × 进站 / 出站 × 本体 / 侧壳）的注册点：方块 + 物品 + 方块实体类型 + 创造页签。
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
 * 闸机的注册入口：<b>4 台闸机 = 4 个本体 + 4 个侧壳，共 8 个方块</b>，全部共用一个方块实体类型。
 *
 * <table border="1">
 *   <caption>四台闸机（每台两个方块）</caption>
 *   <tr><th>本体 id</th><th>原始模型（rawmodels/*）</th><th>侧壳 id</th><th>{@code isEntrance}</th></tr>
 *   <tr><td>{@code mtrlte:ticket_gate_xiamen_in}</td>
 *       <td>{@code ticket_barrier_closed / _open}</td><td>{@code …_in_side}</td><td>{@code true}</td></tr>
 *   <tr><td>{@code mtrlte:ticket_gate_xiamen_out}</td><td>同上（<b>进出共用</b>）</td>
 *       <td>{@code …_out_side}</td><td>{@code false}</td></tr>
 *   <tr><td>{@code mtrlte:ticket_gate_brt_in}</td>
 *       <td>{@code brt_barrier_closed / _open}</td><td>{@code …_in_side}</td><td>{@code true}</td></tr>
 *   <tr><td>{@code mtrlte:ticket_gate_brt_out}</td><td>同上（<b>进出共用</b>）</td>
 *       <td>{@code …_out_side}</td><td>{@code false}</td></tr>
 * </table>
 *
 * <h2>为什么进出共用一套模型</h2>
 * <p>地面上看，进站闸机和出站闸机长得一模一样（厦门地铁的实际闸机就是同一款），差别只在判定：
 * 同一张 {@code OPEN} 状态图，两端靠 {@code isEntrance} 决定走 {@code onEnter} 还是 {@code onExit}。
 *
 * <h2>为什么每台是「本体 + 侧壳」两个方块</h2>
 * <p>新素材把闸机做成「两个柜子夹一条通道」，两份 JSON 用的是<b>同一套局部坐标</b>：
 * 本体的柜体在 {@code x 12..16}、挡杆扫到 {@code x -4}；{@code *_barrier_side.json} 是同一柜体镜像到
 * {@code x -4..0} 的那一份 —— 也就是「本体旁边那一格」里的柜子。一个方块塞不下两个柜体
 * （碰撞 / 框选 / 破坏都只能按格算），所以按 JCM 闸机的做法拆成两个方块：
 * 放本体时会自动在 {@link XiamenTicketGate#sideOf} 那一侧放下 {@link XiamenTicketGateSide}
 * （同向、贴着脸），拆一个另一个跟着走。四种组合各自一对模型，见 {@link Gate#rawModel} /
 * {@link Gate#sideRawModel}。
 *
 * <h2>侧壳为什么也在创造页签之外</h2>
 * <p>侧壳是结构件：没有自己的物品、不掉落（{@code data/mtrlte/loot_tables} 里没有它的表），
 * 玩家只拿本体那一件。页签里仍然是 <b>4 个物品</b>。
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

    /** 一台闸机：本体 id + 进 / 出 + 用哪两套素材。 */
    public static final class Gate {

        /** 本体方块 / 物品共用的 registry id。 */
        public final Identifier id;
        /** 侧壳方块的 registry id（{@code <本体 id>_side}；没有物品）。 */
        public final Identifier sideId;
        /** {@code true} = 进站（{@code BlockTicketBarrier(true)}）。 */
        public final boolean entrance;
        /** 物品模型前缀：{@code ticket_gate_xiamen} / {@code ticket_gate_brt}（item 模型名由它拼）。 */
        public final String model;
        /** 本体原始模型前缀：{@code ticket_barrier} / {@code brt_barrier}（渲染器再拼 {@code _open / _closed}）。 */
        public final String rawModel;
        /** 侧壳原始模型名：{@code ticket_barrier_side} / {@code brt_barrier_side}。 */
        public final String sideRawModel;

        private XiamenTicketGate block;
        private XiamenTicketGateSide side;
        private Item item;
        /** 侧壳物品（侧壳是独立方块，不再跟本体联动）。 */
        private Item sideItem;

        private Gate(final String path, final boolean entrance, final String model, final String rawModel,
                     final String sideRawModel) {
            this.id = new Identifier(XiamenObjects.NAMESPACE, path);
            this.sideId = new Identifier(XiamenObjects.NAMESPACE, path + "_side");
            this.entrance = entrance;
            this.model = model;
            this.rawModel = rawModel;
            this.sideRawModel = sideRawModel;
        }

        /** 注册好的本体方块（{@link XiamenTicketGates#register()} 之后非 null）。 */
        public XiamenTicketGate block() {
            return this.block;
        }

        /** 注册好的侧壳方块（{@link XiamenTicketGates#register()} 之后非 null）。 */
        public XiamenTicketGateSide side() {
            return this.side;
        }

        /** 方块物品（只有本体有）。 */
        public Item item() {
            return this.item;
        }

        /** 侧壳物品（侧壳是独立方块，2012-11-13 起不再跟本体联动）。 */
        public Item sideItem() {
            return this.sideItem;
        }
    }

    /** 页签里的顺序：地铁进 → 地铁出 → BRT 进 → BRT 出。 */
    private static final List<Gate> GATES = List.of(
            new Gate("ticket_gate_xiamen_in", true, "ticket_gate_xiamen", "ticket_barrier", "ticket_barrier_side"),
            new Gate("ticket_gate_xiamen_out", false, "ticket_gate_xiamen", "ticket_barrier", "ticket_barrier_side"),
            new Gate("ticket_gate_brt_in", true, "ticket_gate_brt", "brt_barrier", "brt_barrier_side"),
            new Gate("ticket_gate_brt_out", false, "ticket_gate_brt", "brt_barrier", "brt_barrier_side"));

    /** 八个闸机方块共用的方块实体类型（渲染器按方块实例选模型）。 */
    private static final Identifier ENTITY_ID = new Identifier(XiamenObjects.NAMESPACE, "ticket_gate");

    private static BlockEntityType<XiamenTicketGateBlockEntity> entityType;
    private static boolean registered;

    private XiamenTicketGates() {
    }

    /** 四台闸机（客户端挂渲染器时按它遍历）。 */
    public static List<Gate> gates() {
        return GATES;
    }

    /** 闸机的方块实体类型（{@link XiamenTicketGateBlockEntity} 构造器要用）。 */
    public static BlockEntityType<XiamenTicketGateBlockEntity> entityType() {
        return entityType;
    }

    /** 音效 + 8 个方块 + 4 个物品 + 方块实体类型 + 创造页签条目，{@code main} 入口（{@code LteServer}）调一次。 */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        // 音效是 registry 条目，服务端也要有（passThrough 在服务端 playSound）。
        XiamenTicketGateSounds.register();

        final Block[] blocks = new Block[GATES.size() * 2];
        for (int i = 0; i < GATES.size(); i++) {
            final Gate gate = GATES.get(i);
            // 方块实例自己记着进 / 出与素材名，几何由 BER 画，所以一款方块只需要一个类。
            gate.block = Registry.register(Registries.BLOCK, gate.id,
                    new XiamenTicketGate(gate.entrance, gate.rawModel));
            // 侧壳：纯装饰柜体。设置与本体对齐（叉不掉、需要工具、非满方块不让邻居面被剔除）。
            gate.side = Registry.register(Registries.BLOCK, gate.sideId,
                    new XiamenTicketGateSide(Block.Settings.create().strength(2.0F).requiresTool().nonOpaque(),
                            gate.sideRawModel));
            blocks[i * 2] = gate.block;
            blocks[i * 2 + 1] = gate.side;
            // 方块与物品同一个 id（本模组厦门物件的一贯做法）；侧壳是独立方块，也有自己的物品。
            gate.item = Registry.register(Registries.ITEM, gate.id, new BlockItem(gate.block, new Item.Settings()));
            gate.sideItem = Registry.register(Registries.ITEM, gate.sideId, new BlockItem(gate.side, new Item.Settings()));
        }

        // 方块实体类型：八个方块共用一个（服务端也要注册 —— 方块实体的创建/保存走的是它）。
        entityType = Registry.register(Registries.BLOCK_ENTITY_TYPE, ENTITY_ID,
                FabricBlockEntityTypeBuilder.create(XiamenTicketGateBlockEntity::new, blocks).build());

        try {
            ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, XiamenObjects.CORE_TAB_ID))
                    .register(entries -> {
                        for (final Gate gate : GATES) {
                            entries.add(gate.item);
                            entries.add(gate.sideItem);
                        }
                    });
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] ticket gates could not be added to the core tab", t);
        }

        for (final Gate gate : GATES) {
            LteLog.LOGGER.info("[lte] ticket gate {} (entrance={}, rawmodel={}_*, side={})",
                    gate.id, gate.entrance, gate.rawModel, gate.sideId);
        }
    }
}
