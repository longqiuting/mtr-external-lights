package dev.js.mtrheadlight.signal;

import dev.js.mtrheadlight.TabIcons;
import dev.js.mtrheadlight.bind.LteDashboardItem;
import dev.js.mtrheadlight.bind.PidsConnectorItem;
import dev.js.mtrheadlight.bind.ProjectorConnectorItem;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * LTE 的物品与创造模式页签（{@code main} 入口，两端都注册）。
 *
 * <p>1.20.1 的做法（Fabric Item Group API v1）：
 * <ol>
 *   <li>{@code FabricItemGroup.builder()} 造 {@code ItemGroup}，注册进 {@code Registries.ITEM_GROUP}
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
 * <p>页签里的四件东西：{@code signal_linker}（信号灯 → 轨道节点）、{@code projector_connector}
 * （投影机 → 车站）、{@code pids_connector}（PIDS → 站台）、{@code lte_dashboard}（右键开配置界面）。
 * 前三件都是服务端权威的绑定工具，第四件只在客户端打开界面 —— 但<b>四个物品都在两端注册</b>：
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

    /** 页签 id：{@code mtrlte:tools}。 */
    public static final Identifier TOOLS_TAB_ID = new Identifier("mtrlte", "tools");

    private static SignalLinkItem signalLinker;
    private static ProjectorConnectorItem projectorConnector;
    private static PidsConnectorItem pidsConnector;
    private static LteDashboardItem dashboard;
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

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        signalLinker = Registry.register(Registries.ITEM, SIGNAL_LINKER_ID,
                new SignalLinkItem(new Item.Settings().maxCount(1)));
        // 两个连接器同样是「一次性绑定工具」：每个玩家只需要一把。
        projectorConnector = Registry.register(Registries.ITEM, PROJECTOR_CONNECTOR_ID,
                new ProjectorConnectorItem(new Item.Settings().maxCount(1)));
        pidsConnector = Registry.register(Registries.ITEM, PIDS_CONNECTOR_ID,
                new PidsConnectorItem(new Item.Settings().maxCount(1)));
        dashboard = Registry.register(Registries.ITEM, DASHBOARD_ID,
                new LteDashboardItem(new Item.Settings().maxCount(1)));

        // 页签图标：不是本页签里的任何物品，而是一个只画图标的物品（见 TabIcons 的类注释）。
        TabIcons.register();

        Registry.register(Registries.ITEM_GROUP, TOOLS_TAB_ID,
                FabricItemGroup.builder()
                        .displayName(Text.translatable("itemGroup.mtrlte.tools"))
                        .icon(() -> new ItemStack(TabIcons.tools()))
                        .build());

        ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, TOOLS_TAB_ID))
                .register(entries -> {
                    entries.add(signalLinker);
                    entries.add(projectorConnector);
                    entries.add(pidsConnector);
                    entries.add(dashboard);
                });
    }

    /** 页签在 registry 里的那一份（调试 / 其它模组想往里加东西时用）。 */
    public static ItemGroup tab() {
        return Registries.ITEM_GROUP.get(TOOLS_TAB_ID);
    }
}
