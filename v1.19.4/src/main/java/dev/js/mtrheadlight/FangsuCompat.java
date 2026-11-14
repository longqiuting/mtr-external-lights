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
 * <p><b>交通灯（{@code fangsu:traffic_light}）可以绑定，但要两步。</b>它继承 {@code BaseObjBlock}
 * （= {@code HorizontalFacingBlock}）而不是 {@code BlockSignalLightBase}，红绿灯颜色来自
 * {@code BlockEntityTrafficLight.lightState}，只在 {@code dispType="program"} 时由积木程序经
 * {@code drive()} 写入 ——「MTR 信号联动」那一档在 1.3.2 里是<b>空实现</b>
 * （{@code whenRendering} 的字节码 22-35：{@code ldc "program" / getfield dispType /
 * String.equals / ifeq 38}，跳过 {@code drive()}，之后再没有一条写 {@code lightState} 的指令）。
 * 所以现在的做法是两条一起上：
 * <ol>
 *   <li>右键那一下由 {@code signal.FangsuTrafficLightMode} 调它自己的 {@code EnumConfig} setter
 *       把 {@code dispType} 切成 {@code "mtr"}（和玩家在配置界面点一下同一条路径）；</li>
 *   <li>{@code mixin.fangsu.FangsuTrafficLightRenderMixin} 在 {@code whenRendering} 的 HEAD 调
 *       {@code signal.FangsuSignalSync}，按绑定节点的 aspect 写红/黄/绿（MTR
 *       {@code getOccupiedAspect} 的逐字副本，3 档：1=红 2=黄 0=绿 -1=全灭）。</li>
 * </ol>
 * 两条都有降级：切不过去只提示玩家手动选「MTR信号联动」，渲染侧 {@code require = 0} +
 * {@code catch (Throwable)}。完整字节码证据见
 * {@code docs/report-2026-11-10-fangsu-traffic-light-mtr-mode.md}。
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
                        "[lte] 方速交通灯 fangsu:traffic_light 可参与信号连接器绑定：右键会先把它的"
                                + " dispType 切到 mtr（它自己的 EnumConfig setter），再由 "
                                + "FangsuTrafficLightRenderMixin -> FangsuSignalSync 每帧按绑定节点的 "
                                + "aspect 写 lightState（mtr 档在 1.3.2 里是空实现）。证据见 "
                                + "docs/report-2026-11-10-fangsu-traffic-light-mtr-mode.md");

            }
        } catch (Throwable t) {
            // Diagnostics must never break startup.
        }
    }
}
