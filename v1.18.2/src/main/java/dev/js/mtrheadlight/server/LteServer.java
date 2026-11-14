package dev.js.mtrheadlight.server;

import dev.js.mtrheadlight.HeadlightConfig;
import dev.js.mtrheadlight.LteLog;
import dev.js.mtrheadlight.net.ServerAuthority;
import dev.js.mtrheadlight.net.ServerHandshake;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/**
 * {@code main} 入口点 —— <b>服务端安全</b>，不引用任何客户端类。
 *
 * <h2>它负责什么</h2>
 * <ol>
 *   <li>读本侧配置（dedicated server 的 {@code config/mtrlte.json}）；</li>
 *   <li>标记「本进程里有 LTE 的服务端」→ 客户端的撞击伤害自动让位（{@link ServerAuthority}）；</li>
 *   <li>挂 {@code END_SERVER_TICK} → {@link ServerCollision#tick}
 *       （dedicated server 与单人整合服务器都走这里）；</li>
 *   <li>握手：玩家进服时告诉装了 LTE 的客户端「撞击由我结算」。</li>
 * </ol>
 *
 * <h2>纯客户端模式</h2>
 * 这个入口在客户端进程里也会被 Fabric 加载（{@code environment="*"} + 整合服务器需要它），
 * 所以客户端进单人游戏时服务端权威立刻成立，客户端那边的 {@code CollisionSystem} 只负责推出 /
 * 车顶 / 表现，扣血只有一份。连别人的服务器时本类的服务端 tick 不存在，客户端靠握手判断。
 *
 * <h2>失败模式：吞异常，但必须留下能查的痕迹</h2>
 * 每一个模块的注册调用都包在 try/catch 里：MTR 没装、版本不匹配、数据未就绪都只是让这个模块
 * 什么都不做，绝不让服务端起不来。但「吞掉」不等于「不记」——
 * <ul>
 *   <li>方块注册模块走 {@link #blockModule}：失败时打 <b>ERROR</b> + 全栈，并写明
 *       「注册进去几块 / 建了但没注册几块」，同时立刻把那几个孤儿 Block 引用清掉
 *       （详见 {@link RegistrySelfCheck}）；</li>
 *   <li>其它模块（物品、命令、网络通道、页签顺序）走 {@link #module}：失败时同样 ERROR + 全栈。</li>
 * </ul>
 * 这样 1.18.2 专用服务端「Some intrusive holders were not registered」那种只能看到
 * {@code Block{minecraft:air}} 的崩溃，日志里会直接指出是哪个模块、哪个方块类。
 */
public final class LteServer implements ModInitializer {

    /** 本侧配置。dedicated server 上就是文件里的那一份。 */
    private static volatile HeadlightConfig config = new HeadlightConfig();

    public static HeadlightConfig config() {
        return config;
    }

