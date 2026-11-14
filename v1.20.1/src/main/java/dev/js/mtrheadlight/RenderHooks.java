package dev.js.mtrheadlight;

import dev.js.mtrheadlight.signal.ConnectorNodeHighlight;
import dev.js.mtrheadlight.sss.ScatterPass;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

/**
 * Version-local hook for 1.20.1: the world-render callbacks (self-drawn light beams) and the key
 * binding, plus the throttled rail relight scheduler and the rain-spray tick.
 */
public final class RenderHooks {
    /**
     * 上一次打过的光样式三元组（{@code style/band/flare}）。只在变化时打一行，所以稳态下这段
     * 逻辑每帧只做一次字符串比较，日志里也不会刷屏。
     */
    private static String lastStyleTrace;

    private RenderHooks() {
    }

    /**
     * 一行取证：<b>写进配置的样式</b> 与 <b>渲染层这一帧实际用的两个布尔</b>。
     *
     * <p>为什么要专门打这一行：三档切换"看不出区别"有三种完全不同的可能 —— ①控件没写进配置；
     * ②写进去了但 {@code beamGlow} / {@code beamFlare} 这两个逐层旧开关把样式盖掉；③两个布尔都对，
     * 是几何自己不可见（灯晕的 facing 反号就是这种）。这一行把 ①② 和 ③ 当场分开：
     * <pre>
     * [lte] lightstyle: applied style=FLARE band=false flare=true (beamGlow=false beamFlare=true)
     * </pre>
     * {@code band=} / {@code flare=} 用的就是 {@link HeadlightConfig#bandGlobal()} /
     * {@link HeadlightConfig#flareGlobal()} —— 与 {@code BeamGlow.render} / {@code LightFlare.render}
     * 的帧级门槛是<b>同一次调用</b>，不是第二份判断。玩家切档时日志里多一行、数字变了，说明前两步
     * 都是通的；数字没变就是写入或同步那一步的问题。
     */
    private static void traceLightStyle(final HeadlightConfig cfg) {
        if (cfg == null) {
            return;
        }
        final boolean band = cfg.bandGlobal();
        final boolean flare = cfg.flareGlobal();
        final String key = cfg.style() + "|" + band + "|" + flare;
        if (key.equals(lastStyleTrace)) {
            return;
        }
        lastStyleTrace = key;
        MtrHeadlightClient.LOGGER.info(
                "[lte] lightstyle: applied style={} band={} flare={} (beamGlow={} beamFlare={})",
                cfg.style(), Boolean.valueOf(band), Boolean.valueOf(flare),
                Boolean.valueOf(cfg.beamGlow), Boolean.valueOf(cfg.beamFlare));
    }

