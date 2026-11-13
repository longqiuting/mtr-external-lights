// 四个闸机方块（厦门地铁 / 厦门BRT × 进站 / 出站）的注册点：方块 + 物品 + 创造页签。
package dev.js.mtrheadlight.xiamen.ticket;

import java.util.List;

import dev.js.mtrheadlight.LteLog;
import dev.js.mtrheadlight.xiamen.XiamenObjects;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

/**
 * 闸机的注册入口：<b>4 个方块</b> = 2 款外观 × 进 / 出。
 *
 * <table border="1">
 *   <caption>四个方块</caption>
 *   <tr><th>id</th><th>模型（closed / open）</th><th>{@code isEntrance}</th></tr>
 *   <tr><td>{@code mtrlte:ticket_gate_xiamen_in}</td>
 *       <td>{@code ticket_gate_xiamen_closed} / {@code _open}</td><td>{@code true}</td></tr>
 *   <tr><td>{@code mtrlte:ticket_gate_xiamen_out}</td><td>同上（<b>进出共用</b>）</td><td>{@code false}</td></tr>
 *   <tr><td>{@code mtrlte:ticket_gate_brt_in}</td>
 *       <td>{@code ticket_gate_brt_closed} / {@code _open}</td><td>{@code true}</td></tr>
 *   <tr><td>{@code mtrlte:ticket_gate_brt_out}</td><td>同上（<b>进出共用</b>）</td><td>{@code false}</td></tr>
 * </table>
 *
 * <h2>为什么进出共用一套模型</h2>
 * <p>地面上看，进站闸机和出站闸机长得一模一样（厦门地铁的实际闸机就是同一款），差别只在判定：
 * 同一张 {@code OPEN} 状态图，两端靠 {@code isEntrance} 决定走 {@code onEnter} 还是 {@code onExit}。
 * 所以 blockstates 里 in / out 两个文件指向<b>同一对</b>模型文件（{@code ticket_gate_xiamen_closed}
 * / {@code _open}），只有 BRT 那一款换一对贴图与模型。
 *
 * <h2>开 / 关两种状态</h2>
 * <p>每款两份模型：{@code *_closed}（{@code light} 分组原样）与 {@code *_open}
 * （{@code light_on} 分组带 {@code "shade": false}，灯组不受方向光影响 = 自发光观感）。
 * 模型由 {@code tools/gen-ticket-gate-models.js} 从 Blockbench 素材生成（含旋转角吸附、uv 归一化）。
 *
 * <p>由 {@code LteServer} 在厦门售票机 / 余额查询机之后调用；四个物品进「厦门风格物件」页签
 * （{@link XiamenObjects#CORE_TAB_ID}）。
 */
public final class XiamenTicketGates {

    /** 一个闸机方块：id + 进 / 出 + 用哪一套模型（模型只看款式，不看进 / 出）。 */
    public static final class Gate {

        /** 方块 / 物品共用的 registry id。 */
        public final Identifier id;
        /** {@code true} = 进站（{@code BlockTicketBarrier(true)}）。 */
        public final boolean entrance;
        /** 模型前缀：{@code ticket_gate_xiamen} / {@code ticket_gate_brt}（blockstate 里的 {@code _closed} / {@code _open} 由它拼）。 */
        public final String model;

        private XiamenTicketGate block;
        private Item item;

        private Gate(final String path, final boolean entrance, final String model) {
            this.id = new Identifier(XiamenObjects.NAMESPACE, path);
            this.entrance = entrance;
            this.model = model;
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
            new Gate("ticket_gate_xiamen_in", true, "ticket_gate_xiamen"),
            new Gate("ticket_gate_xiamen_out", false, "ticket_gate_xiamen"),
            new Gate("ticket_gate_brt_in", true, "ticket_gate_brt"),
            new Gate("ticket_gate_brt_out", false, "ticket_gate_brt"));

    private static boolean registered;

    private XiamenTicketGates() {
    }

    /** 四个方块（客户端挂渲染层时按它遍历）。 */
    public static List<Gate> gates() {
        return GATES;
    }

    /** 音效 + 4 个方块 + 4 个物品 + 创造页签条目，{@code main} 入口（{@code LteServer}）调一次。 */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        // 音效是 registry 条目，服务端也要有（passThrough 在服务端 playSound）。
        XiamenTicketGateSounds.register();

        for (final Gate gate : GATES) {
            // 方块实例自己记着进 / 出，贴图与模型由 blockstate 按 id 决定，所以一款方块只需要一个类。
            gate.block = Registry.register(Registries.BLOCK, gate.id, new XiamenTicketGate(gate.entrance));
            // 方块与物品同一个 id（本模组厦门物件的一贯做法）。
            gate.item = Registry.register(Registries.ITEM, gate.id, new BlockItem(gate.block, new Item.Settings()));
        }

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
            LteLog.LOGGER.info("[lte] ticket gate {} (entrance={}, model={})",
                    gate.id, gate.entrance, gate.model);
        }
    }
}
