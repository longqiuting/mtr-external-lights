package dev.js.mtrheadlight;

import net.fabricmc.loader.api.FabricLoader;

/**
 * One-off compatibility note for the 方速 (fangsu) MTR addon.
 *
 * <p>Checked here rather than assumed, because the two mods touch the same rail pipeline:
 *
 * <ul>
 *   <li><b>fangsu</b> - {@code fangsu.mixins.json} lists {@code NteBakedRailMixin},
 *       {@code NteRailLayersMixin}, {@code LevelRendererMixin}, {@code RailGeometryMixin}. The two
 *       NTE-facing ones inject into {@code RailRenderDispatcher#getModelKeyForRender},
 *       {@code #registerRail}, {@code #addRail}, {@code #removeRail}, {@code #clearRail} and into
 *       {@code BakedRail#<init>} - it adds <em>extra model layers</em> for rails whose key selects
 *       more than one model.</li>
 *   <li><b>this mod</b> - redirects {@code World#getLightLevel} inside
 *       {@code MeshBuildingRailChunk#rebuildBuffer} / {@code InstancedRailChunk#rebuildBuffer} and
 *       calls {@code RailRenderDispatcher#registerLightUpdate}.</li>
 * </ul>
 *
 * <p>Those sets are disjoint: fangsu never touches {@code rebuildBuffer}, {@code getLightLevel} or
 * {@code registerLightUpdate}, so there is no @Redirect/@Inject collision to arbitrate and no mixin
 * priority to win. The layering does interact in one useful direction - fangsu builds its extra
 * chunks by reflectively calling the {@code (Long, String)} constructor of the very same
 * {@code InstancedRailChunk}/{@code MeshBuildingRailChunk} classes and puts them into
 * {@code railChunkMap}/{@code railChunkList}, so our class-level mixin applies to them too and
 * {@code registerLightUpdate} (which walks every model key) marks them dirty as well.
 *
 * <p><b>The hand-built turnout.</b> {@code TurnoutBlockEntityRenderer} draws the switch OBJ itself,
 * outside ANTE's bake: the vanilla {@code light} parameter travels through
 * {@code BaseBlockEntityRender#render} into {@code EyeCandyDrawCalls} and finally into
 * {@code VertexConsumer.light(int)} once per mesh, while the whole chain never calls
 * {@code World#getLightLevel}. So the switch area kept the block-position ambient lightmap and
 * neither our beams nor LambDynamicLights reached it.
 *
 * <p>That gap is now closed by a <em>fully defensive</em> mixin (1.20.1 only), on the addon author's
 * invitation: {@code dev.js.mtrheadlight.mixin.fangsu.FangsuTurnoutRenderMixin} rewrites the
 * {@code light} argument of {@code TurnoutBlockEntityRenderer#render}'s super call
 * ({@code @ModifyArgs}, argument 4) with the dynamically lit value from
 * {@link FangsuTurnoutLight}. Nothing references a {@code com.fangsu.*} name at compile time - the
 * target is a string, the injection is {@code require = 0}, the config is {@code required: false}
 * behind {@link dev.js.mtrheadlight.mixin.FangsuMixinPlugin} (a {@code Class.forName} probe), and all
 * logic sits in {@link FangsuTurnoutLight} behind {@code try/catch (Throwable)}. A fangsu update that
 * moves that injection point therefore degrades to "turnout stays on ambient light", never to a
 * crash. See {@code docs/rail-light-plan.md} section 4 for the bytecode evidence.
 *
 * <p>Nothing is disabled when fangsu is present; the log line exists so a support log shows the
 * combination instead of leaving it to guesswork. Detection here is by mod id only - the mixin path
 * uses its own class probe and is the only place that names a fangsu class, and it names it as a
 * string.
 *
 * <p><b>交通灯（{@code fangsu:traffic_light}）不做绑定。</b>它继承 {@code BaseObjBlock}
 * （= {@code HorizontalFacingBlock}）而不是 {@code BlockSignalLightBase}，红绿灯颜色来自
 * {@code BlockEntityTrafficLight.lightState}，只由积木程序（{@code dispType=program}）写；
 * 「MTR 信号联动」那一档在 1.3.2 里是空实现（{@code whenRendering} 只在 {@code "program"} 时
 * 调 {@code drive()}），方速内部唯一相关的 {@code MinecraftClientUtil#getOccupiedAspect}
 * 以灯自身坐标为起点且无调用点。右键时由 {@code signal.FangsuSignalCompat} 认出来并给出提示，
 * 完整字节码证据见 {@code docs/report-2026-11-10-fangsu-signal-and-node-marks.md}。
 */
public final class FangsuCompat {
    /** Fangsu's mod id in its {@code fabric.mod.json}. */
    private static final String MOD_ID = "fangsu";

    private FangsuCompat() {
    }

    /** True when the 方速 addon is loaded (multi-layer rail models may be in use). */
    public static boolean present() {
        try {
            return FabricLoader.getInstance().isModLoaded(MOD_ID);
        } catch (Throwable t) {
            // Loader API missing/older: report "not present" and move on.
            return false;
        }
    }

    /** Logs once at startup; never called from a render or tick path. */
    public static void announce() {
        try {
            if (present()) {
                MtrHeadlightClient.LOGGER.info(
                        "[lte] 方速 (fangsu) detected: its extra rail model layers are built "
                                + "from ANTE's own rail chunk classes, so the light redirect and "
                                + "registerLightUpdate cover them; the hand-built turnout OBJ is lit by "
                                + "the optional fangsu mixins (FangsuTurnoutRenderMixin + "
                                + "FangsuBaseRenderLightMixin, require=0)");
                MtrHeadlightClient.LOGGER.info(
                        "[lte] 方速交通灯 fangsu:traffic_light 不参与信号连接器绑定：它的 MTR 信号联动档"
                                + "（dispType=mtr）在 1.3.2 里没有实现，唯一相关的 API "
                                + "MinecraftClientUtil#getOccupiedAspect(pos, angle, depth) 也以灯自身坐标"
                                + "为起点、全 jar 无调用点；右键时会给出提示。证据见 "
                                + "docs/report-2026-11-10-fangsu-signal-and-node-marks.md");

            }
        } catch (Throwable t) {
            // Diagnostics must never break startup.
        }
    }
}
