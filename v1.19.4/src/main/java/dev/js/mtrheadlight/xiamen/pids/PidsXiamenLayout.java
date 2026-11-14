// 厦门 PIDS 的屏幕排版常量（唯一调整点）：全部以 512×235 的底图像素为单位。
package dev.js.mtrheadlight.xiamen.pids;

/**
 * 厦门 PIDS（{@code mtrlte:pids_xiamen_metro} / {@code pids_xiamen_brt}）的<b>全部</b>排版常量。
 *
 * <h2>两套排版，各一张参考图</h2>
 * <p>地铁与 BRT 的版式完全不同（框的数量、内容、字号都不一样），所以这里<b>一块屏一套</b>，
 * 由 {@link #of(PidsXiamenBlock.Kind)} 选出来：
 * <table border="1">
 *   <caption>版式对照</caption>
 *   <tr><th></th><th>地铁 {@link #METRO}</th><th>BRT {@link #BRT}</th></tr>
 *   <tr><td>底图</td><td>蓝底白描边框</td><td>深色底圆角卡片</td></tr>
 *   <tr><td>左上</td><td>M 标志 + 右侧「YYYY-MM-DD / HH: MM」</td>
 *       <td>M 标志 + 右侧「MM月DD日星期X / HH: MM」</td></tr>
 *   <tr><td>左栏</td><td><b>三</b>个竖排框：开往 / 下一班开往 / 第三班开往</td>
 *       <td><b>两</b>个竖排框：开往X站 + 「线路号」约X分钟</td></tr>
 *   <tr><td>右侧</td><td>广告区（无描边，可拉伸）</td><td>广告卡片（可拉伸）</td></tr>
 *   <tr><td>底部</td><td>标语框（与广告区<b>右边界对齐</b>）</td><td>标语卡片（与广告卡片右边界对齐）</td></tr>
 * </table>
 *
 * <h2>坐标 = 底图像素</h2>
 * <p>屏幕平面就是底图的 512×235 个像素，原点左上、x 向右、y 向下。
 * 渲染时整块平面被 {@code scale(1 / }{@value #SCREEN_SCALE_DIVISOR}{@code )} 压进世界：
 * <pre>
 *   512 / 261.1 = 1.96 格宽     235 / 261.1 = 0.90 格高
 * </pre>
 * 与「两格宽的横向 PIDS」一致。所以下面所有数字都是<b>直接在 PNG 上量出来的像素</b>，
 * 不需要任何 0.49 之类的换算（上一版用的是 251×115 的缩放单位，读起来要多跳一次）。
 *
 * <h2>字号 = 框高 × 比例</h2>
 * <p>{@link TextStyle#sizeRatio()} 是<b>相对所属框的高度</b>的比例，渲染器乘上框高就得到字高（px）。
 * 一行字占的高度就是它的字高，行与行之间再留 {@code gapRatio × 框高}，所以
 * <b>永远不会两行叠在一起</b>（上一版把「行顶 / 行底 / 行中心」混着用，两行就撞了）。
 *
 * <h2>量法（可复现）</h2>
 * <p>框线用脚本扫的：地铁底图里白色描边的行/列直方图 → {@code y 6..47 / 51..107 / 111..166 / 172..227}、
 * {@code x 7..169}、标语框 {@code x 178..501 y 199..227}；BRT 底图里卡片色
 * {@code #222E46} 的连通域 → {@code x 9..167}，卡 {@code y 9..70 / 78..147 / 156..225}，
 * 广告卡 {@code x 178..504 y 9..176}，标语卡 {@code y 185..225}。
 * 广告区量出来是 {@code 176..501} / {@code 178..504}；用户要求「广告图左移」，
 * 地铁版把左边界拉到 170（贴住左栏外沿），BRT 版的图片本来就贴着卡片边框、没有再左的余地。
 * 文字位置再按参考图里每行字的亮像素带（{@code pngbands}）对位。
 */
public final class PidsXiamenLayout {

    private PidsXiamenLayout() {
    }

    // ---------------------------------------------------------------- 屏幕本体

    /** 屏幕宽度（= 底图宽，px）。 */
    public static final float SCREEN_WIDTH = 512.0F;
    /** 屏幕高度（= 底图高，px）。 */
    public static final float SCREEN_HEIGHT = 235.0F;

    /**
     * 屏幕缩放的分母：512×235 px 被 {@code scale(1/261.1)} 压成 1.96 × 0.90 格。
     * <p>上次是 {@code 251×115 单位 / 128}，物理尺寸完全相同，只是坐标系换成了底图像素。
     */
    public static final float SCREEN_SCALE_DIVISOR = 261.1F;

    // ---------------------------------------------------------------- 屏幕贴在哪（按模型几何算，没变）

