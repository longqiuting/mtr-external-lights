// 余额查询机的渲染器：按 HALF 选 bottom / top 两份原始模型，各画自己那一格。
package dev.js.mtrheadlight.xiamen.machine;

import dev.js.mtrheadlight.client.RenderDistance;
import dev.js.mtrheadlight.rawmodel.RawMesh;
import dev.js.mtrheadlight.rawmodel.RawModelJson;
import dev.js.mtrheadlight.rawmodel.RawModelRenderer;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.world.World;

/**
 * 余额查询机（{@code mtrlte:balance_machine_xiamen}）的方块实体渲染器。
 *
 * <h2>两格高怎么画</h2>
 * <p>一个方块 id、两格高（{@code half=lower|upper}），<b>两段各有一个方块实体</b>：
 * 下段画 {@code rawmodels/balance_machine_bottom.json}，上段画 {@code balance_machine_top.json}。
 * 两份模型的局部坐标都是「自己那一格的 0..16」（素材本来就是这么切的），所以这里只按 HALF 选模型，
 * 不需要任何 y 偏移 —— 机器跨格、屏幕斜出去都不会被格边界裁掉（这正是原版 blockstate 模型做不到的）。
 *
 * <h2>朝向</h2>
 * <p>与旧 blockstate 完全一致：{@code facing=south → y=0, west → 90, north → 180, east → 270}
 * （= {@code FACING.asRotation()}，模型原生朝 +Z）。原版的 {@code "y": n} 等价于绕方块中心
 * <b>转 -n 度</b>，所以这里是 {@code rotationDegrees(-FACING.asRotation())}。
 *
 * <h2>渲染层</h2>
 * <p>{@code RenderLayer.getEntityCutout(texture)}：贴图直绑（不进方块图集，
 * 方块模型已经是空的），alpha 裁掉；背面剔除开着，和原版模型渲染同一份 JSON 的效果一致
 * （闸机那边同理，见 {@code XiamenTicketGateRenderer} 里关于零厚度卡片的说明）。
 */
public class XiamenBalanceMachineRenderer implements BlockEntityRenderer<XiamenBalanceMachineBlockEntity> {

    @Override
    public boolean rendersOutsideBoundingBox(final XiamenBalanceMachineBlockEntity entity) {
        return true;
    }

    @Override
    public int getRenderDistance() {
        return RenderDistance.blocks();
    }

    @Override
    public boolean isInRenderDistance(final XiamenBalanceMachineBlockEntity entity, final net.minecraft.util.math.Vec3d cameraPos) {
        return RenderDistance.inRange(entity, cameraPos);
    }

    private static final String MODEL_BASE = "balance_machine_";

    public XiamenBalanceMachineRenderer(final BlockEntityRendererFactory.Context context) {
    }

    @Override
    public void render(final XiamenBalanceMachineBlockEntity entity, final float tickDelta,
                       final MatrixStack matrices, final VertexConsumerProvider vertexConsumers,
                       final int light, final int overlay) {
        final World world = entity.getWorld();
        final BlockState state = world != null ? world.getBlockState(entity.getPos()) : entity.getCachedState();
        if (!(state.getBlock() instanceof XiamenBalanceMachineBlock)) {
            return;
        }

        final boolean upper = state.contains(XiamenMachineBlock.HALF)
                && state.get(XiamenMachineBlock.HALF) == DoubleBlockHalf.UPPER;
        final RawMesh mesh = RawModelJson.get(RawModelJson.id(MODEL_BASE + (upper ? "top" : "bottom")));
        if (mesh == null) {
            return;
        }

        final VertexConsumer consumer =
                vertexConsumers.getBuffer(RawModelRenderer.cutoutLayer(mesh));

        matrices.push();
        matrices.translate(0.5D, 0.0D, 0.5D);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-state.get(HorizontalFacingBlock.FACING).asRotation()));
        matrices.translate(-0.5D, 0.0D, -0.5D);
        RawModelRenderer.render(matrices, consumer, mesh, light, overlay);
        matrices.pop();
    }
}
