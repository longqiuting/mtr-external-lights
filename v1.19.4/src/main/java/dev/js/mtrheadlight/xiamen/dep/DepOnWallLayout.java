// 挂墙发车倒数器的全部几何 / 排版常量（唯一调整点）。
package dev.js.mtrheadlight.xiamen.dep;

/**
 * 挂墙发车倒数器 {@code mtrlte:dep_on_wall} 的几何与排版常量。
 *
 * <h2>屏幕在哪（全部从 {@code 素材/挂墙发车倒数器/dep_on_wall.json} 量出来）</h2>
 * <p>模型是一块贴在墙上、向 -Z 凸出 4/16 格的小盒子（元素 3..6 是四条边柱 / 上下横梁，
 * z 从 12 排到 16），中间那块 <b>凹进去 0.3 格</b>的发光板是元素 2：
 * <pre>
 *   from [2, 4, 12.3]  to [14, 12, 12.3]      ← 零厚度的单面，north 面 uv = [3, 0, 6, 2]
 * </pre>
 * 也就是说屏幕平面 = <b>x 2..14、y 4..12、z = 12.3</b>（单位 1/16 格），
 * 正面（贴图里的暗红 LED 点阵）朝 <b>北</b>；模型默认就是 {@code facing=north}。
 *
 * <h2>屏幕坐标系（改这里之前先读这段）</h2>
 * <p>用「GUI 风格」的局部坐标：<b>+X = 屏幕向右、+Y = 屏幕向下、+Z = 扎进屏幕里</b>。
 * 单位取 LED 点阵的像素：
 * <pre>
 *   屏幕 = 96 × 64 px（= 贴图里 uv 3..6 × 0..2 那 96×64 个点）
 *   1 px = 1/128 格   （96 px 铺满 0.75 格宽；64 / 128 = 0.5 格高，与模型 8/16 格一致）
 * </pre>
 *
 * <h2>原点 = 屏幕<b>正中</b>（不是左上角）</h2>
 * <p>这是 2026-11-12 那次返工改掉的东西，也是「为什么这次翻转终于安全」的关键：
 * <p>以前原点压在<b>屏幕左沿</b>（模型 x = 14/16），所以「把读数方向翻一下」必须同时改三处
 * —— 锚点 x、两个 u 值、渲染器里那个 {@code scale(-1,1,1)} —— 三处只要有一处没跟上，
 * 屏幕就会整体平移 0.75 格（飘出方块）或者底图对不上，而玩家看到的仍然只是「数字还是反的」。
 * <p>现在原点在<b>正中</b>，屏幕四角对称（x ∈ [-48, +48]、y ∈ [-32, +32] px），
 * 于是「左右翻 / 上下翻 / 翻 180°」都只是绕原点的 {@code scale(±1, ±1, 1)}：
 * <b>屏幕占的那块面积一个像素都不动，变的只有屏幕里的内容</b>。翻转本身见
 * {@link DepOnWallOrient}，这里只放常量。
 *
 * <h2>读数方向（谁决定「哪边算右」）</h2>
 * <p>数码管是<b>几何</b>画的（{@link DepOnWallDigits}），不像 {@code TextRenderer} 那样自带
 * 「GUI 文字」的翻转处理：框架里 +X 往哪边走，字就往哪边读。所以渲染器不再对
 * {@code rotateZ(180)} 做加减猜方向，而是直接按<b>基向量</b>把文字坐标系摆到牌子的正面：
 * <pre>
 *   out   = facing 的单位向量         （屏幕法线，指向玩家）
 *   right = up × out                  （玩家站在牌子正前方时的右手方向）
 *   down  = -up                       （屏幕 +Y）
 *   into  = -out                      （屏幕 +Z，扎进牌子里）
 * </pre>
 * 三个基向量拼成的 3×3 是<b>正交且行列式 = +1</b> 的旋转（不含镜像），所以画出来的数字
 * 天然是正的、不带任何隐形反射。建成这个基之后，「玩家站在牌子正面时右手是哪边」就不再
 * 需要人脑推 —— 它是叉乘算出来的。
 * <p>万一实机读出来仍然不对，<b>不要去改这里的基</b>：用游戏内开关
 * {@code /mtr_lte dep} 现场翻（{@link DepOnWallOrient}，四种状态，落盘保存）。
 *
 * <h2>这块屏只写一行数码管</h2>
 * <p>它是<b>站台头端的发车倒数器</b>，不是 PIDS：屏幕上永远只有一串 {@code 00:19} 这样的
 * 七段数字，没有站名、没有「开往 X」。状态见 {@code DepOnWallRenderer} 的类注释：
 * 空屏 → 绿色倒数 → 红色 5 秒正计时 → 空屏。
 *
 * <p>数字的字号<b>不是常量</b>：位数（{@code 00:05} 四位 / {@code 10:05} 五位）一变，宽度就变。
 * 下面给的只是字形的<b>比例</b>（都以字高为单位），{@link DepOnWallDigits#width} 量出整串
 * 的「以字高为单位的宽度」，渲染器再取
 * {@code min(可用宽 / 整串宽, 可用高)} 作为字高并居中 —— 于是四位数字能撑到 ~39 px 高，
 * 五位会自动缩到 ~32 px，永远不溢出屏幕。
 */
public final class DepOnWallLayout {

    private DepOnWallLayout() {
    }

    // ---------------------------------------------------------------- 屏幕本体

    /** 屏幕宽度（LED 点阵像素）= 贴图 uv 3..6 × 32。 */
    public static final float SCREEN_WIDTH = 96.0F;
    /** 屏幕高度（LED 点阵像素）= 贴图 uv 0..2 × 32。 */
    public static final float SCREEN_HEIGHT = 64.0F;

