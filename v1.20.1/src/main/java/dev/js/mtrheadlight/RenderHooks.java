package dev.js.mtrheadlight;

import dev.js.mtrheadlight.signal.ConnectorNodeHighlight;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

/**
 * Version-local hook for 1.20.1: the world-render callbacks (self-drawn light beams) and the key
 * binding, plus the throttled rail relight scheduler and the rain-spray tick.
 */
public final class RenderHooks {
    private RenderHooks() {
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

        // The beam sheets are our own geometry and completely independent of the relight
        // scheduler: they draw themselves and never queue a chunk. The early/late pair exists so
        // exactly one pass draws, depending on whether a shader pack composites after translucent.
        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            try {
                BeamGlow.render(context, false);
            } catch (Throwable t) {
                MtrHeadlightClient.LOGGER.error("[lte] beam glow failed", t);
            }
            // PROTOTYPE: cone verification pass, same two-pass rule as the sheet.
            try {
                ProtoBeamCone.render(context, false);
            } catch (Throwable t) {
                MtrHeadlightClient.LOGGER.error("[lte-proto] cone failed", t);
            }
        });
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> {
            try {
                BeamGlow.render(context, true);
            } catch (Throwable t) {
                MtrHeadlightClient.LOGGER.error("[lte] beam glow (late pass) failed", t);
            }
            try {
                ProtoBeamCone.render(context, true);
            } catch (Throwable t) {
                MtrHeadlightClient.LOGGER.error("[lte-proto] cone (late pass) failed", t);
            }
        });
        ProtoBeamCone.announce();

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
