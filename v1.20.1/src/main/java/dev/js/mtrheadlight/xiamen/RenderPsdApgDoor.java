// APG (half-height) door renderer — MTR's RenderPSDAPGDoor with door type 2.
package dev.js.mtrheadlight.xiamen;

import mtr.block.BlockPSDAPGDoorBase;
import mtr.render.RenderPSDAPGDoor;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;

/**
 * 半高 APG 屏蔽门的门扇渲染器 —— <b>还是 MTR 的 {@code RenderPSDAPGDoor}</b>，只是把 door type 换成
 * {@code 2}。
 *
 * <h2>type 2 到底画什么（javap 3.6.4.2 生产 jar 的 {@code render} 分支）</h2>
 * <pre>
 *  171: getfield type: I
 *  174: tableswitch { 0: 200, 1: 200, 2: 368, default: 492 }   // 门灯：0/1 走 light_%s，2 走 apg_door_light_%s
 *  507: tableswitch { 0..4 }                                    // 门体几何：2 → APG 的上下两块
 *  765: ldc "mtr:textures/block/apg_door_%s_%s.png"              // ← 只有 type 2 会用到
 *  429: ldc "mtr:textures/block/apg_door_light_%s.png"
 * </pre>
 * 也就是说几何、UV、{@code open} 平移、门灯、「暂停使用」牌全部还是 MTR 那一份，差别只在
 * 「用 APG 的模型 + APG 的贴图」。
 *
 * <h2>贴图</h2>
 * 同样实现 {@link IXiamenTextureNamespace}，把 {@code mtr:/apg_door_*} 换成模组自带的
 * {@code mtrlte:/apg_door_*}（含 {@code apg_door_light_on/off}）。缺图自动退回资源包那份。
 *
 * <p>三条线路共用一个 {@code BlockEntityType}（见 {@link XiamenObjects}），所以这里也用 MTR 的
 * 基类作为泛型参数，线路变体（{@code _l2} / {@code _l3}）在渲染时按<b>方块实例</b>取，
 * 通过 {@link XiamenTextures#withVariant} 传给贴图改写点。
 */
public class RenderPsdApgDoor extends RenderPSDAPGDoor<BlockPSDAPGDoorBase.TileEntityPSDAPGDoorBase>
        implements IXiamenTextureNamespace {

    /** MTR 的 door type：2 = APG（{@code apg_door_<half>_<side>.png}，无吊板、2 格高）。 */
    private static final int TYPE = 2;

    public RenderPsdApgDoor(BlockEntityRendererFactory.Context context) {
        super(context.getRenderDispatcher(), TYPE);
    }

    @Override
    public String lte$textureNamespace() {
        return XiamenTextures.MOD_NAMESPACE;
    }

    @Override
    public void render(BlockPSDAPGDoorBase.TileEntityPSDAPGDoorBase entity, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light, int overlay) {
        final String variant = XiamenObjects.textureVariantOf(entity.getCachedState().getBlock());
        XiamenTextures.withVariant(variant, XiamenDoorLights.colorFor(entity.getPos()),
                () -> this.drawInMtr(entity, tickDelta, matrices, vertexConsumers, light, overlay));
    }

    /** 真正调用 MTR 渲染器的那一步（放在独立方法里：lambda 体内不能用 {@code super}）。 */
    private void drawInMtr(BlockPSDAPGDoorBase.TileEntityPSDAPGDoorBase entity, float tickDelta,
                           MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, int overlay) {
        super.render(entity, tickDelta, matrices, vertexConsumers, light, overlay);
    }
}
