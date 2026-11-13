// eyecandy 的渲染器：按方块实例上的描述取 OBJ 网格，原样画在方块位置（没有朝向 / 没有旋转）。
package dev.js.mtrheadlight.xiamen.eyecandy;

import dev.js.mtrheadlight.rawmodel.RawMesh;
import dev.js.mtrheadlight.rawmodel.RawModelRenderer;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.world.World;

/**
 * 全部 eyecandy 共用的方块实体渲染器。
 *
 * <h2>画什么</h2>
 * <p>方块实例上的 {@link EyecandyEntry} → {@link EyecandyBlocks#mesh} → {@code assets/mtrlte/eyecandies/*.obj}
 * 解析出来的 {@link RawMesh}。{@code scale / rotation / translation} 已经在解析期烘进顶点
 * （见 {@code RawObjParser}），方块本身也没有朝向属性，所以这里<b>什么矩阵都不用动</b>：
 * BER 的 {@code matrices} 原点就是方块西北下角，网格本身在模型空间 0..16 里摆好了。
 *
 * <h2>渲染层</h2>
 * <p>{@code RenderLayer.getEntityCutout(texture)}（{@link RawModelRenderer#cutoutLayer} 按贴图缓存了
 * RenderLayer 实例）：贴图直绑、不进方块图集（方块的 blockstate 模型是 {@code mtrlte:block/empty}，
 * 图集里没有这些图），alpha &lt; 0.1 裁掉，背面剔除开着 —— OBJ 的绕序是朝外的，
 * 圆柱 / 薄片都靠剔除两面各画一次才不会 z-fighting。
 *
 * <h2>光照</h2>
 * <p>用 BER 传进来的 {@code light}（方块位置的天光 / 方块光），颜色乘解析期算好的方向明暗
 * （原版那套 down 0.5 / up 1.0 / 南北 0.8 / 东西 0.6）。OBJ 没有自发光信息，所以没有 fullBright 的面。
 *
 * <h2>远处 / 面数</h2>
 * <p>共享单车那种一个模型 3446 个面、60 个加起来的量级对 BER 来说没问题（闸机那份 JSON 也是几千个面）。
 * 网格按 obj id 缓存，只解析一次。
 */
public class EyecandyRenderer implements BlockEntityRenderer<EyecandyBlockEntity> {

    public EyecandyRenderer(final BlockEntityRendererFactory.Context context) {
    }

    @Override
    public void render(final EyecandyBlockEntity entity, final float tickDelta, final MatrixStack matrices,
                       final VertexConsumerProvider vertexConsumers, final int light, final int overlay) {
        final World world = entity.getWorld();
        final BlockState state = world != null ? world.getBlockState(entity.getPos()) : entity.getCachedState();
        if (!(state.getBlock() instanceof EyecandyBlock block)) {
            return;
        }
        final RawMesh mesh = EyecandyBlocks.mesh(block.entry());
        if (mesh == null) {
            return;
        }
        final RenderLayer layer = RawModelRenderer.cutoutLayer(mesh);
        final VertexConsumer consumer = vertexConsumers.getBuffer(layer);
        RawModelRenderer.render(matrices, consumer, mesh, light, overlay);
    }
}
