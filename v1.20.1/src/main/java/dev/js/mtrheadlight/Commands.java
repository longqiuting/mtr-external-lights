package dev.js.mtrheadlight;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.js.mtrheadlight.collision.CollisionImpact;
import dev.js.mtrheadlight.mtr.BuiltInTrains;
import dev.js.mtrheadlight.server.ServerConfig;
import dev.js.mtrheadlight.xiamen.dep.DepOnWallDebug;
import dev.js.mtrheadlight.xiamen.dep.DepOnWallOrient;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

/**
 * {@code /mtr_lte} - client command that opens the config screen.
 *
 * <p>{@code /externallights} is registered as a second root with the same body: the mod shipped
 * under that name before the rename to LTE, and keeping it for one release means existing muscle
 * memory and older guides keep working. Both roots are built by {@link #root}, so the two can
 * never drift apart.
 *
 * <p>{@code /mtr_lte headlight <auto|always|off>} switches {@code headlightMode} without editing
 * the JSON by hand - the mode is read on every tick and every frame, so the change is live and the
 * next 5-second {@code auto light:} line confirms it. It writes {@code config/mtrlte.json} straight
 * away, exactly like the config screen.
 *
 * <p>{@code /mtr_lte debug spray on|off} - the visible particle probe (a fixed burst at the feet).
 * It ignores trains, models, siding overrides and the master switch, so a probe that fails to appear
 * localises the fault to the particle path itself instead of the weather gate.
 *
 * <p>{@code /mtr_lte flare [status|style band|flare|both]} - 车灯的两套可见几何与真实方块光照。
 * 样式三档（{@link HeadlightConfig.LightStyle}）：光带（{@code BeamGlow}，默认）/ 灯晕
 * （{@code LightFlare}）/ 两者；{@code style} 子命令就是界面「光样式」那一行，写同一份配置、
 * 立即生效。灯位来自 {@link TrainLamps}（模型 LIGHT 部件 → {@code builtin_lights.json} →
 * 尺寸兜底），地面亮斑来自 {@code BeamBlockLight}，所以 {@code status} 顺带报告灯位诊断线与
 * 方块光照挂钩是否在运行。光柱（体积锥 / raymarch 体积光）已整体移除，对应的
 * {@code /mtr_lte cone …} 与 {@code /mtr_lte debug cone…} 子命令一并删除。
 *
 * <p>{@code /mtr_lte dep [status|next|normal|mirror|flip|rotate]} - 挂墙发车倒数器
 * （{@code mtrlte:dep_on_wall}）的<b>读数方向</b>。四档都是绕屏幕正中的 {@code scale(±1,±1,1)}：
 * 屏幕占的面积不变，变的只有数字的朝向。改完下一帧生效、落盘到
 * {@code config/mtrlte-dep-orient.json}，所以实机方向不对时不用等重编发版 —— 现场翻到对的那一档，
 * 再把 {@link DepOnWallOrient#DEFAULT} 改过去即可。
 *
 * <p>{@code /mtr_lte dep debug [on|off|status]} - 这块牌子的<b>取证日志</b>（默认关，只在内存里）。
 * 打开后每块牌子每秒一行 {@code [lte] dep: pos=… platform=… entries=… firstArrival=… now=… sec=…
 * dwell=… dwellHalf=… remain=… phase=… lockArrival=…}；「倒到 2 突然变 5」时靠
 * {@code firstArrival} 和 {@code lockArrival} 是否一致，就能判断是服务端换了条目 / 重算了到站时刻，
 * 还是 {@code now} 跳了。见 {@link DepOnWallDebug}。
 */
public final class Commands {
    /** Current name. */
    private static final String NAME = "mtr_lte";
    /** Pre-rename name, kept as an alias for one release. */
    private static final String LEGACY_NAME = "externallights";

