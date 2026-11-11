package dev.js.mtrheadlight;

import net.minecraft.text.LiteralText;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.js.mtrheadlight.collision.CollisionImpact;
import dev.js.mtrheadlight.mtr.BuiltInTrains;
import dev.js.mtrheadlight.server.ServerConfig;
import net.fabricmc.fabric.api.client.command.v1.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v1.FabricClientCommandSource;
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
 * <p>{@code /mtr_lte debug cone|spray on|off} - the two <b>visible probes</b> (a fixed white cone in
 * front of the player, a fixed particle burst at the feet). They ignore trains, models, siding
 * overrides and the master switch, so a probe that fails to appear localises the fault to the
 * render path itself instead of the lamp binding.
 *
 * <p>{@code /mtr_lte debug coneSource model|glow|status} - the cone's <b>geometry source</b>.
 * {@code model} (default) binds the cone to the train model's lamp slots ({@link VehicleLights});
 * {@code glow} makes it take the very geometry the visible light sheet draws
 * ({@link BeamGeom} + {@link BeamGlow#startHeight}) and read no lamp data at all. Cone visible in
 * {@code glow} = the binding was wrong; cone still invisible = the cone's own draw path cannot
 * render a real beam. Live, persisted to {@code config/mtrlte.json}.
 *
 * <p>{@code /mtr_lte cone volume on|off} - the cone's <b>render path</b>. {@code off} (default) =
 * every real beam is built as the additive four-shell cone and drawn through
 * {@link GlowCompat#draw}, the same function {@code /mtr_lte debug cone} proved visible;
 * {@code on} = the 1.20.1 raymarch volume (occlusion-aware). Live, persisted.
 *
 * <p>{@code /mtr_lte cone tail on|off} - whether the cone is also drawn out of the <b>tail</b> end.
 * {@code off} (default) = head lamp only; the tail slot is still resolved for the dynamic light and
 * the ground sheet. Live, persisted.
 */
public final class Commands {
    /** Current name. */
    private static final String NAME = "mtr_lte";
    /** Pre-rename name, kept as an alias for one release. */
    private static final String LEGACY_NAME = "externallights";

    private Commands() {
    }

    public static void register() {
        // 1.18.2 只有 client-command-api v1：没有 ClientCommandRegistrationCallback，
        // 直接往 ClientCommandManager.DISPATCHER 上挂（Fabric 在客户端指令树同步时自己收走）。
        final var dispatcher = ClientCommandManager.DISPATCHER;
        dispatcher.register(root(ClientCommandManager.literal(NAME)));
        dispatcher.register(root(ClientCommandManager.literal(LEGACY_NAME)));
        // 一行启动日志说明客户端指令树里到底有什么：/mtr_lte beam3d「什么都没发生」时，
        // 先确认这条字面量真的挂上了（false = 注册没走到，跟界面无关）。
        final var node = dispatcher.getRoot().getChild(NAME);
        LteLog.LOGGER.info("[lte] commands registered: /{} beam3d={}", NAME,
                Boolean.valueOf(node != null && node.getChild("beam3d") != null));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> root(
            final LiteralArgumentBuilder<FabricClientCommandSource> root) {
        return root
                .executes(context -> {
                    context.getSource().sendFeedback(new LiteralText("LTE：指令执行成功"));
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
                .then(ClientCommandManager.literal("cone")
                        .executes(context -> coneStatus(context.getSource()))
                        .then(ClientCommandManager.literal("status")
                                .executes(context -> coneStatus(context.getSource())))
                        .then(ClientCommandManager.literal("volume")
                                .executes(context -> coneStatus(context.getSource()))
                                .then(ClientCommandManager.argument("state",
                                                StringArgumentType.word())
                                        .suggests((context, builder) -> builder
                                                .suggest("on")
                                                .suggest("off")
                                                .buildFuture())
                                        .executes(context -> setConeVolume(context.getSource(),
                                                "on".equalsIgnoreCase(
                                                        StringArgumentType.getString(context,
                                                                "state"))))))
                        .then(ClientCommandManager.literal("occl")
                                .then(ClientCommandManager.argument("state", StringArgumentType.word())
                                        .suggests((context, builder) -> builder
                                                .suggest("on")
                                                .suggest("off")
                                                .buildFuture())
                                        .executes(context -> setConeOcclusion(context.getSource(),
                                                !"off".equalsIgnoreCase(
                                                        StringArgumentType.getString(context,
                                                                "state"))))))
                        // 尾灯光柱：默认关（只给头灯），这里是显式打开的唯一入口之一。
                        .then(ClientCommandManager.literal("tail")
                                .executes(context -> coneStatus(context.getSource()))
                                .then(ClientCommandManager.argument("state", StringArgumentType.word())
                                        .suggests((context, builder) -> builder
                                                .suggest("on")
                                                .suggest("off")
                                                .buildFuture())
                                        .executes(context -> setConeTail(context.getSource(),
                                                "on".equalsIgnoreCase(
                                                        StringArgumentType.getString(context,
                                                                "state"))))))
                        .then(ClientCommandManager.literal("steps")
                                .then(ClientCommandManager.argument("count",
                                                StringArgumentType.word())
                                        .executes(context -> setConeSteps(context.getSource(),
                                                StringArgumentType.getString(context, "count"))))))
                // 可见探针：先回答"管道通不通"，再谈绑定。两个开关都只在内存里，不写 config，
                // 退出游戏/换世界即失效（它们不是玩家的设置项）。
                .then(ClientCommandManager.literal("debug")
                        .executes(context -> debugStatus(context.getSource()))
                        // 光柱几何源对照：model = 车型灯位（原有行为），glow = 光带几何（不读灯位）。
                        .then(ClientCommandManager.literal("coneSource")
                                .executes(context -> coneSourceStatus(context.getSource()))
                                .then(ClientCommandManager.literal("status")
                                        .executes(context -> coneSourceStatus(context.getSource())))
                                .then(ClientCommandManager.literal("model")
                                        .executes(context -> setConeSource(context.getSource(),
                                                HeadlightConfig.CONE_SOURCE_MODEL)))
                                .then(ClientCommandManager.literal("glow")
                                        .executes(context -> setConeSource(context.getSource(),
                                                HeadlightConfig.CONE_SOURCE_GLOW)))
                                .then(ClientCommandManager.argument("mode",
                                                StringArgumentType.word())
                                        .suggests((context, builder) -> builder
                                                .suggest("model")
                                                .suggest("glow")
                                                .buildFuture())
                                        .executes(context -> setConeSource(context.getSource(),
                                                StringArgumentType.getString(context, "mode")))))
                        .then(ClientCommandManager.literal("cone")
                                .executes(context -> debugStatus(context.getSource()))
                                .then(ClientCommandManager.argument("state", StringArgumentType.word())
                                        .suggests((context, builder) -> builder
                                                .suggest("on")
                                                .suggest("off")
                                                .buildFuture())
                                        .executes(context -> setDebugCone(context.getSource(),
                                                "on".equalsIgnoreCase(StringArgumentType.getString(
                                                        context, "state"))))))
                        // 真实光柱的提亮对照：0.45 平铺、关掉全部衰减 = 探针参数。
                        // 开着的瞬间就看得见 → 原来是太淡；仍然看不见 → 问题不在 alpha。
                        .then(ClientCommandManager.literal("coneBoost")
                                .executes(context -> debugStatus(context.getSource()))
                                .then(ClientCommandManager.argument("state", StringArgumentType.word())
                                        .suggests((context, builder) -> builder
                                                .suggest("on")
                                                .suggest("off")
                                                .buildFuture())
                                        .executes(context -> setDebugConeBoost(context.getSource(),
                                                "on".equalsIgnoreCase(StringArgumentType.getString(
                                                        context, "state"))))))
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
            source.sendFeedback(new LiteralText(
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
                    source.sendFeedback(new LiteralText(text));
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
        source.sendFeedback(new LiteralText("LTE：disableBuiltInTrains = " + cfg.disableBuiltInTrains
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
        source.sendFeedback(new LiteralText(BuiltInTrains.reloadNow()));
        return 1;
    }

    private static int reloadTrains(final FabricClientCommandSource source) {
        source.sendFeedback(new LiteralText(BuiltInTrains.reloadNow()));
        return 1;
    }

    /**
     * {@code /mtr_lte rain} - 在车厢里不画雨：状态 / 总开关。
     *
     * <p>三个开关都在 {@code config/mtrlte.json} 里，这里改的是内存里的活配置并立刻落盘，和配置
     * 界面一模一样（下一次渲染就生效，不需要重启）。
     */
    private static int rainStatus(final FabricClientCommandSource source) {
        source.sendFeedback(new LiteralText("LTE：rain " + RainHush.status()));
        return 1;
    }

    private static int setRainHush(final FabricClientCommandSource source, final boolean on) {
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        cfg.rainHush = on;
        cfg.save();
        source.sendFeedback(new LiteralText("LTE：rainHush = " + on
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
        source.sendFeedback(new LiteralText("LTE：rain force = " + on
                + (on ? "（强制在车内，雨的绘制被 cancel）" : "（恢复按车厢几何判定）")));
        return 1;
    }

    /** Prints the mode that is actually in effect (not just the raw field). */
    private static int report(final FabricClientCommandSource source) {
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        source.sendFeedback(new LiteralText("LTE：headlightMode = " + cfg.resolvedMode()
                + "（AUTO 按夜晚/隧道/雨天，ALWAYS 常亮，OFF 不自动关灯）"));
        return 1;
    }

    /**
     * {@code /mtr_lte cone} - state of the depth-aware cone and its two knobs.
     *
     * <p>{@code volume=} is the live answer, not the config: it is {@code shaderpack} when Iris
     * switched the effect off, {@code shader-missing} when the core shader did not load, and
     * {@code failed(...)} when a GL step was rejected - in all three cases the shell cone draws.
     */
    private static int coneStatus(final FabricClientCommandSource source) {
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        source.sendFeedback(new LiteralText("LTE：cone path=" + ProtoBeamCone.pathName()
                + "（" + (cfg.beamConeVolume
                        ? "raymarch 体积光，配置已打开"
                        : "加色壳层锥，默认：和 debug cone 探针同一条管道")
                + "） occlusion=" + cfg.beamConeOcclusion
                + " steps=" + cfg.beamConeSteps()
                + " alpha=" + String.format(java.util.Locale.ROOT, "%.2f", cfg.beamConeAlpha)
                + " coneBoost=" + (DebugProbe.coneBoostEnabled() ? "on" : "off")
                + " tail=" + (cfg.coneOnTail ? "on" : "off")
                + " volume=" + ConeVolumeRenderer.status(cfg)));
        return 1;
    }

    /**
     * {@code /mtr_lte cone volume on|off} - 光柱的渲染路径。
     *
     * <p>{@code off}（默认）= 真实光束全部走加色壳层锥：四层同心壳、纵向/径向/视角衰减，
     * {@code GlowCompat.draw} 加色混合，和 {@code /mtr_lte debug cone} 探针同一个函数
     * （{@code ProtoBeamCone.cone()} → {@code quad()} → {@code GlowCompat.draw}）。<b>没有</b>深度遮挡。
     * {@code on} = 1.20.1 的 raymarch 体积光（可被墙/车体截断），shader pack / GL 失败时仍自动退回壳层。
     */
    private static int setConeVolume(final FabricClientCommandSource source, final boolean on) {
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        cfg.beamConeVolume = on;
        cfg.save();
        source.sendFeedback(new LiteralText("LTE：beamConeVolume = " + on
                + (on ? "（raymarch；若不可用/被 shader pack 关闭则自动退回壳层）"
                        : "（加色壳层锥 = 默认，与探针同一条管道）")
                + " path=" + ProtoBeamCone.pathName()));
        return 1;
    }

    /** A/B switch for the occlusion test - this is what the screenshot comparison flips. */
    private static int setConeOcclusion(final FabricClientCommandSource source, final boolean on) {
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        cfg.beamConeOcclusion = on;
        cfg.save();
        source.sendFeedback(new LiteralText("LTE：beamConeOcclusion = " + on
                + (on ? "（深度感知：光锥被墙截断）" : "（旧效果：不遮挡）")
                + " volume=" + ConeVolumeRenderer.status(cfg)));
        return 1;
    }

    /**
     * {@code /mtr_lte cone tail on|off} - 尾灯光柱的显式开关（默认 {@code off}）。
     *
     * <p>关：光柱只从<b>头灯</b>射出。车辆模型上的尾灯位照旧解析（动态尾灯 / 地面光带要用），
     * 所以改这一个开关不会让尾部的动态光或光带消失。
     */
    private static int setConeTail(final FabricClientCommandSource source, final boolean on) {
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        cfg.coneOnTail = on;
        cfg.save();
        source.sendFeedback(new LiteralText("LTE：coneOnTail = " + on
                + (on ? "（尾灯也画光柱）" : "（只给头灯；尾灯位仍用于动态光/光带）")));
        return 1;
    }

    private static int setConeSteps(final FabricClientCommandSource source, final String raw) {
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        final int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            source.sendFeedback(new LiteralText("LTE：steps 需要 4-16 的整数"));
            return 0;
        }
        cfg.beamConeSteps = value;
        cfg.save();
        source.sendFeedback(new LiteralText("LTE：beamConeSteps = " + cfg.beamConeSteps()
                + (value == cfg.beamConeSteps() ? "" : "（已钳制到 4-16）")));
        return 1;
    }

    /**
     * {@code /mtr_lte debug coneSource [model|glow|status]} - 光柱的几何来源状态。
     *
     * <p>这一行只说"当前按哪套几何出光柱"，和 5 秒诊断行里的 {@code source=} 是同一个值
     * （{@link ProtoBeamCone#sourceName()}），所以聊天栏和日志不会各说一套。
     */
    private static int coneSourceStatus(final FabricClientCommandSource source) {
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        final boolean glow = cfg.coneSourceGlow();
        source.sendFeedback(new LiteralText("LTE：beamConeSource = " + cfg.resolvedConeSource()
                + (glow
                        ? "（光柱用光带的几何：base/axis/len/颜色同一来源，不读 VehicleLights）"
                        : "（光柱按车型灯位 VehicleLights，原有行为）")
                + " — 诊断行 source=" + ProtoBeamCone.sourceName()
                + "，切换：/mtr_lte debug coneSource model|glow"));
        return 1;
    }

    /**
     * 切到 {@code MODEL} / {@code GLOW}：写活配置并落盘（和配置界面一致，下一帧就生效），并清掉
     * "每列车已打过一行"的记录，让下一帧重新逐车打印 {@code [lte] coneSource glow:} 对照行。
     *
     * <p>{@code GLOW} 不改任何别的参数：光柱仍然走原来的渲染路径（raymarch / 壳层），只是灯位
     * 来源换成光带那一套。这样"看得见/看不见"的差别只可能来自几何绑定。
     */
    private static int setConeSource(final FabricClientCommandSource source, final String raw) {
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        final String mode = HeadlightConfig.normalizeConeSource(raw);
        cfg.beamConeSource = mode;
        cfg.save();
        ProtoBeamCone.clearGlowLogged();
        final boolean accepted = mode.equalsIgnoreCase(raw.trim());
        final boolean glow = HeadlightConfig.CONE_SOURCE_GLOW.equals(mode);
        source.sendFeedback(new LiteralText("LTE：beamConeSource = " + mode
                + (accepted ? "" : "（\"" + raw + "\" 不是合法值，已回落 MODEL）")
                + (glow
                        ? "（光柱改用光带几何：应与光带逐像素重合；仍看不到 = 光柱渲染路径问题）"
                        : "（光柱按车型灯位；原有行为）")));
        return accepted ? 1 : 0;
    }

    /**
     * {@code /mtr_lte debug} - 两个可见探针的状态。
     *
     * <p>这一行是"先定位再修"的入口：{@code cone=on} 却看不到 = 渲染管道问题；{@code cone=on}
     * 看得见 = 管道没问题，去查 {@code beams=} / {@code slots=}（见 5 秒一行的 {@code [lte] debug:}）。
     */
    private static int debugStatus(final FabricClientCommandSource source) {
        source.sendFeedback(new LiteralText("LTE：debug " + DebugProbe.status()
                + " — cone 探针：玩家前方 6 格的大白光锥；coneBoost：真实光柱改用探针参数"
                + "（0.45 平铺、无衰减，用来判定\"是不是太淡\"）；spray 探针：眼前 2 格眼平的一圈 FLAME，5 秒"
                + culledHint()));
        return 1;
    }

    /** 被 Sodium Extra 逐类型屏蔽时补一句——否则"探针也看不见"会被误读成渲染管道坏了。 */
    private static String culledHint() {
        final String reason = ParticleCull.reason();
        return reason == null ? "" : "；⚠ " + reason;
    }

    /**
     * {@code /mtr_lte debug cone on|off} - 固定位置的亮光锥（玩家水平前方 6 格、与眼平齐，长 12、
     * 半角 20°、每层 alpha 0.45、纯白）。它不读列车 / 车型 / 侧线 / 配置开关，只走和真实光柱
     * 相同的加色壳层管道，所以"看不看得见"直接就是管道通不通的答案。
     */
    private static int setDebugCone(final FabricClientCommandSource source, final boolean on) {
        DebugProbe.setCone(on);
        source.sendFeedback(new LiteralText("LTE：cone probe = " + (on ? "on" : "off")
                + (on ? "（前方 6 格 · 眼平齐 · 长 12 · 半角 20° · alpha 0.45 · 纯白；"
                        + "不受任何列车/配置开关影响）" : "")));
        return 1;
    }

    /**
     * {@code /mtr_lte debug coneBoost on|off} - 把<b>真实光束</b>画成探针那套参数：峰值 alpha
     * 强制 0.45，纵向 / 径向 / 视角 / 地面四种衰减全部关掉（每层壳 0.45 平铺）。
     *
     * <p>它回答的是这次报告里唯一还没分开的两个可能：<b>太淡</b>还是<b>管道/几何不对</b>。
     * 打开后立刻看得见 = 之前是衰减把 alpha 乘没了（默认值已提到 0.30 并加了 0.25 下限）；
     * 依旧看不见 = 和 alpha 无关，去查 5 秒诊断行的 {@code path=} / {@code g7}（距离）和
     * {@code [lte] cone beam:} 那几行的 {@code apex}/{@code len}/{@code radius}。
     *
     * <p>只在内存里，不写配置；关掉即恢复真实参数。切换时 {@link ProtoBeamCone} 会清空每束
     * 一次的日志记录，所以开/关各会重新打一遍 {@code [lte] cone beam:}。
     */
    private static int setDebugConeBoost(final FabricClientCommandSource source, final boolean on) {
        DebugProbe.setConeBoost(on);
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        final double peak = cfg == null ? 0.30D : cfg.beamConeAlpha;
        source.sendFeedback(new LiteralText("LTE：coneBoost = " + (on ? "on" : "off")
                + (on
                        ? "（真实光束：alpha 0.45 平铺 · 无纵向/径向/视角/地面衰减 = 探针参数；"
                                + "看得见 = 之前太淡，看不见 = 与 alpha 无关）"
                        : "（恢复真实参数：beamConeAlpha=" + String.format(java.util.Locale.ROOT,
                                "%.2f", peak) + " · 衰减下限 0.25）")));
        return 1;
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
        source.sendFeedback(new LiteralText("LTE：spray probe = " + (on ? "on" : "off")
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
                    new LiteralText("LTE: /mtr_lte beam3d 打开失败 — " + firstLine(t))
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

    /** Normalises and stores the mode, persists it, and echoes back what was accepted. */
    private static int set(final FabricClientCommandSource source, final String raw) {
        final HeadlightConfig cfg = MtrHeadlightClient.config();
        final String mode = HeadlightConfig.normalizeHeadlightMode(raw);
        cfg.headlightMode = mode;
        cfg.save();
        final boolean accepted = mode.equalsIgnoreCase(raw.trim());
        source.sendFeedback(new LiteralText("LTE：headlightMode = " + mode
                + (accepted ? "" : "（\"" + raw + "\" 不是合法值，已回落 AUTO）")));
        return accepted ? 1 : 0;
    }
}
