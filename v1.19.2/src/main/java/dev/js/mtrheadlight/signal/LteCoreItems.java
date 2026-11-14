package dev.js.mtrheadlight.signal;

import dev.js.mtrheadlight.compat.ItemGroupTabs;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import dev.js.mtrheadlight.LteLog;
import dev.js.mtrheadlight.TabIcons;
import dev.js.mtrheadlight.bind.BeamEditorItem;
import dev.js.mtrheadlight.bind.LteDashboardItem;
import dev.js.mtrheadlight.bind.PidsConnectorItem;
import dev.js.mtrheadlight.bind.ProjectorConnectorItem;
import dev.js.mtrheadlight.compat.ItemGroupEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * LTE 的物品与创造模式页签（{@code main} 入口，两端都注册）。
 *
 * <p>1.20.1 的做法（Fabric Item Group API v1）：
 * <ol>
 *   <li>{@code FabricItemGroup.builder()} 造 {@code ItemGroup}，注册进 {@code Registry.ITEM_GROUP}
 *       —— 页签本身是一个 registry 条目，不再像 1.16 那样靠 mixin 往
 *       {@code ItemGroup.GROUPS} 数组里塞；</li>
 *   <li>物品用 {@code ItemGroupEvents.modifyEntriesEvent(RegistryKey)} 追加，而不是
 *       {@code Item.Settings#group(...)}（1.19.3 起后者已删除）。</li>
 * </ol>
 *
 * <p>页签图标是一个只画图标的物品（{@link dev.js.mtrheadlight.TabIcons#tools()}，
 * 贴图 {@code assets/mtrlte/textures/item/tab_tools.png}），不是本页签里的信号连接器 ——
 * {@code icon} 收的是 supplier，注册顺序不敏感。
 *
 * <p>页签 id 从 1.0.0 起是 {@code mtrlte:tools}（「LTE 工具」）。{@code mtrlte:core}
 * 这个名字让给了「厦门风格物件」页签（{@link dev.js.mtrheadlight.xiamen.XiamenObjects}），
 * 因为 1.20.1 的页签是 registry 条目，同一个 id 注册两次会直接崩。
 *
 * <p>页签里的东西：{@code signal_linker}（信号灯 → 轨道节点）、{@code projector_connector}
 * （投影机 → 车站）、{@code pids_connector}（PIDS → 站台）、{@code lte_dashboard}（右键开配置界面）、
 * {@code beam_editor}（右键开 3D 光束编辑器）。
 * 前三件都是服务端权威的绑定工具，后两件只在客户端打开界面 —— 但<b>五个物品都在两端注册</b>：
 * 只在客户端注册的物品在服务器上是「未知物品」，联机时会掉。
 */
public final class LteCoreItems {

    /** 物品 id：{@code mtrlte:signal_linker}。 */
    public static final Identifier SIGNAL_LINKER_ID = new Identifier("mtrlte", "signal_linker");

    /** 物品 id：{@code mtrlte:projector_connector}（投影机 → 车站）。 */
    public static final Identifier PROJECTOR_CONNECTOR_ID = new Identifier("mtrlte", "projector_connector");

    /** 物品 id：{@code mtrlte:pids_connector}（PIDS → 站台）。 */
    public static final Identifier PIDS_CONNECTOR_ID = new Identifier("mtrlte", "pids_connector");

    /** 物品 id：{@code mtrlte:lte_dashboard}（右键开配置界面，仅客户端）。 */
    public static final Identifier DASHBOARD_ID = new Identifier("mtrlte", "lte_dashboard");

    /** 物品 id：{@code mtrlte:beam_editor}（右键开 3D 光束编辑器，仅客户端）。 */
    public static final Identifier BEAM_EDITOR_ID = new Identifier("mtrlte", "beam_editor");

    /** 页签 id：{@code mtrlte:tools}。 */
    public static final Identifier TOOLS_TAB_ID = new Identifier("mtrlte", "tools");

    /** LTE logo 方块的 id（{@code dev.js.mtrheadlight.logo.LogoObjects#LTE_ID}，这里写字面量避免类初始化环）。 */
    private static final Identifier LTE_LOGO_ID = new Identifier("mtrlte", "lte_logo");

    /** LTE 贴墙标识的 id（{@code dev.js.mtrheadlight.xiamen.sign.WallSignObjects}，同上）。 */
    private static final Identifier WALL_SIGN_LTE_ID = new Identifier("mtrlte", "wall_sign_lte");

    /**
     * 「LTE 工具」页签的最终顺序（用户点名，从前往后）：
     * <ol>
     *   <li>{@code lte_logo} —— LTE 标志方块；</li>
     *   <li>{@code wall_sign_lte} —— LTE 贴墙标识；</li>
     *   <li>{@code lte_dashboard} —— LTE 仪表板；</li>
     *   <li>{@code beam_editor} —— 车灯 / 光束编辑器；</li>
     *   <li>{@code signal_linker} → {@code projector_connector} → {@code pids_connector}
     *       —— 三个连接器，内部顺序不变。</li>
     * </ol>
     * 由 {@link #pinToolsTabOrder()} 在<b>所有往这个页签加条目的模块都注册完之后</b>（{@code WallSignObjects}）
     * 才挂上回调 —— 回调按注册顺序执行，早了就看不见后加的条目。
     */
    private static final List<Identifier> TOOLS_TAB_ORDER = List.of(
            LTE_LOGO_ID,
            WALL_SIGN_LTE_ID,
            DASHBOARD_ID,
            BEAM_EDITOR_ID,
            SIGNAL_LINKER_ID,
            PROJECTOR_CONNECTOR_ID,
            PIDS_CONNECTOR_ID);

    private static SignalLinkItem signalLinker;
    private static ProjectorConnectorItem projectorConnector;
    private static PidsConnectorItem pidsConnector;
    private static LteDashboardItem dashboard;
    private static BeamEditorItem beamEditor;
    private static boolean registered;

    private LteCoreItems() {
    }

    public static SignalLinkItem signalLinker() {
        return signalLinker;
    }

    public static ProjectorConnectorItem projectorConnector() {
        return projectorConnector;
    }

    public static PidsConnectorItem pidsConnector() {
        return pidsConnector;
    }

    public static LteDashboardItem dashboard() {
        return dashboard;
    }

    public static BeamEditorItem beamEditor() {
        return beamEditor;
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        signalLinker = Registry.register(Registry.ITEM, SIGNAL_LINKER_ID,
                new SignalLinkItem(new Item.Settings().maxCount(1)));
        // 两个连接器同样是「一次性绑定工具」：每个玩家只需要一把。
        projectorConnector = Registry.register(Registry.ITEM, PROJECTOR_CONNECTOR_ID,
                new ProjectorConnectorItem(new Item.Settings().maxCount(1)));
        pidsConnector = Registry.register(Registry.ITEM, PIDS_CONNECTOR_ID,
                new PidsConnectorItem(new Item.Settings().maxCount(1)));
        dashboard = Registry.register(Registry.ITEM, DASHBOARD_ID,
                new LteDashboardItem(new Item.Settings().maxCount(1)));
        // 车灯编辑器：右键开 3D 光束编辑器（和 /mtr_lte beam3d 同一个屏幕）。
        beamEditor = Registry.register(Registry.ITEM, BEAM_EDITOR_ID,
                new BeamEditorItem(new Item.Settings().maxCount(1)));

        // 页签图标：不是本页签里的任何物品，而是一个只画图标的物品（见 TabIcons 的类注释）。
        TabIcons.register();

        // 1.19.2 没有页签注册表：建页签这一步由 ItemGroupTabs 代做（进 ItemGroup.GROUPS）。
        ItemGroupTabs.create(TOOLS_TAB_ID, () -> new ItemStack(TabIcons.tools()));

        ItemGroupEvents.modifyEntriesEvent(TOOLS_TAB_ID)
                .register(entries -> {
                    entries.add(signalLinker);
                    entries.add(projectorConnector);
                    entries.add(pidsConnector);
                    entries.add(dashboard);
                    entries.add(beamEditor);
                });
    }

    /**
     * 把「LTE 工具」页签重排成 {@link #TOOLS_TAB_ORDER}：LTE logo → LTE 贴墙标识 → 仪表板 →
     * 光束编辑器 → 三个连接器（连接器内部顺序不变）。
     *
     * <h2>为什么不是「谁后注册谁在后」</h2>
     * <p>页签条目由 {@code ItemGroupEvents.modifyEntriesEvent} 的回调按<b>注册顺序</b>拼出来：
     * {@code LteCoreItems} 先加五个物品、{@code LogoObjects} 把 LTE logo 插到 0、
     * {@code WallSignObjects} 再把贴墙标识追加到末尾 —— 不加约束的话贴墙标识会沉到最后，
     * 而用户要的是第 2 位。这里跟 {@code LogoObjects#pinCoreTabOrder()} 一个做法：
     * 拿 {@code entries.getDisplayStacks()}（页签正在拼的可变列表）→ 按顺序把那几件摘出来 →
     * 一次性插回下标 0。其余条目保持原有相对顺序，只被「往下推」。
     *
     * <p>由 {@code WallSignObjects.register()} 在它自己加完条目之后调用（那是最后一个往这个页签
     * 加东西的模块）。
     */
    public static void pinToolsTabOrder() {
        try {
            ItemGroupEvents.modifyEntriesEvent(TOOLS_TAB_ID)
                    .register(entries -> {
                        final List<ItemStack> stacks = entries.getDisplayStacks();
                        final List<ItemStack> front = new ArrayList<>(TOOLS_TAB_ORDER.size());
                        for (final Identifier id : TOOLS_TAB_ORDER) {
                            final Item item = Registry.ITEM.get(id);
                            if (item == null || item == Items.AIR) {
                                // 没注册（例如某次构建裁掉了贴墙标识）：跳过，不影响其余排序。
                                continue;
                            }
                            for (final Iterator<ItemStack> it = stacks.iterator(); it.hasNext(); ) {
                                final ItemStack stack = it.next();
                                if (stack.isOf(item)) {
                                    front.add(stack);
                                    it.remove();
                                }
                            }
                        }
                        if (!front.isEmpty()) {
                            stacks.addAll(0, front);
                        }
                    });
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] tools tab order: could not pin the LTE tools tab", t);
        }
    }

    /** 页签在 registry 里的那一份（调试 / 其它模组想往里加东西时用）。 */
    public static ItemGroup tab() {
        return ItemGroupTabs.get(TOOLS_TAB_ID);
    }
}
