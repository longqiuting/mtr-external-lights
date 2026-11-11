// Marker for "my renderer wants MTR's hard-coded mtr: texture ids rewritten".
package dev.js.mtrheadlight.xiamen;

/**
 * 由<b>自己的渲染器</b>实现，告诉 {@code XiamenTextures}「我这个渲染器该用哪个贴图命名空间」。
 *
 * <p>MTR 的 {@code RenderPSDAPGDoor#render} 里贴图 id 是死写的格式串
 * （{@code "mtr:textures/block/psd_door_%s_%s_%s.png"} 等 15 处），继承它以后没法从外面改。
 * 于是渲染器在调 {@code super.render} 之前用 {@link XiamenTextures#withNamespace} 把
 * {@link #lte$textureNamespace()} 塞进去，{@code RenderPsdDoorTextureMixin} 在那次调用里的
 * {@code String.format} 上把 id 换掉，渲染完立刻恢复。
 *
 * <ul>
 *   <li>{@link #lte$textureNamespace()} 返回 {@code "mtrlte"} → 用模组自带的
 *       {@code mtrlte:textures/block/...}（缺图自动退回 {@code mtr:}）；</li>
 *   <li>返回 {@code null} / {@code "mtr"} → 原样不动。</li>
 * </ul>
 *
 * <p>接口里<b>不出现任何 MC 类型</b>，所以它可以在客户端与服务端两侧安全加载；真正的资源查找在
 * {@link XiamenTextures}（只有客户端会用到）。
 */
public interface IXiamenTextureNamespace {

    /** 本渲染器优先使用的命名空间，默认 {@code "mtrlte"}。 */
    String lte$textureNamespace();
}
