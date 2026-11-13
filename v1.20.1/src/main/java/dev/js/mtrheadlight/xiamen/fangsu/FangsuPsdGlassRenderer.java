// 方速厦门屏蔽门的玻璃板渲染器：只画那一组静态几何，按 facing 绕 Y 转一次。
package dev.js.mtrheadlight.xiamen.fangsu;

import java.util.List;
import java.util.Map;

import dev.js.mtrheadlight.rawmodel.RawMesh;
import dev.js.mtrheadlight.rawmodel.RawModelRenderer;
import mtr.block.IBlock;
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
 * 玻璃板（{@code fangsu_psd_screendoor_high_glass_left / _right}）的方块实体渲染器。
 *
 * <h2>画什么</h2>
 * <p>和门扇完全同一套管线（{@link FangsuPsdBlocks#meshes} → {@code o} 组 → 按材质拆开的
 * {@link RawMesh}），<b>区别只有一条</b>：这里只画 {@link FangsuPsdDef#staticGroup()} 那一组
 * （{@code glass_left_glass} / {@code glass_right_glass}），没有任何门扇变换 ——
 * 玻璃板是不动的。
 *
 * <h2>朝向 / 渲染层</h2>
 * <p>和 {@link FangsuPsdRenderer} 逐字一致：绕方块中心 {@code (0.5, ·, 0.5)} 转
 * {@code NEGATIVE_Y × facing 角度}（blockstate 的 {@code y} 是俯视顺时针）、
 * {@code RenderLayer.getEntityCutout(贴图)} 直绑贴图（不进方块图集），alpha &lt; 0.1 裁掉；
 * 贴图名以 {@code ltg.png} 结尾的那份（灯带）在注册期被标成 {@code fullBright}，画出来是亮的。
 *
 * <p>没有 {@code HALF} 判断：玻璃板是<b>单格方块</b>，一块就把 3 格高的板画完（碰撞也是 3 格高，
 * 见 {@link FangsuPsdGlassBlock}）。
 */
public class FangsuPsdGlassRenderer implements BlockEntityRenderer<FangsuPsdGlassBlock.FangsuPsdGlassEntity> {

    public FangsuPsdGlassRenderer(final BlockEntityRendererFactory.Context context) {
    }

    @Override
    public void render(final FangsuPsdGlassBlock.FangsuPsdGlassEntity entity, final float tickDelta,
                       final MatrixStack matrices, final VertexConsumerProvider vertexConsumers,
                       final int light, final int overlay) {
        final World world = entity.getWorld();
        final BlockState state = world != null ? world.getBlockState(entity.getPos()) : entity.getCachedState();
        if (!(state.getBlock() instanceof FangsuPsdGlassBlock block)) {
            return;
        }
        final FangsuPsdDef def = block.def();
        if (def.staticGroup() == null) {
            return;
        }
        final Map<String, List<RawMesh>> meshes = FangsuPsdBlocks.meshes(def);
        final List<RawMesh> glass = meshes.get(def.staticGroup());
        if (glass == null) {
            return;
        }

        final Direction facing = IBlock.getStatePropertySafe(state, FangsuPsdGlassBlock.FACING);
        matrices.push();
        try {
            if (facing != Direction.NORTH) {
                matrices.translate(0.5F, 0.0F, 0.5F);
                matrices.multiply(RotationAxis.NEGATIVE_Y.rotationDegrees(degreesOf(facing)));
                matrices.translate(-0.5F, 0.0F, -0.5F);
            }
            for (final RawMesh mesh : glass) {
                final RenderLayer layer = RawModelRenderer.cutoutLayer(mesh);
                final VertexConsumer consumer = vertexConsumers.getBuffer(layer);
                RawModelRenderer.render(matrices, consumer, mesh, light, overlay);
            }
        } finally {
            matrices.pop();
        }
    }

    /** facing → 俯视顺时针角度（{@code north=0 / east=90 / south=180 / west=270}）。 */
    private static float degreesOf(final Direction facing) {
        return switch (facing) {
            case EAST -> 90.0F;
            case SOUTH -> 180.0F;
            case WEST -> 270.0F;
            default -> 0.0F;
        };
    }
}
