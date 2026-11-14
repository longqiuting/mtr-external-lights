// 「这一帧画的是哪一张品牌牌子」——MTR 把 logo 烤进站名贴图里，这是唯一的换图开关。
package dev.js.mtrheadlight.xiamen.sign;

/**
 * 贴墙标识的<b>品牌作用域</b>：告诉 MTR 的贴图生成器「这次要画的是 {@code mtrlte:wall_sign_*} 的牌子，
 * 品牌 logo 用 {@code mtrlte:block/<brand>_logo.png}」。
 *
 * <h2>为什么需要它（MTR 的 logo 不在模型里）</h2>
 * <p>MTR 3.6.4.2 的「站名出入口」<b>没有任何 logo 模型</b>：
 * {@code assets/mtr/models/block/station_name_entrance_{short,tall}.json} 只有一块
 * {@code mtr:block/dark_blue} 的零厚度灯板（299 字节）。logo 是<b>画进运行时贴图里</b>的：
 * <pre>
 *   RenderStationNameTiled#drawStationName(showLogo=true)
 *     ClientData.DATA_CACHE.getStationNameEntrance(color, name, scale)
 *       ClientCache#getResource(key="station_name_entrance_%s_%s_%s", …)
 *         RouteMapGenerator.generateStationNameEntrance(int, String, float)      ← 画布 + 底板色
 *           RouteMapGenerator.drawResource(image, "textures/block/sign/logo.png", …)
 *                                    ↑ new Identifier("mtr", path)              ← 命名空间写死 mtr
 *                   RenderTrains.scheduleRender(…, INTERIOR, …)                ← 面板几何 / UV
 * </pre>
 * javap 取证（3.6.4.2 / 1.20.1）：
 * <pre>
 *   public static NativeImage generateStationNameEntrance(int, String, float);   // 没有 logo 参数
 *   private static void drawResource(NativeImage, String, int, int, int, int, boolean, float, float, int, boolean);
 *   private static final String LOGO_RESOURCE;                                  // 私有常量，常量折叠
 * </pre>
 * 也就是说：<b>MTR 不给 logo 参数，也没有 {@code getLogo()} 之类的可覆写点</b>
 * （{@code RenderStationNameTiled} 只有 {@code showLogo} 一个 boolean 字段，字节码里没有别的钩子），
 * logo 的取图完全在 {@code drawResource} 里、命名空间写死 {@code mtr}。
 *
 * <h2>所以我们只换「取哪张图」，别的一行不动</h2>
 * <p>面板几何、站名文字、字号（矮板 0.5 / 高板 1.0 由 {@code STYLE} 决定）、底色、缓存、
 * 相邻连接全部留在 MTR 手里；{@code mixin.mtr.StationNameEntranceLogoMixin} 只把
 * {@code new Identifier("mtr", "textures/block/sign/logo.png")} 换成
 * {@code new Identifier("mtrlte", "textures/block/<brand>_logo.png")} —— 用的是<b>我们已有的</b>
 * logo 贴图，资源文件一个都不改。
 *
 * <h2>为什么是 ThreadLocal，还要跟着线程池走</h2>
 * <p>{@code generateStationNameEntrance} 是<b>静态</b>的，MTR 自己的牌子也会走同一条路；
 * 而 {@code ClientCache#getResource} 把画图丢给 {@code CompletableFuture.supplyAsync}
 * （{@code RouteAsyncScopeMixin} 的注释里有完整字节码），所以作用域必须由我们的渲染器在渲染线程压、
 * 由那条 mixin 带进工作线程（与 {@code XiamenRouteScope} 完全同一个套路）。
 */
public final class WallSignLogos {

    /** 当前线程正在画哪个品牌的牌子（{@code xiamen} / {@code jiaomei} / {@code lte}）；{@code null} = 不是我们的。 */
    private static final ThreadLocal<String> BRAND = new ThreadLocal<>();

    private WallSignLogos() {
    }

    /** 进入一次渲染：{@code brand} 为 {@code null} 时什么也不做（MTR 自己的牌子原样画）。 */
    public static void push(final String brand) {
        if (brand == null || brand.isEmpty()) {
            return;
        }
        BRAND.set(brand);
    }

    /** 退出一次渲染（与 {@link #push(String)} 严格配对）。 */
    public static void pop() {
        BRAND.remove();
    }

    /** 当前线程正在画的品牌（{@code null} = 不是我们的牌子，或还没进渲染）。 */
    public static String brand() {
        return BRAND.get();
    }

    /** 某个品牌 logo 的资源路径（相对命名空间，与 MTR {@code drawResource} 的用法一致）。 */
    public static String texturePath(final String logo) {
        // logo() 的取值已经是 “xiamen_logo” 这种完整贴图名，所以这里只补目录和扩展名。
        return "textures/block/" + logo + ".png";
    }

    /**
     * 纹理缓存键的后缀。
     *
     * <p>MTR 的 key 是 {@code station_name_entrance_<color>_<name>_<scale>}（javap
     * {@code ClientCache#getStationNameEntrance}：{@code ldc "station_name_entrance_%s_%s_%s" → String.format}），
     * <b>只有站名、字色、缩放，没有牌子</b>。同一个车站同时挂厦门牌和角美牌时，两张图的 key 完全一样 ——
     * 谁先生成谁赢，另一块就会显示别人的 logo。所以我们的牌子在 key 后面加 {@code _sign_<brand>}，
     * 各自缓存；MTR 自己的牌子（没压作用域）后缀是空串，行为和以前一字不差。
     */
    public static String cacheKeySuffix() {
        final String brand = BRAND.get();
        return brand == null ? "" : "_sign_" + brand;
    }
}