    /** 屏幕左上角沿「屏幕朝外」方向外移多少（格）。 */
    public static final float ANCHOR_OUT = 0.4797F;
    /** 屏幕左上角沿 facing 往回缩多少（格）。= 0.5 − 0.02（贴图从 z=0.02 起）。 */
    public static final float ANCHOR_ALONG = 0.48F;
    /** 屏幕左上角的高度（格）。 */
    public static final float ANCHOR_Y = 0.8467F;

    /** 屏幕绕自身「长轴」的倾角（度），贴合模型 ±22.5° 的斜面。 */
    public static final float SCREEN_TILT_X = 22.5F;

    /**
     * 文字层相对底图往观察者方向推的距离（px）。
     *
     * <p><b>为什么只有 0.7px</b>：0.7 / 261.1 ≈ 0.0027 格 ≈ 2.7mm，肉眼看就是贴在屏面上。
     * 上一版是 7px（≈2.7cm），站在屏前斜看会明显「浮」在屏幕外面 —— 用户报的「文字/广告凸出」。
     * 文字只需要比底图多一点点深度余量（底图画在它之前，且不写深度，顺序上已经压住了）。
     */
    public static final float TEXT_LAYER_Z = -0.7F;

    /**
     * 广告层在<b>文字层之后</b>再叠加的偏移（px）。总偏移 = {@link #TEXT_LAYER_Z} + 本值。
     *
     * <p>底图和广告现在走<b>同一个</b> {@code MoreRenderLayers.getLight}（beacon 层：贴图原色、
     * 不过光照贴图、写深度），两张图亮度完全一致；谁在上面只看深度 —— 广告在 −0.35px
     * （更靠近观察者），所以底图后画也盖不住它。0.35px ≈ 1.3mm，视觉上贴面，
     * 不再需要为了「抢深度」把广告推出屏幕。
     */
    public static final float AD_LAYER_Z = 0.35F;

    // ---------------------------------------------------------------- 亮度

    // 屏幕没有「外围光晕」层（上一版的 GLOW_* 已删除）：底图本身是自发光层
    // （getEntityTranslucentEmissive）按满亮度画，看到的就是一块亮屏，不再向外扩一圈加色光。

    /** 满亮度（{@code LightmapTextureManager.MAX_LIGHT_COORDINATE}）。 */
    public static final int FULL_LIGHT = 15728880;

    /**
     * MC 字体在缩放 1 时的字形高度（px）。
     * <p>{@code TextRenderer.draw} 的字形占 8px，行高 9px；这里用 8 —— 也就是说
     * {@code scale = 字高px / 8}，屏幕上量到的字高正好等于我们给的值。
     */
    public static final float FONT_UNIT_PX = 8.0F;

    // ---------------------------------------------------------------- 颜色

    public static final int COLOR_WHITE = -1;

    // ---------------------------------------------------------------- 广告 / 标语

    /** 广告图是否保持原始宽高比；{@code false} = 直接拉伸铺满（参考图写的是「可以拉伸」）。 */
    public static final boolean AD_KEEP_ASPECT = false;
    /**
     * 一张广告的<b>兜底</b>停留时长（tick）。
     *
     * <p>正常轮播不用这个数：一张广告停多久 = 它自己那条标语滚完的时间
     * （{@link PidsXiamenTicker#scrollSpanTicks}，学天津的「滚完再换下一张」）。
     * 只有标语表是空 / 标语条太窄 / 这张广告的图丢了这种算不出时长的情况下才回落到它。
     */
    public static final int AD_ADVANCE_TICKS = 200;

    /** 标语条左右各留多少 px（文字在这里面被逐字裁切，出框的字直接不画）。 */
    public static final float TICKER_INSET = 5.0F;

    // 标语不再「等比缩小到条宽以内」：一条太长的消息就保持原字号滚过去，
    // 只有跨过标语条左右边缘的那几个字被逐字裁掉（= 天津 ScrollingText 用 UV 裁边的同一个效果）。
    // 以前那条 TICKER_MAX_WIDTH_RATIO 会把长句整体缩小，字越缩越小、屏上读不清。

    // ---------------------------------------------------------------- 结构参数

    /** 每行几排文字（MTR 界面用，我们的排版固定 1）。 */
    public static final int LINES_PER_ARRIVAL = 1;

    // ================================================================ 数据类型

    /** 水平对齐。 */
    public enum HAlign {
        LEFT,
        CENTER,
        RIGHT
    }

    /**
     * 一个矩形（底图像素）。
     *
     * @param x0 左边界
     * @param y0 上边界
     * @param x1 右边界
     * @param y1 下边界
     */
    public record Rect(float x0, float y0, float x1, float y1) {

        public float w() {
            return this.x1 - this.x0;
        }

