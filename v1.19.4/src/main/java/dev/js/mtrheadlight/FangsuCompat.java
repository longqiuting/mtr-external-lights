package dev.js.mtrheadlight;

import net.fabricmc.loader.api.FabricLoader;

/**
 * 方速（fangsu）兼容性说明 / 启动日志（1.18.2 / 1.19.2 / 1.19.4 版）。
 *
 * <p>方速是一个可选的 MTR 追加包，本模组<b>故意不产生 {@code com.fangsu.*} 的编译期依赖</b>
 * （它不在 {@code libs/} 里，而且版本带着 MC 版本号在变）。所有接触点都只有两条路：
 *
 * <ul>
 *   <li>{@code signal.FangsuSignalCompat} —— 纯 registry id 判断（{@code fangsu:traffic_light}），
 *       给「信号连接器能不能绑这盏灯」用；</li>
 *   <li>{@code mixin.FangsuMixinPlugin} + {@code mtrlte.fangsu.mixins.json} —— 类存在性探针
 *       （{@code ClassLoader#getResource}，不加载类），只决定
 *       {@code FangsuTrafficLightRenderMixin} 要不要应用；配置是 {@code required: false}，
 *       每条注入 {@code require = 0}。</li>
 * </ul>
 *
 * <p>所以「没装方速」和「装了别的版本的方速」都退化成「什么都不做」，绝不会崩。
 *
 * <h2>交通灯为什么必须两步</h2>
 * <p>{@code fangsu:traffic_light} 继承 {@code BaseObjBlock}（= {@code HorizontalFacingBlock}），
 * 不是 MTR 的 {@code BlockSignalLightBase}；灯色来自方块实体的 {@code lightState}，只在
 * {@code dispType="program"} 时由积木程序 {@code drive()} 写入 —— 也就是说「MTR 信号联动」那一档
 * 在方速里是<b>空实现</b>（只负责把积木程序关掉）。因此：
 * <ol>
 *   <li>右键那一下由 {@code signal.FangsuTrafficLightMode} 走方速自己的 {@code EnumConfig} setter
 *       把 {@code dispType} 切成 {@code "mtr"}（和玩家在配置界面点一下同一条路径）；</li>
 *   <li>{@code mixin.fangsu.FangsuTrafficLightRenderMixin} 在 {@code whenRendering} 的 HEAD 调
 *       {@code signal.FangsuSignalSync}，按绑定节点的 aspect 写红/黄/绿。</li>
 * </ol>
 * 两条都有降级：切不过去只提示玩家手动选「MTR信号联动」；渲染侧 {@code require = 0} +
 * {@code catch (Throwable)}，方速改结构就只是不再联动，灯保持方速自己的行为。
 *
 * <p>检测这里只按 mod id，日志那一行是给支持日志用的；mixin 那条路自己用 class 探针。
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
                                + "from the same rail chunk classes, so the light redirect and "
                                + "registerLightUpdate cover them");
                MtrHeadlightClient.LOGGER.info(
                        "[lte] 方速交通灯 fangsu:traffic_light 可参与信号连接器绑定：右键会先把它的"
                                + " dispType 切到 mtr（它自己的 EnumConfig setter），再由 "
                                + "FangsuTrafficLightRenderMixin -> FangsuSignalSync 每帧按绑定节点的 "
                                + "aspect 写 lightState（mtr 档在方速 1.3.2 里是空实现）");
            }
        } catch (Throwable t) {
            // Diagnostics must never break startup.
        }
    }
}