    private Commands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(root(ClientCommandManager.literal(NAME)));
            dispatcher.register(root(ClientCommandManager.literal(LEGACY_NAME)));
            // 一行启动日志说明客户端指令树里到底有什么：/mtr_lte beam3d「什么都没发生」时，
            // 先确认这条字面量真的挂上了（false = 注册没走到，跟界面无关）。
            final var node = dispatcher.getRoot().getChild(NAME);
            LteLog.LOGGER.info("[lte] commands registered: /{} beam3d={}", NAME,
                    Boolean.valueOf(node != null && node.getChild("beam3d") != null));
        });
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> root(
            final LiteralArgumentBuilder<FabricClientCommandSource> root) {
        return root
                .executes(context -> {
                    context.getSource().sendFeedback(Text.literal("LTE：指令执行成功"));
                    // Same entry point as the key binding - see MtrHeadlightClient#openConfigScreen.
                    MtrHeadlightClient.openConfigScreen();
                    return 1;
                })
                .then(ClientCommandManager.literal("beam3d")
                        .executes(context -> openBeamEditor(null))
                        .then(ClientCommandManager.argument("vehicle", StringArgumentType.greedyString())
                                .executes(context -> openBeamEditor(
                                        StringArgumentType.getString(context, "vehicle")))))
                .then(ClientCommandManager.literal("headlight")
                        .executes(context -> report(context.getSource()))
                        .then(ClientCommandManager.argument("mode", StringArgumentType.word())
                                .suggests((context, builder) -> builder
                                        .suggest(HeadlightConfig.MODE_AUTO)
                                        .suggest(HeadlightConfig.MODE_ALWAYS)
                                        .suggest(HeadlightConfig.MODE_OFF)
                                        .buildFuture())
                                .executes(context -> set(context.getSource(),
                                        StringArgumentType.getString(context, "mode")))))
                // 挂墙发车倒数器的读数方向：现场翻，不用重编发版。
                .then(ClientCommandManager.literal("dep")
                        .executes(context -> depNext(context.getSource()))
                        .then(ClientCommandManager.literal("status")
                                .executes(context -> depStatus(context.getSource())))
                        .then(ClientCommandManager.literal("next")
                                .executes(context -> depNext(context.getSource())))
                        .then(ClientCommandManager.literal("normal")
                                .executes(context -> depSet(context.getSource(), "normal")))
                        .then(ClientCommandManager.literal("mirror")
                                .executes(context -> depSet(context.getSource(), "mirror")))
                        .then(ClientCommandManager.literal("flip")
                                .executes(context -> depSet(context.getSource(), "flip")))
                        .then(ClientCommandManager.literal("rotate")
                                .executes(context -> depSet(context.getSource(), "rotate")))
                        // 取证开关：打开后每块牌子每秒打一行 [lte] dep: …（只在内存里，默认关）。
                        .then(ClientCommandManager.literal("debug")
                                .executes(context -> depDebugStatus(context.getSource()))
                                .then(ClientCommandManager.literal("status")
                                        .executes(context -> depDebugStatus(context.getSource())))
                                .then(ClientCommandManager.literal("on")
                                        .executes(context -> setDepDebug(context.getSource(), true)))
                                .then(ClientCommandManager.literal("off")
                                        .executes(context -> setDepDebug(context.getSource(), false)))
                                .then(ClientCommandManager.argument("state",
                                                StringArgumentType.word())
                                        .suggests((context, builder) -> builder
                                                .suggest("on")
                                                .suggest("off")
                                                .buildFuture())
                                        .executes(context -> setDepDebug(context.getSource(),
                                                "on".equalsIgnoreCase(
                                                        StringArgumentType.getString(context,
                                                                "state"))))))
                        .then(ClientCommandManager.argument("mode", StringArgumentType.word())
                                .suggests((context, builder) -> builder
                                        .suggest("normal")
                                        .suggest("mirror")
                                        .suggest("flip")
                                        .suggest("rotate")
                                        .buildFuture())
                                .executes(context -> depSet(context.getSource(),
                                        StringArgumentType.getString(context, "mode")))))
                .then(ClientCommandManager.literal("rain")
                        .executes(context -> rainStatus(context.getSource()))
                        .then(ClientCommandManager.literal("on")
                                .executes(context -> setRainHush(context.getSource(), true)))
                        .then(ClientCommandManager.literal("off")
                                .executes(context -> setRainHush(context.getSource(), false)))
                        .then(ClientCommandManager.literal("status")
                                .executes(context -> rainStatus(context.getSource())))
                        .then(ClientCommandManager.literal("force")
                                .executes(context -> force(context.getSource(), true))
                                .then(ClientCommandManager.argument("state", StringArgumentType.word())
                                        .suggests((context, builder) -> builder
                                                .suggest("on")
                                                .suggest("off")
                                                .buildFuture())
                                        .executes(context -> force(context.getSource(),
                                                !"off".equalsIgnoreCase(
                                                        StringArgumentType.getString(context,
                                                                "state")))))))
                .then(ClientCommandManager.literal("trains")
                        .executes(context -> trainsStatus(context.getSource()))
                        .then(ClientCommandManager.literal("status")
                                .executes(context -> trainsStatus(context.getSource())))
                        .then(ClientCommandManager.literal("on")
                                .executes(context -> setBuiltInTrains(context.getSource(), true)))
                        .then(ClientCommandManager.literal("off")
                                .executes(context -> setBuiltInTrains(context.getSource(), false)))
                        .then(ClientCommandManager.literal("reload")
                                .executes(context -> reloadTrains(context.getSource()))))
                // 车灯现状：光样式（光带 / 灯晕 / 两者）+ 真实方块光照（光柱已移除）。
                .then(ClientCommandManager.literal("flare")
                        .executes(context -> flareStatus(context.getSource()))
                        .then(ClientCommandManager.literal("status")
                                .executes(context -> flareStatus(context.getSource())))
                        // 光样式：band / flare / both —— 和界面「光样式」一行同一条写入路径。
                        .then(ClientCommandManager.literal("style")
                                .executes(context -> flareStatus(context.getSource()))
                                .then(ClientCommandManager.argument("style", StringArgumentType.word())
                                        .suggests((context, builder) -> builder
                                                .suggest("band")
                                                .suggest("flare")
                                                .suggest("both")
                                                .buildFuture())
                                        .executes(context -> setLightStyle(context.getSource(),
                                                StringArgumentType.getString(context, "style"))))))
                // 屏幕空间散射（车灯在空气里的光雾）：开关 / 状态。pass 本身在
                // dev.js.mtrheadlight.sss.ScatterPass，这里只改那一个布尔量并落盘。
                .then(ClientCommandManager.literal("scatter")
                        .executes(context -> scatterStatus(context.getSource()))
                        .then(ClientCommandManager.literal("status")
                                .executes(context -> scatterStatus(context.getSource())))
                        .then(ClientCommandManager.literal("on")
                                .executes(context -> setScatter(context.getSource(), true)))
                        .then(ClientCommandManager.literal("off")
                                .executes(context -> setScatter(context.getSource(), false)))
                        .then(ClientCommandManager.argument("state", StringArgumentType.word())
                                .suggests((context, builder) -> builder
                                        .suggest("on")
                                        .suggest("off")
                                        .buildFuture())
                                .executes(context -> setScatter(context.getSource(),
                                        !"off".equalsIgnoreCase(
                                                StringArgumentType.getString(context, "state"))))))
                // 可见探针：先回答"管道通不通"，再谈绑定。两个开关都只在内存里，不写 config，
                // 退出游戏/换世界即失效（它们不是玩家的设置项）。
                .then(ClientCommandManager.literal("debug")
                        .executes(context -> debugStatus(context.getSource()))
                        .then(ClientCommandManager.literal("spray")
                                .executes(context -> debugStatus(context.getSource()))
                                .then(ClientCommandManager.argument("state", StringArgumentType.word())
                                        .suggests((context, builder) -> builder
                                                .suggest("on")
                                                .suggest("off")
                                                .buildFuture())
                                        .executes(context -> setDebugSpray(context.getSource(),
                                                "on".equalsIgnoreCase(StringArgumentType.getString(
                                                        context, "state"))))))
                        // 致死链路自检：用我们自己的 TrainImpactDamageSource 打一记必死伤害。
                        // 死亡屏 + 文案只要是对的，这里就一定看得到；看不到就是链路问题，不是撞不死。
                        .then(ClientCommandManager.literal("killself")
                                .executes(context -> killSelf(context.getSource(), true))
                                .then(ClientCommandManager.literal("low")
                                        .executes(context -> killSelf(context.getSource(), false)))
                                .then(ClientCommandManager.literal("high")
                                        .executes(context -> killSelf(context.getSource(), true)))));
    }

    /**
     * {@code /mtr_lte debug killself [low|high]} —— 用 {@link CollisionImpact#debugKill} 对<b>服务端
     * 玩家实例</b>打一记必死伤害，验证死亡屏与死亡文案。
     *
     * <p>必须打服务端那个实例：血量、死亡、死亡消息广播都是服务端权威的，{@code ClientPlayerEntity}
     * 上 {@code damage()} 第一行就 {@code return false}（那是「红一下就好」的老毛病）。所以这条
     * 命令只在单人 / 局域网主机可用 —— 服务器不在本进程时它会明说，而不是假装成功。
     */
    private static int killSelf(final FabricClientCommandSource source, final boolean high) {
        final net.minecraft.client.MinecraftClient client =
                net.minecraft.client.MinecraftClient.getInstance();
        final net.minecraft.server.MinecraftServer server = client.getServer();
        if (server == null || client.player == null) {
            source.sendFeedback(Text.literal(
                    "LTE：killself 需要单人存档（服务器权威实例不在本进程）——"
                            + "多人请让服主装 LTE 后用 /mtr_lte debug killself"));
            return 0;
        }
        final java.util.UUID id = client.player.getUuid();
        // 命令跑在客户端线程；伤害要在服务端线程打，结果回传到客户端线程显示。
        server.execute(() -> {
            net.minecraft.server.network.ServerPlayerEntity target = null;
            try {
                target = server.getPlayerManager().getPlayer(id);
            } catch (Throwable ignored) {
                target = null;
            }
            final String report = CollisionImpact.debugKill(target, high, ServerConfig.get());
            LteLog.LOGGER.info(report);
            final String text = report;
            client.execute(() -> {
                try {
                    source.sendFeedback(Text.literal(text));
                } catch (Throwable ignored) {
                    // 玩家已经下线：日志里那行就够了。
                }
            });
        });
        return 1;
    }

    /**
     * {@code /mtr_lte trains [status|on|off|reload]} - 内置车型屏蔽（{@code disableBuiltInTrains}）
     * 的查看与即时重载。
     *
     * <p>{@code on} / {@code off} 先把开关写进活配置并落盘，然后<b>立刻</b>调
     * {@link BuiltInTrains#reloadNow()} 重建 {@code TrainClientRegistry}，所以这次切换不用重启、
     * 也不用等 tick 的冷却：{@code off} 会把 114 个内置车型原样注册回来，{@code on} 会立刻把没在
     * 用的内置车型从车厂选车面板里剔掉。{@code reload} 只按当前开关重建一次（改了 json 或想重试时用）。
     *
     * <p>重建本身是把 MTR 的车型表清空再注册一遍，第一次会把 100 多个车型的属性/模型重新建出来，
     * 所以它是一次有意的、玩家主动触发的操作，不会在 tick 里自动发生。
     */
    private static int trainsStatus(final FabricClientCommandSource source) {
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        source.sendFeedback(Text.literal("LTE：disableBuiltInTrains = " + cfg.disableBuiltInTrains
                + (cfg.disableBuiltInTrains
                        ? "（车厂里只剩资源包/附属车型在用到的内置车）"
                        : "（内置车型全部保留）")
                + " — /mtr_lte trains reload 立即重建注册表"));
        return 1;
    }

    private static int setBuiltInTrains(final FabricClientCommandSource source, final boolean on) {
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        cfg.disableBuiltInTrains = on;
        cfg.save();
        source.sendFeedback(Text.literal(BuiltInTrains.reloadNow()));
        return 1;
    }

    private static int reloadTrains(final FabricClientCommandSource source) {
        source.sendFeedback(Text.literal(BuiltInTrains.reloadNow()));
        return 1;
    }

    /**
     * {@code /mtr_lte rain} - 在车厢里不画雨：状态 / 总开关。
     *
     * <p>三个开关都在 {@code config/mtrlte.json} 里，这里改的是内存里的活配置并立刻落盘，和配置
     * 界面一模一样（下一次渲染就生效，不需要重启）。
     */
    private static int rainStatus(final FabricClientCommandSource source) {
        source.sendFeedback(Text.literal("LTE：rain " + RainHush.status()));
        return 1;
    }

    private static int setRainHush(final FabricClientCommandSource source, final boolean on) {
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        cfg.rainHush = on;
        cfg.save();
        source.sendFeedback(Text.literal("LTE：rainHush = " + on
                + (on ? "（在车厢内不画雨/雪）" : "（回到原版：车内也画雨）")));
        return 1;
    }

    /**
     * {@code /mtr_lte rain force on|off} - <b>调试</b>：无视几何判定，强制认作「在车内」。
     *
     * <p>用来在没有列车、没有车站的地方验证渲染路径本身（雨到底有没有被 cancel）。它不写盘，
     * 退出游戏即失效。
     */
    private static int force(final FabricClientCommandSource source, final boolean on) {
        RainHush.setForceInside(on);
        source.sendFeedback(Text.literal("LTE：rain force = " + on
                + (on ? "（强制在车内，雨的绘制被 cancel）" : "（恢复按车厢几何判定）")));
        return 1;
    }

    /** Prints the mode that is actually in effect (not just the raw field). */
    private static int report(final FabricClientCommandSource source) {
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        source.sendFeedback(Text.literal("LTE：headlightMode = " + cfg.resolvedMode()
                + "（AUTO 按夜晚/隧道/雨天，ALWAYS 常亮，OFF 不自动关灯）"));
        return 1;
    }

    /**
     * {@code /mtr_lte flare} - 车灯现在由什么点亮：灯晕（billboard flash）+ 真实方块光照。
     *
     * <p>光柱（体积锥 / raymarch 体积光 / 加粗壳层）已经整体移除：先按车型灯位算出每盏灯的世界坐标
     * （{@link TrainLamps}：模型 LIGHT 部件 → 内置灯位表 → 尺寸兜底），再在灯位画一张面向相机的
     * 光晕贴图，地面/墙上的亮光由 {@link BeamBlockLight} 写进区块网格的真实方块光照提供。
     * 所以这一行报告的是"灯位从哪来"和"方块光照有没有在工作"，不再是光柱的渲染路径。
     */
    private static int flareStatus(final FabricClientCommandSource source) {
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        final HeadlightConfig.Effective eff = cfg.effectiveFor(0L);
        source.sendFeedback(Text.literal("LTE：lightStyle = " + cfg.style()
                + "（光带=" + (eff.beamGlow ? "画" : "不画")
                + "，灯晕=" + (eff.beamFlare ? "画" : "不画")
                + "；用 /mtr_lte flare style band|flare|both 切换）"
                + " flareSize=" + String.format(java.util.Locale.ROOT, "%.2f",
                        cfg.flareSizeClamped())
                + " minPx=" + cfg.flareMinPxClamped()
                + " alpha=" + String.format(java.util.Locale.ROOT, "%.2f", cfg.beamAlpha)
                + " blockLight=" + (cfg.beamBlockLight ? "on" : "off")
                + " radius=" + cfg.beamBlockLightRadius
                + " — 灯位：模型 LIGHT 部件 / builtin_lights.json / 尺寸兜底，"
                + "逐车型诊断行见 [lte] lights:，每 5 秒汇总见 [lte] flare:"));
        if (!BeamBlockLight.alive()) {
            source.sendFeedback(Text.literal("LTE：方块光照挂钩没有运行（"
                    + (BeamRelight.available() ? "区块网格重建被别的模组接管" : "Sodium/Embeddium 在线")
                    + "）—— 灯晕照常画，地面的亮斑不会出现"));
        }
        return 1;
    }

    /**
     * {@code /mtr_lte flare style band|flare|both} - 车灯<b>可见样式</b>：光带 / 灯晕 / 两者都画。
     *
     * <p>和配置界面「光源 → 光样式」一行走同一条路径（{@link HeadlightConfig#setLightStyle}），
     * 所以两种入口不会写出不同的状态：样式决定"画哪一套几何"，两份逐层旧开关
     * （{@code beamGlow} / {@code beamFlare}）跟着它一起写。颜色、亮度、灯位、长度是<b>同一份</b>
     * 配置，切换样式只改画法，不改灯本身 —— 这也是为什么光带和灯晕可以互为备份。
     *
     * <p>立即生效（渲染每帧读配置），并写盘，和 {@code /mtr_lte headlight} 一样。
     */
    private static int setLightStyle(final FabricClientCommandSource source, final String arg) {
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        final HeadlightConfig.LightStyle style;
        if ("band".equalsIgnoreCase(arg)) {
            style = HeadlightConfig.LightStyle.BAND;
        } else if ("flare".equalsIgnoreCase(arg)) {
            style = HeadlightConfig.LightStyle.FLARE;
        } else if ("both".equalsIgnoreCase(arg)) {
            style = HeadlightConfig.LightStyle.BOTH;
        } else {
            source.sendError(Text.literal("LTE：未知样式 " + arg + "（band / flare / both）"));
            return 0;
        }
        cfg.setLightStyle(style);
        cfg.save();
        source.sendFeedback(Text.literal("LTE：lightStyle = " + cfg.style()
                + (style == HeadlightConfig.LightStyle.BAND ? "（光带）"
                : style == HeadlightConfig.LightStyle.FLARE ? "（灯晕）" : "（光带 + 灯晕）")));
        return 1;
    }

    /**
     * {@code /mtr_lte scatter} - 屏幕空间散射（车灯在空气里的光雾）的状态。
     *
     * <p>这一行同时回答三个问题：开关开没开（{@code scatter on|off}）、shader 程序到底编译出来没有
     * （{@code program=ok|missing|failed(...)}）、以及上一帧真的往 shader 里送了几盏灯
     * （{@code lights=n/16}）。{@code program=failed(...)} 后面跟的就是加载失败的原因，和日志里那条
     * WARN 是同一个字符串；{@code depth=none} 说明还没有任何一帧有灯（pass 不建深度拷贝）。
     *
     * <p>开光影时这一行会多一句「shader pack active: pass skipped」—— 那不是故障，是设计：光影
     * 自己拥有 framebuffer 与深度缓冲，我们再去读会拿到一张错的深度图。
     */
    private static int scatterStatus(final FabricClientCommandSource source) {
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        source.sendFeedback(Text.literal("LTE：" + dev.js.mtrheadlight.sss.ScatterPass.status(cfg)
                + " — /mtr_lte scatter on|off，强度/半径在 配置 → 光源 → 空气散射"));
        return 1;
    }

    /** {@code /mtr_lte scatter on|off} - 写活配置 + 落盘，下一帧生效（和配置界面同一条路径）。 */
    private static int setScatter(final FabricClientCommandSource source, final boolean on) {
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        cfg.scatterEnabled = on;
        cfg.save();
        source.sendFeedback(Text.literal("LTE：scatter = " + (on ? "on" : "off")
                + (on ? "（车灯会在空气里散出光雾；没有灯时零开销）"
                        : "（不再运行全屏散射 pass）")));
        return 1;
    }

    /**
     * {@code /mtr_lte debug} - 可见探针的状态。
     *
     * <p>探针只服务于雨雾粒子（{@code /mtr_lte debug spray}）：车灯这一侧现在没有需要探针的
     * 渲染路径了 —— 灯晕是一张加色四边形，方块光照走原版区块网格，两者都没有"能不能画出来"
     * 的中间层。灯位对不对看 5 秒一行的 {@code [lte] flare:} 与每车型一次的 {@code [lte] lights:}。
     */
    private static int debugStatus(final FabricClientCommandSource source) {
        source.sendFeedback(Text.literal("LTE：debug " + DebugProbe.status()
                + " — spray 探针：眼前 2 格眼平的一圈 FLAME，5 秒"
                + culledHint()));
        return 1;
    }

    /** 被 Sodium Extra 逐类型屏蔽时补一句——否则"探针也看不见"会被误读成渲染管道坏了。 */
    private static String culledHint() {
        final String reason = ParticleCull.reason();
        return reason == null ? "" : "；⚠ " + reason;
    }

    /**
     * {@code /mtr_lte debug spray on|off} - 眼前 2 格、眼平高度的一圈 {@code FLAME}（8 颗/tick，
     * 半径 0.45–0.8）+ 一颗贴在眼睛前 0.8 格的对照，持续 5 秒。水雾的真实路径需要"在车厢里 +
     * 下雨 + 车速"，探针把这三条全部绕开，只看粒子系统本身；四条通道的计数见
     * {@code [lte] debug spray:} 那行（{@code eye/self/cloudOld/pmDirect/airSkipped/pm/culled}）。
     *
     * <p>另外这 5 秒内雨天水雾的<b>露天判定被强制忽略</b>（{@link RainSpray} 读
     * {@link DebugProbe#sprayEnabled()}）：在隧道 / 车库里也能看到真实水雾，用来验证"隧道里不生成"
     * 是露天判定在起作用，而不是粒子管道坏了。探针结束即恢复。
     */
    private static int setDebugSpray(final FabricClientCommandSource source, final boolean on) {
        final int ticks = DebugProbe.setSpray(on);
        source.sendFeedback(Text.literal("LTE：spray probe = " + (on ? "on" : "off")
                + (on ? "（眼前 2 格 · 眼平 · 每 tick " + DebugProbe.SPRAY_PER_TICK + " 颗 FLAME，"
                        + (ticks / 20) + " 秒；这 " + (ticks / 20) + " 秒内雨天水雾忽略露天限制）" : "")
                + culledHint()));
        return 1;
    }

    /**
     * {@code /mtr_lte beam3d [车型id]} - 打开 3D 光束编辑器。
     *
     * <p>客户端指令在客户端主线程上执行，但换屏仍然走 {@code client.send(...)} 延迟一帧，和
     * {@link MtrHeadlightClient#openConfigScreen} 同一条已验证的路：指令是在聊天界面的按键回调里
     * 跑的，那一帧的输入处理（聊天界面关闭 / 重新聚焦）仍可能把刚装上的屏幕换掉，玩家看到的
     * 就是「什么都没发生」。延迟到下一帧时聊天界面已经收尾完毕。
     *
     * <p>不带车型时由编辑器自己选「世界里在用的第一辆」，再退回资源包第一辆。任何异常都写进
     * 聊天框（红字，异常类名 + 首行）并打完整栈—— 一个打不开的编辑器不能安静地什么都不做。
     */
    private static int openBeamEditor(final String vehicleId) {
        final net.minecraft.client.MinecraftClient client =
                net.minecraft.client.MinecraftClient.getInstance();
        try {
            client.send(() -> {
                try {
                    client.setScreen(new BeamEditor3DScreen(client.currentScreen, vehicleId));
                } catch (Throwable t) {
                    reportBeamEditorFailure(t);
                }
            });
        } catch (Throwable t) {
            reportBeamEditorFailure(t);
        }
        return 1;
    }

    /** 打开编辑器失败：聊天框红字（异常类名 + 首行）+ 日志完整栈。 */
    private static void reportBeamEditorFailure(final Throwable t) {
        LteLog.LOGGER.error("[lte] beam3d: could not open the editor", t);
        try {
            final net.minecraft.client.MinecraftClient client =
                    net.minecraft.client.MinecraftClient.getInstance();
            client.inGameHud.getChatHud().addMessage(
                    Text.literal("LTE: /mtr_lte beam3d 打开失败 — " + firstLine(t))
                            .formatted(net.minecraft.util.Formatting.RED));
        } catch (Throwable ignored) {
            // 聊天框不可用（例如还在加载）时只剩日志。
        }
    }

    /** {@code Throwable.toString()} 的首行：类名 + message，不带多行栈。 */
    private static String firstLine(final Throwable t) {
        final String text = t == null ? "unknown" : t.toString();
        final int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline);
    }

    // ------------------------------------------------------------------ 挂墙发车倒数器的方向

    /**
     * {@code /mtr_lte dep} - 切到下一档读数方向（{@code normal → mirror → flip → rotate → …}）。
     *
     * <p>这块牌子的数字是几何画的，方向只可能是「整体转过去」的四种组合之一；每切一次都在
     * 聊天栏报当前档位，改完下一帧就生效，不需要重编、不需要重进世界。写进
     * {@code config/mtrlte-dep-orient.json}，重启也还在。
     */
    private static int depNext(final FabricClientCommandSource source) {
        final DepOnWallOrient.Mode mode = DepOnWallOrient.cycle();
        source.sendFeedback(Text.literal("LTE：倒数器方向 → " + mode.id() + "（" + mode.label()
                + "）— 屏幕面积不变，只有数字的朝向变；再执行一次 /mtr_lte dep 继续翻"));
        return 1;
    }

    /** {@code /mtr_lte dep status} - 当前档位 + 下一档 + 用法。 */
    private static int depStatus(final FabricClientCommandSource source) {
        source.sendFeedback(Text.literal(DepOnWallOrient.statusLine()
                + "；取证：" + (DepOnWallDebug.enabled() ? "on" : "off")
                + "（/mtr_lte dep debug on|off）"));
        return 1;
    }

    /** {@code /mtr_lte dep <normal|mirror|flip|rotate>} - 点名切到某一档。 */
    private static int depSet(final FabricClientCommandSource source, final String raw) {
        final DepOnWallOrient.Mode mode = DepOnWallOrient.Mode.byId(raw);
        if (mode == null) {
            source.sendFeedback(Text.literal("LTE：不认识的方向 \"" + raw
                    + "\"，可用：normal / mirror / flip / rotate"));
            return 0;
        }
        DepOnWallOrient.set(mode);
        source.sendFeedback(Text.literal("LTE：倒数器方向 = " + mode.id() + "（" + mode.label() + "）"));
        return 1;
    }

    /**
     * {@code /mtr_lte dep debug [on|off|status]} - 倒数器的<b>取证日志</b>。
     *
     * <p>打开后每块牌子每秒最多一行 {@code [lte] dep: …}，把这一帧的
     * {@code platform / entries / firstArrival / now / sec / dwell / dwellHalf / remain / phase /
     * lockArrival} 原样打出来 —— 「倒到 2 突然变 5」到底是因为<b>条目换了</b>（{@code firstArrival}
     * 变了 / 和 {@code lockArrival} 不一致）、<b>dwell 变了</b>，还是<b>时钟跳了</b>
     * （{@code now} 倒退），一眼就能分开。
     *
     * <p>只在内存里，不写 config：退出游戏即失效（和 {@code debug cone|spray} 一样，它不是玩家的设置项）。
     */
    private static int depDebugStatus(final FabricClientCommandSource source) {
        source.sendFeedback(Text.literal(DepOnWallDebug.statusLine()));
        return 1;
    }

    /** {@code /mtr_lte dep debug on|off} - 切取证开关，立刻生效（下一帧就开始打点）。 */
    private static int setDepDebug(final FabricClientCommandSource source, final boolean on) {
        DepOnWallDebug.set(on);
        source.sendFeedback(Text.literal("LTE：倒数器取证 = " + (on ? "on" : "off")
                + (on
                        ? "（每块牌子每秒一行 [lte] dep: …，看 firstArrival / sec / dwell / phase）"
                        : "（已停止打点）")));
        return 1;
    }

    /** Normalises and stores the mode, persists it, and echoes back what was accepted. */
    private static int set(final FabricClientCommandSource source, final String raw) {
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        final String mode = HeadlightConfig.normalizeHeadlightMode(raw);
        cfg.headlightMode = mode;
        cfg.save();
        final boolean accepted = mode.equalsIgnoreCase(raw.trim());
        source.sendFeedback(Text.literal("LTE：headlightMode = " + mode
                + (accepted ? "" : "（\"" + raw + "\" 不是合法值，已回落 AUTO）")));
        return accepted ? 1 : 0;
    }
}
