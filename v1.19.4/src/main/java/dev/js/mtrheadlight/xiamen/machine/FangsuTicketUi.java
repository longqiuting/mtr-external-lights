// 方速售票界面的客户端桥：纯反射，编译期不依赖方速。
package dev.js.mtrheadlight.xiamen.machine;

import java.lang.reflect.Method;
import java.util.function.BiConsumer;

import dev.js.mtrheadlight.LteLog;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

/**
 * 把「开方速售票界面」这件事做成一个可空的可执行钩子 —— 只有客户端装了方速才有内容，
 * 服务端 / 没装方速的客户端永远是 {@code null}，调用方（{@link XiamenTicketMachineBlock}）不需要
 * 任何 {@code Class.forName}、也不需要 {@code isClient} 之外的判断。
 *
 * <h2>为什么是反射</h2>
 * <p>方速的 {@code com.fangsu.client.ClientHooks#openTicketMachineScreen(Text, BlockPos)} 是
 * <b>客户端类</b>：直接在 main 源集里 import 会让专用服务端在加载类时炸掉，而且我们的构建根本没有
 * 方速的编译期依赖（{@code libs/} 里也没有）。所以只在
 * {@code XiamenMachinesClient} 里反射拿一次方法，之后走的都是这个 {@link BiConsumer}。
 *
 * <p>拿方法的条件是「<b>名字对 + 两个参数</b>」，而不是死抠参数类型：方速的 jar 是 intermediary
 * 名（{@code class_2561} / {@code class_2338}），运行时被 Fabric 重映射成 yarn 名，写死
 * {@code Text.class} 在开发环境和正式环境之间会不一致；用 {@code isAssignableFrom} 判一次即可。
 *
 * <h2>失败时的行为</h2>
 * <p>方法找不到 / 调用抛异常 → 记一条 {@code warn}，钩子置空。之后 {@link #open} 一直返回
 * {@code false}，售票机那边会提示「客户端未安装方速，请用刷子切到原版界面」并让服务端退回原版界面，
 * 不会出现「右键没反应」。
 */
public final class FangsuTicketUi {

    /** 方速客户端钩子类（只以字符串出现）。 */
    private static final String HOOKS_CLASS = "com.fangsu.client.ClientHooks";

    /** 方速的售票界面入口方法名。 */
    private static final String OPEN_METHOD = "openTicketMachineScreen";

    /** 方速的模组 id。 */
    private static final String MOD_ID = "fangsu";

    private static volatile BiConsumer<Text, BlockPos> opener;

    /** 只在第一次用到时反射（见 {@link #open}）—— 注册期加载方速的类会让它在 registry 冻结前
     *  建出空键的 holder，客户端启动直接崩（"Some intrusive holders were not registered"）。 */
    private static boolean attempted;

    private FangsuTicketUi() {
    }

    /** 这个进程里有没有方速（服务端 / 客户端各自判断各自那一侧）。 */
    public static boolean loaded() {
        try {
            return FabricLoader.getInstance().isModLoaded(MOD_ID);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 客户端入口调一次：反射取到方速的售票界面入口。没有方速时什么也不做。 */
    public static void installClientBridge() {
        if (attempted) {
            return;
        }
        attempted = true;
        if (!loaded()) {
            LteLog.LOGGER.info("[lte] ticket machine: fangsu not installed, vanilla MTR UI only");
            return;
        }
        try {
            final Class<?> hooks = Class.forName(HOOKS_CLASS);
            for (final Method candidate : hooks.getMethods()) {
                if (!OPEN_METHOD.equals(candidate.getName())) {
                    continue;
                }
                final Class<?>[] params = candidate.getParameterTypes();
                if (params.length != 2
                        || !params[0].isAssignableFrom(Text.class)
                        || !params[1].isAssignableFrom(BlockPos.class)) {
                    continue;
                }
                candidate.setAccessible(true);
                opener = (title, pos) -> {
                    try {
                        candidate.invoke(null, title, pos);
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException("fangsu " + OPEN_METHOD + " failed", e);
                    }
                };
                LteLog.LOGGER.info("[lte] ticket machine: fangsu UI bridge ready ({})", candidate);
                return;
            }
            LteLog.LOGGER.warn("[lte] ticket machine: {}#{} not found, falling back to the vanilla UI",
                    HOOKS_CLASS, OPEN_METHOD);
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] ticket machine: could not reach the fangsu UI", t);
        }
    }

    /**
     * 在客户端开方速售票界面。
     *
     * @return {@code false} = 这个客户端开不了（没装方速 / 反射失败），调用方应退回原版界面
     */
    public static boolean open(final Text title, final BlockPos pos) {
        // 懒加载：第一次开界面才碰方速的类（原因见 #attempted 的注释）。
        installClientBridge();
        final BiConsumer<Text, BlockPos> hook = opener;
        if (hook == null) {
            return false;
        }
        try {
            hook.accept(title, pos);
            return true;
        } catch (Throwable t) {
            opener = null;
            LteLog.LOGGER.warn("[lte] ticket machine: the fangsu UI threw, disabling the bridge", t);
            return false;
        }
    }
}
