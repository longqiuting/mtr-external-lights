package dev.js.mtrheadlight.mixin.mtr;

import dev.js.mtrheadlight.xiamen.XiamenTextures;
import mtr.render.RenderPSDAPGDoor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 把 MTR 门扇渲染器里<b>写死的 {@code mtr:} 贴图 id</b> 换成模组自带的 {@code mtrlte:} 那一份。
 *
 * <h2>注入点（javap 3.6.4.2 / 1.20.1，生产 jar）</h2>
 * <pre>
 * public void render(T, float, class_4587, class_4597, int, int):
 *   216: ldc "mtr:textures/block/light_%s.png"      237: String.format(...)   240: new class_2960
 *   283: ldc "mtr:textures/block/psd_door_end_%s_%s_2_%s.png"   337: format    340: new class_2960
 *   433: ldc "mtr:textures/block/apg_door_light_%s.png"          455: format    458: new class_2960
 *   553: ldc "mtr:textures/block/psd_door_end_%s_%s_1_%s.png"    608: format    611: new class_2960
 *   641: ldc "mtr:textures/block/psd_door_%s_%s_%s.png"          696: format    699: new class_2960
 *   734: ldc "mtr:textures/block/sign/door_not_in_use.png"       737: new class_2960
 *   769: ldc "mtr:textures/block/apg_door_%s_%s.png"             806: format    809: new class_2960
 * </pre>
 * 每一个 id 都是「{@code String.format} 出字符串 → {@code new Identifier}」两步。
 *
 * <h2>为什么拦 {@code String.format} 而不是 {@code new Identifier}</h2>
 * <ul>
 *   <li>{@code new Identifier} 属于 MC 类，生产 jar 里叫 {@code net/minecraft/class_2960}；本模组没有为
 *       MTR 生成 refmap（MTR 未 remap），所以 {@code @At} 目标只能写成 intermediary 名 —— 那样
 *       {@code remap = false} 下编译期注解处理器找不到目标（它看的是 yarn 名的开发 jar），
 *       运行期才碰运气。</li>
 *   <li>{@code String.format} 是 <b>JDK 方法</b>，开发环境和生产环境字节码完全一样，注解处理器
 *       当场就能确认目标存在 —— 注入在编译期就被验证过，不靠猜。</li>
 *   <li>返回值是「最终要用的字符串」，所以这里直接改字符串，Identifier 的构造 / RenderLayer 的
 *       缓存 / {@code RenderTrains.scheduleRender} 全都不动。</li>
 * </ul>
 *
 * <h2>只作用于自己的门</h2>
 * 命名空间由本模组的门扇渲染器在调用 {@code super.render} 之前塞进 {@link XiamenTextures} 的
 * ThreadLocal（{@link XiamenTextures#withNamespace}），渲染完立刻恢复。MTR 自己注册的
 * {@code RenderPSDAPGDoor} 实例不会设置它 → <b>一条 id 都不改</b>，和资源包里别的门完全不冲突。
 * 注入丢失时由 {@code mtrlte.mtr.mixins.json} 的 {@code defaultRequire: 0} + {@code required: false}
 * 兜底：门还是跟着资源包走，不会崩。
 */
@Mixin(value = RenderPSDAPGDoor.class, remap = false)
public abstract class RenderPsdDoorTextureMixin {

    @Redirect(
            method = "render",
            at = @At(value = "INVOKE",
                    target = "Ljava/lang/String;format",
                    remap = false),
            remap = false)
    private static String lte$rewriteTextureId(final String format, final Object[] args) {
        return XiamenTextures.resolveActive(String.format(format, args));
    }
}
