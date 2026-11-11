package dev.js.mtrheadlight.mixin.mtr;

import dev.js.mtrheadlight.xiamen.XiamenRouteStrip;
import mtr.client.RouteMapGenerator;
import mtr.data.IGui;
import net.minecraft.client.texture.NativeImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 把 MTR 当场画出来的<b>线路条带位图</b>换成 {@link XiamenRouteStrip} 画的那一张。
 *
 * <h2>为什么拦这里（而不是另写一个渲染器）</h2>
 * <pre>
 *   RenderRouteBase#render            ← final，改不了
 *     ClientData.DATA_CACHE.getRouteMap(platformId, vertical, flip, aspectRatio, transparentWhite)
 *       ClientCache#getResource(key, supplier, defaultColour)   ← 缓存 / 纹理注册 / 尺寸
 *         RouteMapGenerator.generateRouteMap(...)               ← 位图内容，就是这里
 *     IDrawing.drawTexture(...)                                ← 面板几何 / UV / 着色
 *   RenderPSDTop#renderAdditional → DATA_CACHE.getColorStrip(platformId)   ← 底部色带
 * </pre>
 *
 * <p>面板几何、UV、色带、方向箭头、纹理缓存（{@code route_map_<id>_...} 这个 key）全部留在 MTR 手里，
 * 我们只换“画什么”。这样条带的比例天然跟着面板走（MTR 传进来的 {@code aspectRatio} 就是面板的
 * 宽/高），也不需要复制 {@code RenderRouteBase} 那一大段几何。</p>
 *
 * <h2>安全</h2>
 * <ul>
 *   <li><b>只作用于本模组的方块</b>：{@code XiamenRouteStrip.paint / directionArrow / colorStrip}
 *       第一行就检查 {@link dev.js.mtrheadlight.xiamen.XiamenRouteScope#active()} —— 那个标记由我们自己的
 *       渲染器（{@code XiamenPsdTopRenderer} / {@code XiamenApgGlassRenderer}）在
 *       {@code getRenderType} 里压栈、{@code renderAdditional} 里出栈。MTR 原版屏蔽门走的是 MTR 自己
 *       的渲染器实例，线程里永远是“不是我们的” → 一行都不改；</li>
 *   <li>{@code paint} 返回 null（纵向线路图 / 环线 / 数据没到 / 配置关掉 / 内部异常）→ <b>不取消</b>
 *       → MTR 照原样画它自己那张，绝不会出现紫黑格；</li>
 *   <li>{@code remap = false}：MTR 未 remap，方法名在开发环境和生产环境一致；</li>
 *   <li>配置项 {@code mtrlte.json → xiamenRouteStrip = false} 可一键回到原版画法。</li>
 * </ul>
 *
 * <p>注入用 {@code defaultRequire: 0} + {@code required: false} 兜底：MTR 换版本改了方法名时本条
 * 注入静默失效，条带回到原版，不会崩。</p>
 */
@Mixin(value = RouteMapGenerator.class, remap = false)
public abstract class RouteMapArtMixin {

    /** 线路条带（吊板 / APG 玻璃的 ROUTE 面板）。 */
    @Inject(method = "generateRouteMap", at = @At("HEAD"), cancellable = true, remap = false)
    private static void lte$routeMap(final long platformId, final boolean vertical, final boolean flip,
                                     final float aspectRatio, final boolean transparentWhite,
                                     final CallbackInfoReturnable<NativeImage> cir) {
        final NativeImage image = XiamenRouteStrip.paint(platformId, vertical, flip, aspectRatio);
        if (image != null) {
            cir.setReturnValue(image);
        }
    }

    /**
     * 面板下沿那条 1px 色带。
     *
     * <p>只在 {@code lineColors} 手工覆盖了颜色时才接管（否则原样交给 MTR）—— 不接管的话，
     * 覆盖过颜色的线路会出现“条带是新颜色、下沿色带还是旧颜色”。</p>
     */
    @Inject(method = "generateColorStrip", at = @At("HEAD"), cancellable = true, remap = false)
    private static void lte$colorStrip(final long platformId, final CallbackInfoReturnable<NativeImage> cir) {
        final NativeImage image = XiamenRouteStrip.colorStrip(platformId);
        if (image != null) {
            cir.setReturnValue(image);
        }
    }

    /**
     * <b>方向面板</b>（吊板 / 玻璃上那一块 {@code RenderType.ARROW}，以及站名为空时的
     * {@code STATION_NAME}）：MTR 的原图左侧带线路色圆 + 圆内<b>站台号</b>，文字从圆的右边左对齐。
     * 实拍吊板没有站台号、文字居中，所以整块重画（{@link dev.js.mtrheadlight.xiamen.XiamenDirectionPanel}）。
     *
     * <p>参数原样转交，不自己解析：{@code platformId} 用来查 MTR 的
     * {@code getFormattedRouteDestination}，{@code aspectRatio} 用来定尺寸。</p>
     */
    @Inject(method = "generateDirectionArrow", at = @At("HEAD"), cancellable = true, remap = false)
    private static void lte$directionArrow(final long platformId, final boolean hasLeft, final boolean hasRight,
                                           final IGui.HorizontalAlignment horizontalAlignment,
                                           final boolean showToString, final float paddingScale,
                                           final float aspectRatio, final int backgroundColor, final int textColor,
                                           final int transparentColor,
                                           final CallbackInfoReturnable<NativeImage> cir) {
        final NativeImage image = XiamenRouteStrip.directionArrow(platformId, hasLeft, hasRight, showToString,
                aspectRatio);
        if (image != null) {
            cir.setReturnValue(image);
        }
    }
}
