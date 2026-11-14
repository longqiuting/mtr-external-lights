package dev.js.mtrheadlight.server;

import dev.js.mtrheadlight.HeadlightConfig;
import dev.js.mtrheadlight.LteLog;
import dev.js.mtrheadlight.net.ServerAuthority;
import dev.js.mtrheadlight.net.ServerHandshake;
import net.fabricmc.api.ModInitializer;
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
 * <h2>失败模式</h2>
 * 每一个 MTR / 事件注册调用都包在 try/catch 里：MTR 没装、版本不匹配、数据未就绪都只是让
 * {@link ServerCollision} 什么都不做（它会自己再挡一层），绝不让服务端起不来。
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
            LteLog.LOGGER.warn("[lte] server config unavailable - using defaults", t);
        }
        // 「本进程是 dedicated server」—— 客户端的撞击伤害自动让位（没有客户端，无所谓）。
        if (net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType()
                == net.fabricmc.api.EnvType.SERVER) {
            ServerAuthority.markDedicated();
        }

        try {
            ServerHandshake.register();
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] handshake unavailable", t);
        }

        // LTE 核心物品 + 创造页签（物品注册必须在两端都跑，所以放在 main）。
        try {
            dev.js.mtrheadlight.signal.LteCoreItems.register();
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] connector items unavailable", t);
        }

        // /ltebind：投影机 / PIDS 绑定表的查询（服务端，读 PersistentState）。
        try {
            dev.js.mtrheadlight.bind.LteBindCommand.register();
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] /ltebind unavailable", t);
        }

        // 左键解绑（AttackBlockCallback，两端都挂）+ 方块被拆时自动解绑（PlayerBlockBreakEvents.AFTER）。
        try {
            dev.js.mtrheadlight.bind.BindUnbind.register();
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] connector unbind hooks unavailable", t);
        }

        // 厦门风格物件页签 + 屏蔽门方块（同样是两端都需要的 registry 条目）。
        try {
            dev.js.mtrheadlight.xiamen.XiamenObjects.register();
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] xiamen objects unavailable", t);
        }

        // 标志方块：LTE logo（进「LTE 工具」页签）+ 角美地铁 logo（进「厦门风格物件」页签）。
        // 必须排在上面两个页签之后 —— 往还没建的页签里加条目会抛异常。
        try {
            dev.js.mtrheadlight.logo.LogoObjects.register();
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] logo blocks unavailable", t);
        }

        // 贴墙标识（厦门 / 角美 / LTE）：方块直接继承 MTR 的 station_name_entrance，渲染器也是 MTR
        // 自己的 RenderStationNameTiled(dispatcher, true) —— 放置 / 朝向 / 站名 / 刷子 / 相邻连接全走 MTR。
        // 厦门 / 角美两面进「厦门风格物件」页签、紧跟两个 logo 块（LogoObjects.pinCoreTabOrder() 负责挪），
        // LTE 那面只进「LTE 工具」页签的第 2 位（LteCoreItems.pinToolsTabOrder() 负责挪），不进厦门页。
        try {
            dev.js.mtrheadlight.xiamen.sign.WallSignObjects.register();
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] wall sign blocks unavailable", t);
        }

        // 墙上扶手（进上面的「厦门风格物件」页签，所以排在其后）。
        try {
            dev.js.mtrheadlight.railing.RailingWallBlocks.register();
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] railing wall unavailable", t);
        }

        // 玻璃扶手：与墙上扶手同一套模型空间约定，同样进「厦门风格物件」页签，排在它后面。
        try {
            dev.js.mtrheadlight.railing.RailingGlassBlocks.register();
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] railing glass unavailable", t);
        }

        // 装饰方块（大理石地板 / 大理石站台）：站台继承 MTR 的 BlockPlatform，同样进「厦门风格物件」页签。
        try {
            dev.js.mtrheadlight.decor.DecorBlocks.register();
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] decor blocks unavailable", t);
        }

        // RCC 三种站台（平齐 / 内凹 / 石英）：独立页签 mtrlte:rcc_platform，同样继承 BlockPlatform。
        try {
            dev.js.mtrheadlight.decor.RccPlatforms.register();
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] rcc platforms unavailable", t);
        }

        // 厦门 PIDS（地铁 / BRT）：模型与渲染照天津地铁，广告表走资源包。
        try {
            dev.js.mtrheadlight.xiamen.pids.PidsXiamenObjects.register();
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] xiamen pids blocks unavailable", t);
        }

        // 厦门 PIDS 界面通道：客户端改广告序号 / 服务端叫开界面（刷子右键）。
        try {
            dev.js.mtrheadlight.xiamen.pids.PidsXiamenNet.registerServer();
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] xiamen pids channel unavailable", t);
        }

        // 挂墙发车倒数器：贴在站台附近的墙上，显示下一班车的发车倒计时（纯客户端读数据）。
        try {
            dev.js.mtrheadlight.xiamen.dep.DepOnWallObjects.register();
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] dep on wall block unavailable", t);
        }

        // 挂顶发车倒数器：吊在站台上方的天花板上，读数和挂墙版同一套（排在它后面）。
        try {
            dev.js.mtrheadlight.xiamen.dep.DepOnRoofObjects.register();
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] dep on roof block unavailable", t);
        }

        // 站外标志（厦门 / 角美 × 挂墙 / 杆上）：灯箱牌，同样进「厦门风格物件」页签。
        try {
            dev.js.mtrheadlight.xiamen.sign.StationSignObjects.register();
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] station sign blocks unavailable", t);
        }

        // 「厦门风格物件」页签的顺序（用户点名）：最前排两个 logo → 四件站外标志；
        // 两个贴墙标识（厦门 / 角美）排到本页末尾。
        // 这里必须排在 LogoObjects / WallSignObjects / StationSignObjects 之后 —— 页签条目的回调
        // 按注册顺序执行，重排的那一次得看得见它们；之后再注册的模块只会往末尾加，排不回来。
        try {
            dev.js.mtrheadlight.logo.LogoObjects.pinCoreTabOrder();
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] core tab order unavailable", t);
        }

        // 售票机 / 余额查询机（各两格高）：售票机接方速 / 原版两套界面（刷子切），
        // 余额查询机服务端读记分板 mtr_balance 后用一句文字报余额。同样进「厦门风格物件」页签。
        try {
            dev.js.mtrheadlight.xiamen.machine.XiamenMachines.register();
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] xiamen ticket / balance machines unavailable", t);
        }

        // 共享充电宝租借机（上半 / 下半各自独立的两块方块）+ 充电宝物品 + 投掷实体。
        // 租借 / 归还都在这里注册的服务端逻辑里：余额读 MTR 的记分板 mtr_balance（bind.MtrBalance）。
        try {
            dev.js.mtrheadlight.xiamen.charger.ChargerObjects.register();
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] power bank charger unavailable", t);
        }

        // 饮料售卖机（上下两块联动）：空手右键 3 元出一瓶随机正面药水，刷子右键换药水池。
        try {
            dev.js.mtrheadlight.xiamen.drinks.DrinksMachineObjects.register();
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] drinks machine unavailable", t);
        }

        // 安检类方块（液体安检机）：方块 + 物品 + 创造页签条目，排在厦门那一组之后注册。
        try {
            dev.js.mtrheadlight.security.SecurityObjects.register();
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] security blocks unavailable", t);
        }

        // 闸机（厦门地铁 / BRT × 进站 / 出站，共 4 个方块）：继承 MTR 的 BlockTicketBarrier，
        // 音效换成南滨的 crt_ticket / crt_ticket_error（这次一并注册这两个 SoundEvent）。
        try {
            dev.js.mtrheadlight.xiamen.ticket.XiamenTicketGates.register();
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] xiamen ticket gates unavailable", t);
        }

        // eyecandy（.obj 几何，运行时由 BER 画）：厦门风格物件包 26 件追加进「厦门风格物件」页签，
        // 南普陀 34 件单开一页 mtrlte:nanputuo_decor。碰撞箱在注册期从 obj 顶点重推（两端都要，
        // 所以排在服务端也跑的这一段里）。每个方块带四向 rotation，MTR 刷子右键循环。
        try {
            dev.js.mtrheadlight.xiamen.eyecandy.EyecandyBlocks.register();
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] xiamen eyecandy decorations unavailable", t);
        }

        // 方速（fangsu）屏蔽门搬运版：5 款旋转端门 + 2 款厦门滑动门，各拆左/右共 14 个方块，
        // 外加 2 块「厦门高」玻璃板（一个方块 4 种形态，刷子循环）。
        // 几何 / 材质 / 贴图 / 描述全部随 jar 走（assets/mtrlte/fangsu_psd/ + textures/fangsu_psd/），
        // 不需要装方速。滑动门继承 MTR 的 BlockPSDAPGDoorBase（列车到站自动开门、发车自动关门）；
        // 端门是普通方块 + 方块实体，玩家手持 MTR 驾驶钥匙右键开关（不接列车）。
        try {
            dev.js.mtrheadlight.xiamen.fangsu.FangsuPsdBlocks.register();
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] fangsu psd doors unavailable", t);
        }

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

        LteLog.LOGGER.info("[lte] server initialised "
                        + "(collision={} damage={} mobs={})",
                Boolean.valueOf(config.trainCollision),
                Boolean.valueOf(config.trainCollisionDamage),
                Boolean.valueOf(config.trainCollisionMobs));
    }
}
