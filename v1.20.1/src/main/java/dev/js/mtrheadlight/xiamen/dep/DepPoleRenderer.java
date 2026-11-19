// 挂顶 DEP 挂杆的渲染器：rawmodel/dep_pole.json → RawMesh，按 facing 绕方块中心转 −blockRotation 度。
package dev.js.mtrheadlight.xiamen.dep;

import dev.js.mtrheadlight.client.RenderDistance;
import dev.js.mtrheadlight.rawmodel.RawMesh;
import dev.js.mtrheadlight.rawmodel.RawModelJson;
import dev.js.mtrheadlight.rawmodel.RawModelRenderer;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.world.World;

/**
 * 挂顶 DEP 挂杆的方块实体渲染器（路线与 {@code EyecandyRenderer} 完全一致）。
 *
 * <h2>画什么</h2>
 * <p>{@code assets/mtrlte/rawmodels/dep_pole.json}（Blockbench 原样导出：10 个元素，全部是同一根细杆
 * 绕 Y 轴不同角度摆出来的叶片，角度 {@code 135 / 154 / -11.75 / 7.25 / -87.5 / …} 不是 22.5° 的倍数）
 * 由 {@link RawModelJson} 解析成 {@link RawMesh}，每个元素在解析期就把旋转烘进顶点 —— 渲染器只做
 * 「一次矩阵 + 画四边形」。解析结果按模型 id 缓存，只读一次。
 *
 * <h2>朝向</h2>
 * <p>按 {@code facing} 绕方块中心（{@code 0.5, 0, 0.5}）转 −{@link DepPoleBlock#blockRotation} 度，
 * 和 {@link DepPoleBlock#getOutlineShape} 转轮廓用的是同一个角度（同一个 {@code (x, z) → (16 − z, x)}
 * 方向），否则模型转了框选不转。{@code facing=north} = 模型原始朝向 = 0°，矩阵一次都不动。
 *
 * <h2>渲染层 / 光照</h2>
 * <p>{@code RawModelRenderer.cutoutLayer(mesh)} = {@code RenderLayer.getEntityCutout(贴图)}：
 * 贴图直绑、不进方块图集（方块的 blockstate 模型是 {@code mtrlte:block/empty}），alpha &lt; 0.1 裁掉。
 * 光照用 BER 传进来的 {@code light}，颜色乘解析期算好的方向明暗（down 0.5 / up 1.0 / 南北 0.8 / 东西 0.6）。
 */
public class DepPoleRenderer implements BlockEntityRenderer<DepPoleBlockEntity> {

    @Override
    public boolean rendersOutsideBoundingBox(final DepPoleBlockEntity entity) {
        return true;
    }

    @Override
    public int getRenderDistance() {
        return RenderDistance.blocks();
    }

    @Override
    public boolean isInRenderDistance(final DepPoleBlockEntity entity, final net.minecraft.util.math.Vec3d cameraPos) {
        return RenderDistance.inRange(entity, cameraPos);
    }

    public DepPoleRenderer(final BlockEntityRendererFactory.Context context) {
    }

    @Override
    public void render(final DepPoleBlockEntity entity, final float tickDelta, final MatrixStack matrices,
                       final VertexConsumerProvider vertexConsumers, final int light, final int overlay) {
        final World world = entity.getWorld();
        final BlockState state = world != null ? world.getBlockState(entity.getPos()) : entity.getCachedState();
        if (!(state.getBlock() instanceof DepPoleBlock)) {
            return;
        }
        final RawMesh mesh = RawModelJson.get(RawModelJson.id(DepPoleObjects.MODEL));
        if (mesh == null) {
            return;
        }
        final RenderLayer layer = RawModelRenderer.cutoutLayer(mesh);
        final VertexConsumer consumer = vertexConsumers.getBuffer(layer);
        final Direction facing = state.contains(DepPoleBlock.FACING)
                ? state.get(DepPoleBlock.FACING) : Direction.NORTH;
        final int degrees = DepPoleBlock.blockRotation(facing);
        if (degrees != 0) {
            matrices.push();
            matrices.translate(0.5F, 0.0F, 0.5F);
            matrices.multiply(RotationAxis.NEGATIVE_Y.rotationDegrees(degrees));
            matrices.translate(-0.5F, 0.0F, -0.5F);
        }
        try {
            RawModelRenderer.render(matrices, consumer, mesh, light, overlay);
        } finally {
            if (degrees != 0) {
                matrices.pop();
            }
        }
    }
}