    public static void init() {
        FangsuCompat.announce();

        // 自注册的水雾粒子 mtrlte:spray。必须在任何粒子生成之前注册（工厂注册是「先到先得」，
        // 而且 ParticleManager 建好之后再注册也要走 Fabric 的直连路径）—— 放在这里是因为
        // RenderHooks.init() 是 1.20.1 自己的客户端入口，不牵动 common / fabric.mod.json。
        try {
            SprayType.init();
        } catch (Throwable t) {
            // init() 内部已经兜住一切；这里是第二层保险，绝不能让粒子注册拖垮客户端启动。
            MtrHeadlightClient.LOGGER.warn("[lte] spray particle registration failed", t);
        }

        // Rain spray runs on the client tick, registered AFTER MtrHeadlightClient's own handler, so
        // LightController has already built this tick's TrainPose and ofShared() is a cache hit.
        try {
            ClientTickEvents.END_CLIENT_TICK.register(client -> RainSpray.tick());
        } catch (Throwable t) {
            MtrHeadlightClient.LOGGER.warn("[lte] could not register the rain spray tick", t);
        }

        // 可见探针 + 5 秒绑定诊断行。挂在 tick 上而不是 LightController 里：后者在 enabled=false
        // 时提前 return，而探针恰恰要在"整个模组看起来没工作"时还能跑、还能打日志。
        try {
            ClientTickEvents.END_CLIENT_TICK.register(client -> DebugProbe.tick());
        } catch (Throwable t) {
            MtrHeadlightClient.LOGGER.warn("[lte] could not register the debug probe tick", t);
        }

        // 车灯的两套可见几何：光带（BeamGlow，贴地带状面片）和灯晕（LightFlare，灯位加色 billboard）。
        //
        // 画哪一套由 config.lightStyle 决定（BAND / FLARE / BOTH，默认 BAND）。这里 <b>故意两个都无
        // 条件调</b>，判定全部在各自的 render 里，两边读的是同一对方法：
        //   * BeamGlow.render  帧级   cfg.beamAnySiding()   -> bandGlobal() = bandStyle() && beamGlow
        //                      逐车   eff.beamGlow          -> 同上（侧线可覆盖）
        //   * LightFlare.render 帧级  cfg.flareAnySiding()  -> flareGlobal() = flareStyle() && beamFlare
        //                      逐车   eff.beamFlare         -> 同上（侧线可覆盖）
        // 所以在 RenderHooks 里再加一道 if 只会制造第二个真相来源——这里 <b>只负责给两个渲染器各一次
        // 机会</b>，并打一行 traceLightStyle() 把"渲染层实际用的两个布尔"写进日志：
        //   * 早/晚两趟回调仍然成对存在（灯晕只认晚趟：早趟之后世界还要画一大段几何，加色四边形
        //     会被后画的东西盖掉 —— 见 LightFlare.render 的说明；光带仍按 ShaderPackProbe 决定哪趟画）；
        //   * 两个渲染器各自 begin/end 自己的顶点缓冲、各自复位 depthMask / blendFunc / cull，
        //     所以 BOTH 叠加时不会重复写面片，也不会把对方的 GL 状态清掉；
        //   * 光带先画、灯晕后画：灯晕是加色贴图，压在上面才是"灯亮着"的样子。
        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            traceLightStyle(MtrHeadlightClient.config());
            try {
                BeamGlow.render(context, false);
            } catch (Throwable t) {
                MtrHeadlightClient.LOGGER.error("[lte] beam glow failed", t);
            }
            try {
                LightFlare.render(context, false);
            } catch (Throwable t) {
                MtrHeadlightClient.LOGGER.error("[lte] light flare failed", t);
            }
        });
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> {
            try {
                BeamGlow.render(context, true);
            } catch (Throwable t) {
                MtrHeadlightClient.LOGGER.error("[lte] beam glow (late pass) failed", t);
            }
            try {
                LightFlare.render(context, true);
            } catch (Throwable t) {
                MtrHeadlightClient.LOGGER.error("[lte] light flare (late pass) failed", t);
            }
        });

        // 屏幕空间散射（车灯在空气里的光雾）。两条钩子：
        //  * END_CLIENT_TICK 只做一次「把 shader 编译出来」——发生在标题画面，所以 shader 编译错误
        //    在进世界之前就写在日志里，而且玩家的 GL 上下文由客户端线程持有；
        //  * LAST 才是真正的 pass：世界已经画完、GUI 还没开始，主 framebuffer 仍绑着，深度还是
        //    这一帧的深度。没有点亮的灯时它直接返回，不建 framebuffer、不拷深度、不绑程序。
        try {
            ClientTickEvents.END_CLIENT_TICK.register(ScatterPass::tick);
        } catch (Throwable t) {
            MtrHeadlightClient.LOGGER.warn("[lte] could not register the scatter shader probe", t);
        }
        WorldRenderEvents.LAST.register(context -> {
            try {
                ScatterPass.render(context);
            } catch (Throwable t) {
                MtrHeadlightClient.LOGGER.error("[lte] scatter: pass threw", t);
            }
        });
        // 退出时把程序和深度纹理还回去：它们不在原版 ProgramManager 的清单里，没人替我们关。
        try {
            ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ScatterPass.shutdown());
        } catch (Throwable t) {
            MtrHeadlightClient.LOGGER.warn("[lte] could not register the scatter shutdown hook", t);
        }

        // 手持任意一个连接器（信号 / 投影机 / PIDS）时在附近轨道节点上画轮廓（白色；
        // 信号连接器已选中的那颗青色）。三个物品共用同一个绘制回调，一帧一次 draw。纯粹是辅助显示。
        try {
            WorldRenderEvents.AFTER_TRANSLUCENT.register(ConnectorNodeHighlight::render);
        } catch (Throwable t) {
            MtrHeadlightClient.LOGGER.warn("[lte] could not register the node highlight", t);
        }

        try {
            KeyHooks.init();
        } catch (Throwable t) {
            MtrHeadlightClient.LOGGER.warn("[lte] could not register the key binding", t);
        }

        // Rail lighting itself is baked into ANTE's rail models by the mixins; the scheduler only
        // decides when a section has to be rebuilt. It is a no-op when ANTE is missing.
        try {
            RailRelightScheduler.install();
        } catch (Throwable t) {
            // ANTE/NTE missing or too old: rail baking stays off, the rest keeps working.
        }

        // Real block light from the beams into vanilla terrain vertices (prototype, off by
        // default). Registered after MtrHeadlightClient's own tick handler so LightController has
        // already published this tick's beams by the time the sweeper runs.
        try {
            BeamRelight.install();
            ClientTickEvents.END_CLIENT_TICK.register(client -> {
                try {
                    BeamRelight.tick(MtrHeadlightClient.config());
                } catch (Throwable t) {
                    MtrHeadlightClient.LOGGER.error("[lte] beam block light tick failed", t);
                }
            });
        } catch (Throwable t) {
            MtrHeadlightClient.LOGGER.warn("[lte] beam block light not installed", t);
        }
    }
}
