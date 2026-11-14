package dev.js.mtrheadlight.mixin.mtr;

import dev.js.mtrheadlight.xiamen.sign.WallSignLogos;
import mtr.client.RouteMapGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 把 MTR 站名出入口贴图里<b>烤进去的那张 logo</b>换成我们自己的 —— 其余全照 MTR。
 *
 * <h2>MTR 原样（javap 3.6.4.2 / 1.20.1）</h2>
 * <pre>
 *   public static NativeImage generateStationNameEntrance(int color, String name, float scale)   // 没有 logo 参数
 *     …
 *     getstatic  RouteMapGenerator.scale:I
 *     invokestatic ClientData.DATA_CACHE.getTextPixels(...)        // 站名点阵
 *     new NativeImage(RGBA, width, height) → fillRect(0, 0, w, h, color)
 *     ldc        "textures/block/sign/logo.png"
 *     invokestatic RouteMapGenerator.drawResource(NativeImage, String, int, int, int, int,
 *                                                 boolean, float, float, int, boolean)   ← 就是这一句
 *     invokestatic drawString(...) / clearColor(invertColor(color))
 *
 *   private static void drawResource(NativeImage, String path, int, int, int, int, boolean, float, float, int, boolean):
 *     new Identifier("mtr", path)                                  // ← 命名空间写死 mtr
 *     MinecraftClient.getInstance().getResourceManager().getResource(id)
 *     NativeImage.read(RGBA, stream) → 逐像素混合进画布
 * </pre>
 *
 * <h2>为什么拦 {@code Identifier} 而不是拦几何 / 另写渲染器</h2>
 * <p>画布尺寸、logo 的落点（{@code (width - textWidth - logoSize) / 2}，与站名一起居中）、
 * 底色 / 清色、<b>缓存键</b>{@code station_name_entrance_<color>_<name>_<scale>}、面板几何与 UV
 * 全部还是 MTR 算的；这里只改「读哪张 PNG」：命名空间 {@code mtr → mtrlte}、
 * 路径 {@code textures/block/sign/logo.png → textures/block/<brand>_logo.png}。
 * 用的是资源包里<b>已经存在</b>的 {@code mtrlte:block/xiamen_logo} / {@code jiaomei_logo} /
 * {@code lte_logo}，一个资源文件都不新增、不修改。
 *
 * <h2>只作用于我们自己的牌子</h2>
 * <p>{@link WallSignLogos#brand()} 由 {@code WallSignRenderer} 在渲染线程压栈、
 * 由 {@code RouteAsyncScopeMixin} 带进 MTR 的纹理生成线程（{@code getResource} 走
 * {@code CompletableFuture.supplyAsync}）。MTR 自己注册的 {@code RenderStationNameTiled} 实例不会压栈，
 * 所以原版出入口牌子一点不变。
 *
 * <p>{@code defaultRequire: 0} + {@code required: false} 兜底：注入丢失时我们的牌子显示 MTR 原版 logo，
 * 不会崩、不会紫黑格。
 */
@Mixin(value = RouteMapGenerator.class, remap = false)
public abstract class StationNameEntranceLogoMixin {

    /** {@code new Identifier("mtr", path)} 的<b>命名空间</b>：我们的牌子换成 {@code mtrlte}。 */
    @ModifyArg(
            method = "drawResource",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/util/Identifier;<init>(Ljava/lang/String;Ljava/lang/String;)V",
                    remap = true),
            index = 0,
            remap = false)
    private static String lte$brandLogoNamespace(final String namespace) {
        final String brand = WallSignLogos.brand();
        if (brand == null || !"mtr".equals(namespace)) {
            return namespace;
        }
        return "mtrlte";
    }

    /** {@code new Identifier(namespace, path)} 的<b>路径</b>：{@code textures/block/sign/logo.png} → 我们的品牌图。 */
    @ModifyArg(
            method = "drawResource",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/util/Identifier;<init>(Ljava/lang/String;Ljava/lang/String;)V",
                    remap = true),
            index = 1,
            remap = false)
    private static String lte$brandLogoPath(final String path) {
        final String brand = WallSignLogos.brand();
        if (brand == null || path == null || !path.endsWith("sign/logo.png")) {
            return path;
        }
        return WallSignLogos.texturePath(brand);
    }
}
