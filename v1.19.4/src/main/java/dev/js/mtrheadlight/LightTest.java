package dev.js.mtrheadlight;

/**
 * <b>{@code /mtr_lte lighttest on|off} — 车灯渲染路径的取证开关。</b>
 *
 * <h2>它回答的问题</h2>
 * 玩家看到的「只有光带，灯晕和散射都不见了」有四种完全不同的原因，而它们在截图里长得一模一样：
 * <ol>
 *   <li><b>几何 / 时机</b>：四边形确实写进了顶点缓冲，但落在视锥外面（最典型的原因是喂了世界绝对
 *       坐标而不是相机相对坐标），或者画在了会被覆盖的时机；</li>
 *   <li><b>程序 / 贴图</b>：灯晕用的是 {@code position_tex_color} 程序 + 灯晕贴图，程序没加载或贴图
 *       路径错了就一点都画不出来，而顶点数照样在涨；</li>
 *   <li><b>pass / 附件</b>：全屏散射 pass 在跑（{@code frames} 在涨），但它写的那张 framebuffer /
 *       那个颜色附件根本没到屏幕上；</li>
 *   <li><b>shader 数学</b>：pass 和附件都对，是深度重建 / 灯参数让它算出来接近 0。</li>
 * </ol>
 * 这个开关用三块纯色把这些原因一次拆开，<b>每一块都跳过配置闸、灯位、朝向、距离、透明度判定</b>：
 *
 * <table border="1">
 *   <caption>屏幕上的三块颜色</caption>
 *   <tr><th>看到什么</th><th>谁画的</th><th>结论</th></tr>
 *   <tr>
 *     <td><b>洋红四边形</b>（每列车的车头 / 车尾前方 2 格，3 格见方）</td>
 *     <td>灯晕层，原版 {@code position_color} 程序，<b>不带贴图</b></td>
 *     <td>看不到 → 世界渲染回调 / 相机相对坐标 / 程序绑定这一层坏了；
 *         看得到而灯晕仍然没有 → 灯晕的<b>贴图或纹理程序</b>是问题，与几何、时机无关</td>
 *   </tr>
 *   <tr>
 *     <td><b>左半屏红</b></td>
 *     <td>散射 pass <b>自己的</b> {@code light_scatter} 程序（{@code uTest=1} 时整段
 *         {@code main()} 只输出 {@code vec4(1,0,0,1)}）</td>
 *     <td>看不到 → 程序绑定 / uniform 上传 / 全屏四边形 / 颜色附件 有一环断了；
 *         看得到而光雾仍然没有 → 程序与附件都对，问题在 <b>shader 的深度重建或灯参数</b></td>
 *   </tr>
 *   <tr>
 *     <td><b>右半屏蓝</b></td>
 *     <td>原版 {@code position_color} 程序（{@link GlowCompat#drawScreenQuad}），
 *         不碰我们的程序、不碰深度拷贝</td>
 *     <td>只有蓝没有红 → {@code LAST} 这个时机与颜色附件是通的，断的是我们的
 *         {@code light_scatter} 程序那一条；两半都没有 → 整条 {@code LAST} 写入链路在光影 /
 *         别的模组手里</td>
 *   </tr>
 * </table>
 *
 * <h2>为什么状态只在内存里</h2>
 * 它不是玩家的设置项：{@code /mtr_lte lighttest on} 打开、退出游戏或 {@code off} 关掉，
 * 永远不写 {@code config/mtrlte.json} —— 和 {@code /mtr_lte debug spray} 一致。
 *
 * <h2>日志</h2>
 * 打开时每 5 秒一行 {@code [lte] lighttest: flareQuads=N scatterTint=true|false}：
 * <ul>
 *   <li>{@code flareQuads} —— 本窗口真的写进顶点缓冲的洋红四边形数。<b>0</b> 表示连列车都没走到
 *       （世界回调没跑 / 没有可见列车），{@code >0} 而屏幕上看不见才是「几何在、但没画到屏幕上」。</li>
 *   <li>{@code scatterTint} —— 本窗口散射 pass 自己的纯红有没有真的提交（{@code uTest} 生效）。</li>
 *   <li>后面可能跟 {@code fallbackTint=true}（右半屏蓝已提交）、{@code tintError=…} /
 *       {@code flareError=…}（哪一环抛了异常）—— 把「静默什么都不画」变成一句有名字的原因。</li>
 * </ul>
 */
public final class LightTest {

    private static final long LOG_INTERVAL_MS = 5000L;

    /** 只在内存里：不是配置项。 */
    private static volatile boolean on;