    /**
     * 把 LED 像素压进世界的分母：96 px / 0.75 格 = 128。
     * <p>也就是 {@code scale(1/128)}，于是 64 px = 0.5 格，和模型的 8/16 格高严格对齐。
     */
    public static final float SCREEN_SCALE_DIVISOR = 128.0F;

    // ---------------------------------------------------------------- 屏幕贴在哪

    /**
     * 屏幕<b>正中</b>在模型空间的位置（单位：格）= 元素 2 的中心 {@code (8, 8, 11.96)/16}。
     *
     * <p>x / y 就是元素 2 的中点（{@code (2+14)/2}、{@code (4+12)/2}）。
     * <p>z 是<b>边柱正面那一层（z = 12）之前 0.04/16 格</b>，不是板面本身的 12.3：
     * 模型里那块发光板缩在 0.3/16 深的口袋里，斜看时会被边柱削掉、正面看还会和板面 z-fighting
     * （就是「DEP 文字被模型挡住」）；放到 11.96 之后整块屏贴平在边框上，模型上再没有任何东西
     * 能挡在它前面。0.04/16 ≈ 0.0025 格，肉眼看不出来。
     */
    public static final float SCREEN_CENTER_X = 8.0F / 16.0F;
    public static final float SCREEN_CENTER_Y = 8.0F / 16.0F;
    public static final float SCREEN_CENTER_Z = 11.96F / 16.0F;

    /**
     * 底图四角 UV（就是元素 2 north 面的 uv {@code [3, 0, 6, 2]}，除以 16 归一化）。
     *
     * <p>{@link #UV_LEFT} 对应局部 x = -48（屏幕左沿），{@link #UV_RIGHT} 对应 x = +48。
     * 这两个值<b>不随翻转变化</b>：翻转是几何层的 {@code scale(±1, ±1, 1)}，底图和数字一起翻，
     * 底图那块暗红点阵是左右对称的，所以翻不翻看起来一模一样 —— 这也是「翻转只影响读数、
     * 不会让屏幕错位」的原因。
     */
    public static final float UV_LEFT = 3.0F / 16.0F;
    public static final float UV_RIGHT = 6.0F / 16.0F;
    public static final float UV_TOP = 0.0F / 16.0F;
    public static final float UV_BOTTOM = 2.0F / 16.0F;

    /**
     * 数字层相对底图往观察者方向推的距离（px）。
     *
     * <p>数字和底图是两张贴图 = 两个 {@code RenderLayer}，绘制顺序不由我们决定；1.5px
     * （≈0.012 格）的深度余量让深度测试保证数字永远压在底图前面，不会出现「底图后画、
     * 把数字抹掉」。
     */
    public static final float TEXT_LAYER_Z = -1.5F;

    // ---------------------------------------------------------------- 亮度 / 颜色

    /** 满亮度（{@code LightmapTextureManager.MAX_LIGHT_COORDINATE}）：屏幕夜里也是一块亮屏。 */
    public static final int FULL_LIGHT = 15728880;

    /**
     * 倒数中的数字：亮绿（{@code #00E676}）。
     * <p>压在暗红点阵底图上对比很强，白天隔着半个站台也读得出来；和真实发车计时器的
     * 「还能走」一档同色系。
     */
    public static final int COLOR_COUNTDOWN = 0xFF00E676;

    /**
     * 到 00:00 之后的正计时：纯红（{@code #FF0000}）。
     * <p>不是暗红 —— 它要能在绿色刚消失的那一瞬间被一眼认出来「已经过点了」。
     */
    public static final int COLOR_OVERDUE = 0xFFFF0000;

    // ---------------------------------------------------------------- 七段字形比例

    /**
     * 字宽 / 字高。
     * <p>0.50 是七段管常见的「瘦高」比例：再宽一点四位 {@code 00:00} 就撑不满高度，
     * 再窄一点「1」和其它数字并排时会显得空。四位数字在这块 96×64 的屏上量到的字高
     * 约 35 px（宽度先到顶，高度也就到这儿了）。
     */
    public static final float LED_DIGIT_ASPECT = 0.50F;

    /**
     * 段厚 / 字高。
     * <p>0.13 时横竖段一样粗，斜切出来的缝刚好是一个亮点的宽度 —— 远看是「一节一节」的
     * 数码管，近看缝也还没宽到把数字拆散；再粗一档 {@code 0} 中间那个洞就快糊死了。
     */
    public static final float LED_SEG_THICKNESS = 0.13F;

    /** 字距 / 字高（相邻两个字形之间的空隙）。 */
    public static final float LED_DIGIT_GAP = 0.08F;

    /** 冒号列宽 / 字高（两个方点占的宽度，比一个数字窄得多）。 */
    public static final float LED_COLON_WIDTH = 0.18F;

    /** 数字上下各留多少 px（居中后剩下的就是留白）。 */
    public static final float LED_INSET_Y = 5.0F;

    /** 数字最大字高（px）：整串再短也不会比这更高，避免两个数字顶到屏幕上下边框。 */
    public static final float LED_MAX_HEIGHT = SCREEN_HEIGHT - LED_INSET_Y * 2.0F;

    // ---------------------------------------------------------------- 排版

    /** 左右各留多少 px（数字被限制在这条线内，超了整体等比缩小）。 */
    public static final float INSET_X = 4.0F;
    /** 数字可用宽度。 */
    public static final float TEXT_MAX_WIDTH = SCREEN_WIDTH - INSET_X * 2.0F;
}