        public float h() {
            return this.y1 - this.y0;
        }

        public float cx() {
            return (this.x0 + this.x1) * 0.5F;
        }

        public float cy() {
            return (this.y0 + this.y1) * 0.5F;
        }

        /** 框宽的 {@code ratio} 处。 */
        public float x(final float ratio) {
            return this.x0 + w() * ratio;
        }

        /** 框高的 {@code ratio} 处。 */
        public float y(final float ratio) {
            return this.y0 + h() * ratio;
        }
    }

    /**
     * 一行文字的样子。
     *
     * @param sizeRatio     字高 ÷ <b>所属框</b>的高度
     * @param color         ARGB
     * @param xRatio        水平锚点 ÷ 框宽（配合 {@code align}）
     * @param align         水平对齐
     * @param maxWidthRatio 这一行最多占框宽的多少（超了自动缩小字号，防止压边）
     */
    public record TextStyle(float sizeRatio, int color, float xRatio, HAlign align, float maxWidthRatio) {
    }

    /**
     * 地铁的竖排框（三个）。
     *
     * @param card     框
     * @param padRatio 上下内边距 ÷ 框高
     * @param gapRatio 行间距 ÷ 框高
     * @param label    左列大标签（开往 / 下一班开往 / 第三班开往）
     * @param labelSub 左列小标签（Bound for / Next train / Third train）
     * @param dest     右列终点站（大）
     * @param destSub  右列终点站（小，英文）
     * @param count    右列倒计时数字（大，强调色）
     * @param unit     右列倒计时单位（小），跟在 {@code count} 后面同一行
     */
    public record Row(Rect card, float padRatio, float gapRatio,
                      TextStyle label, TextStyle labelSub,
                      TextStyle dest, TextStyle destSub,
                      TextStyle count, TextStyle unit) {
    }

    /**
     * BRT 的竖排框（两个）：第一行「开往XXXX站」，第二行「线路号 约X 分钟」。
     * 「即将进站 / 已到站」这一档没有数字也没有单位，<b>不写「约」</b>，只写「线路号 状态」。
     *
     * @param card     框
     * @param padRatio 上下内边距 ÷ 框高
     * @param gapRatio 行间距 ÷ 框高
     * @param dest     第一行
     * @param route    第二行的线路号（强调色）
     * @param about    第二行的「约」
     * @param count    第二行的倒计时数字（大）
     * @param unit     第二行的单位
     */
    public record BrtRow(Rect card, float padRatio, float gapRatio,
                         TextStyle dest, TextStyle route, TextStyle about,
                         TextStyle count, TextStyle unit) {
    }

    /**
     * 左上角「标志 + 日期时间」那块。
     *
     * @param card     框（M 标志已经画在底图里，这里只表示那块区域）
     * @param padRatio 上下内边距 ÷ 框高
     * @param gapRatio 两行之间 ÷ 框高
     * @param date     日期行
     * @param time     时间行
     */
    public record Clock(Rect card, float padRatio, float gapRatio, TextStyle date, TextStyle time) {
    }

    /** 地铁整套。 */
    public record Metro(Clock clock, Rect ad, Rect ticker, TextStyle tickerText, Row[] rows) {
    }

    /** BRT 整套。 */
    public record Brt(Clock clock, Rect ad, Rect ticker, TextStyle tickerText, BrtRow[] rows) {
    }

    // ================================================================ 地铁版

    /** 左上「标志 + 日期时间」：贴图 px x 7..169、y 6..47。 */
    private static final Clock METRO_CLOCK = new Clock(
            new Rect(7, 6, 169, 47), 0.12F, 0.10F,
            // 日期 / 时间写在 M 标志右侧（底图上那里是空的），左对齐，字号分别是框高的 0.22 / 0.30
            new TextStyle(0.22F, COLOR_WHITE, 0.33F, HAlign.LEFT, 0.62F),
            new TextStyle(0.30F, COLOR_WHITE, 0.33F, HAlign.LEFT, 0.62F));

    /** 三个竖排框，x 7..169；y 分别是 51..107、111..166、172..227。 */
    private static final Row[] METRO_ROWS = {
            metroRow(new Rect(7, 51, 169, 107)),
            metroRow(new Rect(7, 111, 169, 166)),
            metroRow(new Rect(7, 172, 169, 227))
    };