    @Override
    public void onInitialize() {
        try {
            config = HeadlightConfig.load();
            ServerConfig.install(LteServer::config);
        } catch (Throwable t) {
            LteLog.LOGGER.error("[lte] server config unavailable - using defaults", t);
        }
        // 「本进程是 dedicated server」—— 客户端的撞击伤害自动让位（没有客户端，无所谓）。
        if (net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType()
                == net.fabricmc.api.EnvType.SERVER) {
            ServerAuthority.markDedicated();
        }

        module("handshake", ServerHandshake::register);

        // LTE 核心物品 + 创造页签（物品注册必须在两端都跑，所以放在 main）。
        module("connector items", dev.js.mtrheadlight.signal.LteCoreItems::register);

        // /ltebind：投影机 / PIDS 绑定表的查询（服务端，读 PersistentState）。
        module("/ltebind", dev.js.mtrheadlight.bind.LteBindCommand::register);

        // 左键解绑（AttackBlockCallback，两端都挂）+ 方块被拆时自动解绑（PlayerBlockBreakEvents.AFTER）。
        module("connector unbind hooks", dev.js.mtrheadlight.bind.BindUnbind::register);

        // 厦门风格物件页签 + 屏蔽门方块（同样是两端都需要的 registry 条目）。
        blockModule("xiamen objects", dev.js.mtrheadlight.xiamen.XiamenObjects::register);

        // 标志方块：LTE logo（进「LTE 工具」页签）+ 角美地铁 logo（进「厦门风格物件」页签）。
        // 必须排在上面两个页签之后 —— 往还没建的页签里加条目会抛异常。
        blockModule("logo blocks", dev.js.mtrheadlight.logo.LogoObjects::register);

        // 贴墙标识（厦门 / 角美 / LTE）：方块直接继承 MTR 的 station_name_entrance，渲染器也是 MTR
        // 自己的 RenderStationNameTiled(dispatcher, true) —— 放置 / 朝向 / 站名 / 刷子 / 相邻连接全走 MTR。
        // 厦门 / 角美两面进「厦门风格物件」页签、紧跟两个 logo 块（LogoObjects.pinCoreTabOrder() 负责挪），
        // LTE 那面只进「LTE 工具」页签的第 2 位（LteCoreItems.pinToolsTabOrder() 负责挪），不进厦门页。
        blockModule("wall sign blocks", dev.js.mtrheadlight.xiamen.sign.WallSignObjects::register);

        // 墙上扶手（进上面的「厦门风格物件」页签，所以排在其后）。
        blockModule("railing wall", dev.js.mtrheadlight.railing.RailingWallBlocks::register);

        // 玻璃扶手：与墙上扶手同一套模型空间约定，同样进「厦门风格物件」页签，排在它后面。
        blockModule("railing glass", dev.js.mtrheadlight.railing.RailingGlassBlocks::register);

        // 装饰方块（大理石地板 / 大理石站台）：站台继承 MTR 的 BlockPlatform，同样进「厦门风格物件」页签。
        blockModule("decor blocks", dev.js.mtrheadlight.decor.DecorBlocks::register);

        // RCC 三种站台（平齐 / 内凹 / 石英）：独立页签 mtrlte:rcc_platform，同样继承 BlockPlatform。
        blockModule("rcc platforms", dev.js.mtrheadlight.decor.RccPlatforms::register);

        // 厦门 PIDS（地铁 / BRT）：模型与渲染照天津地铁，广告表走资源包。
        blockModule("xiamen pids blocks", dev.js.mtrheadlight.xiamen.pids.PidsXiamenObjects::register);

        // 厦门 PIDS 界面通道：客户端改广告序号 / 服务端叫开界面（刷子右键）。
        module("xiamen pids channel", dev.js.mtrheadlight.xiamen.pids.PidsXiamenNet::registerServer);

        // 挂墙发车倒数器：贴在站台附近的墙上，显示下一班车的发车倒计时（纯客户端读数据）。
        blockModule("dep on wall block", dev.js.mtrheadlight.xiamen.dep.DepOnWallObjects::register);

        // 挂顶发车倒数器：吊在站台上方的天花板上，读数和挂墙版同一套（排在它后面）。
        blockModule("dep on roof block", dev.js.mtrheadlight.xiamen.dep.DepOnRoofObjects::register);

        // 站外标志（厦门 / 角美 × 挂墙 / 杆上）：灯箱牌，同样进「厦门风格物件」页签。
        blockModule("station sign blocks", dev.js.mtrheadlight.xiamen.sign.StationSignObjects::register);

        // 「厦门风格物件」页签的顺序（用户点名）：最前排两个 logo → 四件站外标志；
        // 两个贴墙标识（厦门 / 角美）排到本页末尾。
        // 这里必须排在 LogoObjects / WallSignObjects / StationSignObjects 之后 —— 页签条目的回调
        // 按注册顺序执行，重排的那一次得看得见它们；之后再注册的模块只会往末尾加，排不回来。
        module("core tab order", dev.js.mtrheadlight.logo.LogoObjects::pinCoreTabOrder);

        // 售票机 / 余额查询机（各两格高）：售票机接方速 / 原版两套界面（刷子切），
        // 余额查询机服务端读记分板 mtr_balance 后用一句文字报余额。同样进「厦门风格物件」页签。
        blockModule("xiamen ticket / balance machines",
                dev.js.mtrheadlight.xiamen.machine.XiamenMachines::register);

        // 共享充电宝租借机（上半 / 下半各自独立的两块方块）+ 充电宝物品 + 投掷实体。
        // 租借 / 归还都在这里注册的服务端逻辑里：余额读 MTR 的记分板 mtr_balance（bind.MtrBalance）。
        blockModule("power bank charger", dev.js.mtrheadlight.xiamen.charger.ChargerObjects::register);

        // 饮料售卖机（上下两块联动）：空手右键 3 元出一瓶随机正面药水，刷子右键换药水池。
        blockModule("drinks machine", dev.js.mtrheadlight.xiamen.drinks.DrinksMachineObjects::register);

        // 安检类方块（液体安检机）：方块 + 物品 + 创造页签条目，排在厦门那一组之后注册。
        blockModule("security blocks", dev.js.mtrheadlight.security.SecurityObjects::register);

        // 闸机（厦门地铁 / BRT × 进站 / 出站，共 4 个方块）：继承 MTR 的 BlockTicketBarrier，
        // 音效换成南滨的 crt_ticket / crt_ticket_error（这次一并注册这两个 SoundEvent）。
        blockModule("xiamen ticket gates", dev.js.mtrheadlight.xiamen.ticket.XiamenTicketGates::register);

        // eyecandy（.obj 几何，运行时由 BER 画）：厦门风格物件包 26 件追加进「厦门风格物件」页签，
        // 南普陀 34 件单开一页 mtrlte:nanputuo_decor。碰撞箱在注册期从 obj 顶点重推（两端都要，
        // 所以排在服务端也跑的这一段里）。每个方块带四向 rotation，MTR 刷子右键循环。
        blockModule("xiamen eyecandy decorations",
                dev.js.mtrheadlight.xiamen.eyecandy.EyecandyBlocks::register);

        // 方速（fangsu）屏蔽门搬运版：5 款旋转端门 + 2 款厦门滑动门，各拆左/右共 14 个方块，
        // 外加 2 块「厦门高」玻璃板（一个方块 4 种形态，刷子循环）。
        // 几何 / 材质 / 贴图 / 描述全部随 jar 走（assets/mtrlte/fangsu_psd/ + textures/fangsu_psd/），
        // 不需要装方速。滑动门继承 MTR 的 BlockPSDAPGDoorBase（列车到站自动开门、发车自动关门）；
        // 端门是普通方块 + 方块实体，玩家手持 MTR 驾驶钥匙右键开关（不接列车）。
        blockModule("fangsu psd doors", dev.js.mtrheadlight.xiamen.fangsu.FangsuPsdBlocks::register);

        try {
            ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
                try {
                    // 进服：把玩家所在维度的两张表都同步过去（渲染要它）。
                    final net.minecraft.server.network.ServerPlayerEntity player = handler.player;
                    final net.minecraft.server.world.ServerWorld world = (net.minecraft.server.world.ServerWorld) player.getWorld();
                    final dev.js.mtrheadlight.signal.SignalLinkStore store =
                            dev.js.mtrheadlight.signal.SignalLinkStore.get(world);
                    dev.js.mtrheadlight.signal.SignalLinkNet.sendAll(player,
                            dev.js.mtrheadlight.signal.SignalLinkNet.dimensionOf(world),
                            store.links(), store.lampLinks());
                } catch (Throwable ignored) {
                    // 同步失败只是绑定的信号灯不亮，不影响进服。
                }
            });
        } catch (Throwable t) {
            LteLog.LOGGER.debug("[lte] could not hook the signal-link sync", t);
        }

        try {
            ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
                try {
                    ServerHandshake.announce(server, handler.player);
                } catch (Throwable ignored) {
                    // 握手只是优化，失败不影响游戏。
                }
            });
        } catch (Throwable t) {
            LteLog.LOGGER.debug("[lte] could not hook the join event", t);
        }

        try {
            ServerTickEvents.END_SERVER_TICK.register(server -> {
                try {
                    ServerCollision.tick(server);
                } catch (Throwable t) {
                    // 服务端碰撞永远不许影响 tick（MTR 数据异常、类缺失都在这里被吞掉）。
                    ServerCollision.warnOnce(t);
                }
                // PIDS / 投影机：把「绑定过的站台」加进附近玩家的到站数据范围。
                // 不加这一步，站外显示屏会在 MTR 下一次 PIDS 数据包（≤3 秒）里被清成空屏。
                try {
                    dev.js.mtrheadlight.bind.PidsScheduleScope.tick(server);
                } catch (Throwable ignored) {
                    // 索引只是补数据，失败就退回 MTR 原行为。
                }
                // 玩家走进 64 格时补推一次方块实体 NBT（绑定那一刻的推送够不到后来的人）。
                try {
                    dev.js.mtrheadlight.bind.DisplayPushTicker.tick(server);
                } catch (Throwable ignored) {
                    // 同上。
                }
            });
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] server collision unavailable", t);
        }

        // 方块注册表自检（早）：1.18.2 的 {@code Block} 构造器会把自己挂进注册表的「待绑定」表
        // （{@code SimpleRegistry.unfrozenValueToEntry}），没有 register 就永远留在里面 —— 注册表冻结时抛
        // "Some intrusive holders were not registered: [Reference{null=Block{minecraft:air}}]"，
        // 崩栈里全是 minecraft:air，看不出是谁。这里先把（此刻已经出现的）漏网方块点名并清掉。
        // 真正的最后一道是 SimpleRegistryFreezeMixin —— 它跑在所有模组都注册完之后、冻结之前。
        try {
            RegistrySelfCheck.purgePending("init");
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] block registry self-check unavailable", t);
        }

        // 全注册表扫描放到 SERVER_STARTING：那时所有模组（不止我们）都注册完了，
        // 数出来的 "N blocks, M unregistered" 才是完整的一副牌。
        try {
            ServerLifecycleEvents.SERVER_STARTING.register(server -> {
                try {
                    RegistrySelfCheck.purgePending("server-starting");
                    RegistrySelfCheck.reportBlocks("server-starting");
                } catch (Throwable t) {
                    LteLog.LOGGER.warn("[lte] block registry self-check failed", t);
                }
            });
        } catch (Throwable t) {
            LteLog.LOGGER.debug("[lte] could not hook the block registry self-check", t);
        }

        LteLog.LOGGER.info("[lte] server initialised "
                        + "(collision={} damage={} mobs={})",
                Boolean.valueOf(config.trainCollision),
                Boolean.valueOf(config.trainCollisionDamage),
                Boolean.valueOf(config.trainCollisionMobs));
    }

    /**
     * 跑一个模块的注册；失败时 <b>ERROR + 全栈</b>（不再是 warn），但绝不让服务端起不来。
     *
     * <p>{@code name} 会出现在日志里 —— 服主一眼能看到是哪一块坏了。
     */
    private static void module(final String name, final Runnable body) {
        try {
            body.run();
        } catch (final Throwable t) {
            LteLog.LOGGER.error("[lte] {} registration aborted (the rest of the mod keeps working)", name, t);
        }
    }

    /**
     * 注册方块的模块：除了 {@link #module} 的 ERROR 日志，还额外点名「建了但没注册」的方块。
     *
     * <p>1.18.2 的 {@code Block} 构造器一执行就挂进注册表的「待绑定」表
     * （{@code Registry.BLOCK.createEntry(this)} → {@code SimpleRegistry.unfrozenValueToEntry}），
     * 只有 {@code Registry.register} 才会补上 id。所以模块在「建了一批、还没注册完」的时候抛异常，
     * 就会留下一批孤儿 Block —— 注册表冻结时抛
     * {@code Some intrusive holders were not registered: [Reference{null=Block{minecraft:air}}]}，
     * 而且崩溃信息里全是一样的 {@code minecraft:air}，完全看不出是谁。
     *
     * <p>这里量两件事并写进日志：<b>注册进去几块</b>（注册表里本模组条目数变化）、
     * <b>建了但没注册几块</b>（「待绑定」表条目数变化）。随后立刻清掉这些孤儿引用
     * （{@link RegistrySelfCheck#purgePending}），服务端就能正常冻结注册表、起到 {@code Done}。
     */
    private static void blockModule(final String name, final Runnable body) {
        final int blocksBefore = RegistrySelfCheck.countBlocks();
        final int oursBefore = RegistrySelfCheck.countBlocks("mtrlte");
        final int pendingBefore = RegistrySelfCheck.pendingCount();
        try {
            body.run();
        } catch (final Throwable t) {
            final int registered = RegistrySelfCheck.countBlocks("mtrlte") - oursBefore;
            final int leaked = Math.max(0, RegistrySelfCheck.pendingCount() - pendingBefore);
            LteLog.LOGGER.error("[lte] {} registration aborted: {} block(s) registered, {} block(s) created"
                            + " but never registered (blocks total {} -> {}); the rest of the mod keeps working",
                    name, Integer.valueOf(registered), Integer.valueOf(leaked),
                    Integer.valueOf(blocksBefore), Integer.valueOf(RegistrySelfCheck.countBlocks()), t);
            try {
                RegistrySelfCheck.purgePending(name);
            } catch (final Throwable ignored) {
                // 自检自己不许再抛。
            }
        }
    }
}
