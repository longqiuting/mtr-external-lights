// 充电宝租借机的渲染器：按方块实例选 top / bottom 原始模型，绕格心按 facing 旋转。
package dev.js.mtrheadlight.xiamen.charger;

import dev.js.mtrheadlight.rawmodel.RawMesh;
import dev.js.mtrheadlight.rawmodel.RawModelJson;
import dev.js.mtrheadlight.rawmodel.RawModelRenderer;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.world.World;

/**
 * 充电宝租借机（{@code mtrlte:charger_top} / {@code mtrlte:charger_bottom}）的方块实体渲染器。
 *
 * <h2>两块各画各的</h2>
 * <p>上下是两个<b>独立方块</b>，各自一个方块实体，各自一份原始模型
 * （{@code rawmodels/charger_top.json} / {@code charger_bottom.json}）。两份模型的局部坐标
 * 都是「自己那一格的 0..16」（素材本来就这么切），所以这里只按方块实例选模型，
 * 不需要任何 y 偏移 —— 换句话说，只要玩家把两块叠起来，机器自然就是一台。
 *
 * <h2>朝向</h2>
 * <p>与其它厦门机器的 blockstate 约定一致：{@code facing=south → y=0, west → 90, north → 180,
 * east → 270}（模型原生朝 +Z）。原版 {@code "y": n} 等价于绕方块中心转 {@code -n} 度，
 * 所以这里是 {@code rotationDegrees(-FACING.asRotation())}。
 *
 * <h2>渲染层</h2>
 * <p>{@code RenderLayer.getEntityCutout(texture)}：贴图直绑（方块模型已经是空的，不进方块图集），
 * alpha 裁掉，背面剔除开着。贴图是 {@code mtrlte:block/charger}（模型里写的是
 * {@code mtrlte:charger}，由 {@link RawModelJson} 按「无 textures/ 前缀 → 补 textures/block/」
 * 解成 {@code textures/block/charger.png}）。
 */
public class ChargerRenderer implements BlockEntityRenderer<ChargerBlockEntity> {

    public ChargerRenderer(final BlockEntityRendererFactory.Context context) {
    }

    @Override
    public void render(final ChargerBlockEntity entity, final float tickDelta,
                       final MatrixStack matrices, final VertexConsumerProvider vertexConsumers,
                       final int light, final int overlay) {
        final World world = entity.getWorld();
        final BlockState state = world != null ? world.getBlockState(entity.getPos()) : entity.getCachedState();
        if (!(state.getBlock() instanceof ChargerBlock block)) {
            return;
        }

        final RawMesh mesh = RawModelJson.get(RawModelJson.id(block.rawModel()));
        if (mesh == null) {
            return;
        }

        final VertexConsumer consumer = vertexConsumers.getBuffer(RawModelRenderer.cutoutLayer(mesh));

        matrices.push();
        matrices.translate(0.5D, 0.0D, 0.5D);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-state.get(HorizontalFacingBlock.FACING).asRotation()));
        matrices.translate(-0.5D, 0.0D, -0.5D);
        RawModelRenderer.render(matrices, consumer, mesh, light, overlay);
        matrices.pop();
    }
}