    private static Row metroRow(final Rect card) {
        // 左列 x = 16px（框左边 +9），右列 x = 76px（框左边 +69）—— 参考图量出来的。
        final float labelX = 9.0F / card.w();
        final float contentX = 69.0F / card.w();
        return new Row(card, 0.07F, 0.05F,
                new TextStyle(0.18F, COLOR_WHITE, labelX, HAlign.LEFT, 0.34F),
                new TextStyle(0.105F, COLOR_WHITE, labelX, HAlign.LEFT, 0.34F),
                new TextStyle(0.27F, COLOR_WHITE, contentX, HAlign.LEFT, 0.55F),
                new TextStyle(0.125F, COLOR_WHITE, contentX, HAlign.LEFT, 0.55F),
                // 倒计时也是白的（整屏只有 BRT 的线路号带颜色）
                new TextStyle(0.36F, COLOR_WHITE, contentX, HAlign.LEFT, 0.32F),
                new TextStyle(0.16F, COLOR_WHITE, 0, HAlign.LEFT, 0));
    }

    /** 地铁版：蓝底白框、三个到站框。 */
    public static final Metro METRO = new Metro(
            METRO_CLOCK,
            // 广告区：<b>用户标出来的粉色矩形</b>（桌面 `厦门地铁pids示例1.png`，脚本量的是
            // 粉色像素 bbox = x 179..511、y 33..197，所以矩形取 x1/y1 各 +1 当开区间）。
            // 左 179 正好和下面标语框的白色边框内侧对齐（图上那句「和下面标语边框对齐」），
            // 上 33 是线路徽章条（最后一行 32）下面第一行，下 198 是标语框白色上边框（199）上面一行。
            // 上一版左边界 170 是按「左栏外沿 169 再让 1px」推的，比标记宽 9px，把标语框
            // 左边缘那条竖线露在了广告外面 —— 这次全部按粉色块来。
            new Rect(179, 33, 512, 198),
            // 标语框：x 178..501、y 199..227
            new Rect(178, 199, 501, 227),
            // 标语字：框高的 0.52（≈15px），水平居中，左右各留 5px 裁切
            new TextStyle(0.52F, COLOR_WHITE, 0.5F, HAlign.CENTER, 1.0F),
            METRO_ROWS);

    // ================================================================ BRT 版

    /** 顶部卡片（M 标志 + 日期时间）：x 9..167、y 9..70。 */
    private static final Clock BRT_CLOCK = new Clock(
            new Rect(9, 9, 167, 70), 0.12F, 0.10F,
            // 标志右侧（x=74px）两行：MM月DD日星期X / HH: MM
            new TextStyle(0.15F, COLOR_WHITE, 0.41F, HAlign.LEFT, 0.57F),
            new TextStyle(0.20F, COLOR_WHITE, 0.41F, HAlign.LEFT, 0.57F));

    /** 两个竖排框：y 78..147、156..225。 */
    private static final BrtRow[] BRT_ROWS = {
            brtRow(new Rect(9, 78, 167, 147)),
            brtRow(new Rect(9, 156, 167, 225))
    };

    private static BrtRow brtRow(final Rect card) {
        // 参考图里两行字都是左对齐、左边距约 13px。
        final float x = 13.0F / card.w();
        return new BrtRow(card, 0.10F, 0.12F,
                new TextStyle(0.21F, COLOR_WHITE, x, HAlign.LEFT, 0.88F),
                // 线路号的颜色在渲染时按 MTR 的线路色覆盖（这里只是取不到线路时的兜底）
                new TextStyle(0.21F, COLOR_WHITE, x, HAlign.LEFT, 0.42F),
                new TextStyle(0.16F, COLOR_WHITE, 0, HAlign.LEFT, 0),
                new TextStyle(0.30F, COLOR_WHITE, 0, HAlign.LEFT, 0),
                new TextStyle(0.15F, COLOR_WHITE, 0, HAlign.LEFT, 0));
    }

    /** BRT 版：深色圆角卡片、两个到站框。 */
    public static final Brt BRT = new Brt(
            BRT_CLOCK,
            // 广告卡片：卡片<b>外框</b>是 x 178..504、y 9..176，1px 描边占掉最外一圈，
            // 内部可画区域是 x 179..503、y 10..175。这里就是这圈内部：
            // 图片四边贴住卡片描边（贴边），不会盖掉那条 1px 的边框。
            new Rect(179, 10, 504, 176),
            // 标语卡片：x 178..504、y 185..225（右边界与广告卡片对齐）
            new Rect(178, 185, 504, 225),
            new TextStyle(0.38F, COLOR_WHITE, 0.5F, HAlign.CENTER, 1.0F),
            BRT_ROWS);

    // ================================================================ 选一套

    /** 这块屏（地铁 / BRT）用哪一套。 */
    public static Object of(final PidsXiamenBlock.Kind kind) {
        return kind == PidsXiamenBlock.Kind.BRT ? BRT : METRO;
    }

    /** 这块屏有几个到站框。 */
    public static int rowsOf(final PidsXiamenBlock.Kind kind) {
        return kind == PidsXiamenBlock.Kind.BRT ? BRT.rows().length : METRO.rows().length;
    }
}