    private static long windowStart = System.currentTimeMillis();
    private static int flareQuads;
    /** 窗口内散射 pass 自己的纯红是否提交过 —— 粘性，避免帧内先后顺序把它抹掉。 */
    private static boolean programTint;
    /** 窗口内原版程序那条对照（右半屏蓝）是否提交过。 */
    private static boolean fallbackTint;
    private static int submits;
    private static String flareError;
    private static String tintError;

    private LightTest() {
    }

    /** {@code true} 时两个渲染器都走纯色探针，不看任何配置闸。 */
    public static boolean enabled() {
        return on;
    }

    /**
     * 打开 / 关闭探针。打开时把窗口清空并立刻从「现在」计时，所以第一行日志总是在开关之后不久
     * 打出来，而不是等满 5 秒。
     */
    public static void set(final boolean value) {
        on = value;
        windowStart = System.currentTimeMillis();
        flareQuads = 0;
        submits = 0;
        programTint = false;
        fallbackTint = false;
        flareError = null;
        tintError = null;
    }

    /** 一次 {@code /mtr_lte lighttest} 的状态字符串（不带 {@code lighttest:} 前缀，指令层自己拼）。 */
    public static String status() {
        return "lighttest = " + (on ? "on" : "off")
                + (on ? "（洋红四边形 = 灯晕层几何；左半屏红 = 散射 pass 在写颜色；右半屏蓝 = 时机对照）"
                        : "");
    }

    /** 灯晕层真的写了一个洋红四边形。 */
    static void flareQuad() {
        flareQuads++;
    }

    /** 灯晕层的探针这一帧没能画出来，{@code reason} 直接进日志。 */
    static void flareFailure(final String reason) {
        flareError = reason;
    }

    /** 散射 pass 自己（{@code uTest=1}）已经提交了一次纯红。 */
    public static void programTintSubmitted() {
        submits++;
        programTint = true;
    }

    /** 散射 pass 的纯红这次没能提交，{@code reason} 进日志（通常是程序加载失败）。 */
    public static void tintFailure(final String reason) {
        tintError = reason;
    }

    /**
     * <b>右半屏蓝色对照</b>：在 {@code WorldRenderEvents.LAST} 里用原版 {@code position_color} 程序
     * 把右半屏涂成蓝色（左半屏留给散射程序自己画的红，两边不会互相盖住）。
     *
     * <p>它绕开本模组自己的全部机制：不采灯、不拷深度、不绑 {@code light_scatter} 程序、不看
     * {@code beamScatter}。所以「只有蓝、没有红」这一种组合能单独点名「我们的程序没写进去，但这个
     * 时机能写」。
     *
     * @return {@code true} 表示已经把这次绘制提交出去
     */
    public static boolean drawFallbackTint() {
        try {
            GlowCompat.drawScreenQuad(0.0F, -1.0F, 1.0F, 1.0F, 0.15F, 0.35F, 1.0F, 0.55F);
            fallbackTint = true;
            return true;
        } catch (Throwable t) {
            tintError = t.getClass().getSimpleName() + ":" + firstLine(t);
            return false;
        }
    }

    /**
     * 每 5 秒一行。由两个渲染器都调一次（谁先到谁打），窗口计数在打印后清零。
     * 只有在探针打开时才打 —— 关掉之后日志恢复安静。
     */
    public static void report() {
        if (!on) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (now - windowStart < LOG_INTERVAL_MS) {
            return;
        }
        windowStart = now;
        final StringBuilder extra = new StringBuilder();
        if (fallbackTint) {
            extra.append(" fallbackTint=true");
        }
        if (tintError != null) {
            extra.append(" tintError=").append(tintError);
        } else if (flareError != null) {
            extra.append(" flareError=").append(flareError);
        } else if (submits > 0) {
            extra.append(" tintSubmits=").append(submits);
        }
        MtrHeadlightClient.LOGGER.info("[lte] lighttest: flareQuads={} scatterTint={}{}",
                Integer.valueOf(flareQuads), Boolean.valueOf(programTint), extra.toString());
        flareQuads = 0;
        submits = 0;
        programTint = false;
        fallbackTint = false;
        flareError = null;
        tintError = null;
    }

    private static String firstLine(final Throwable t) {
        final String text = t == null ? "unknown" : String.valueOf(t.getMessage());
        final int newline = text.indexOf('\n');
        final String head = newline < 0 ? text : text.substring(0, newline);
        return head.length() > 120 ? head.substring(0, 120) : head;
    }
}
