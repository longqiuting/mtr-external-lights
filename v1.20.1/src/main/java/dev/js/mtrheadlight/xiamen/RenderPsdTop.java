// Ported from Minecraft Transit Railway (MTR), MIT License.
package dev.js.mtrheadlight.xiamen;

import net.minecraft.block.BlockState;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.world.World;

/**
 * 吊板的灯箱面 —— MTR {@code mtr.render.RenderRouteBase} / {@code RenderPSDTop} 里
 * 「站名 / 线路图」那一条的等价物。
 *
 * <h2>为什么自己画一条</h2>
 * <p>MTR 在这条带上贴的是<b>运行时生成的贴图</b>（车站名、线路图、方向箭头，要靠 MTR 的
 * {@code ClientData} 平台数据）。本模组没有那套数据，所以照 MTR 的<b>几何</b>画一条静态面板：
 * <ul>
 *   <li>位置：{@code z = 1.95px}（从方块中心往<b>站台</b>一侧 6px，正好贴在包边内侧面外 0.05px，
 *       和 MTR 的 {@code translate(-0.5, -offset, this.z)} 同一处）；</li>
 *   <li>高度：{@code y = 1.5 .. 8.5px}（MTR 的 {@code topPadding = 7.5/16}、
 *       {@code bottomPadding = 1.5/16} 再经 {@code rotateZ(180)} 翻过来）；</li>
 *   <li>{@code persistent != none} 时整条上移 {@code 0.46875} 格（{@code PERSISTENT_OFFSET_SMALL}）。</li>
 * </ul>
 */
public class RenderPsdTop implements BlockEntityRenderer<PsdTopBlock.TileEntityPsdTop> {

    private static final Identifier TEX_TOP = new Identifier("mtrlte", "textures/block/xiamen_psd_top.png");

    /** MTR 的 {@code sidePadding} = 0.125px（一条链的两端各留一点）。 */
    private static final float SIDE_PADDING = 0.125F;

    public RenderPsdTop(BlockEntityRendererFactory.Context context) {
    }

    @Override
    public void render(PsdTopBlock.TileEntityPsdTop entity, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light, int overlay) {
        World world = entity.getWorld();
        if (world == null) {
            return;
        }
        BlockPos pos = entity.getPos();
        BlockState state = world.getBlockState(pos);
        Direction facing = PsdProps.prop(state, Properties.HORIZONTAL_FACING);
        boolean persistent = PsdProps.prop(state, PsdTopBlock.PERSISTENT) != PsdTopBlock.Persistent.NONE;
        boolean airLeft = PsdProps.prop(state, PsdTopBlock.AIR_LEFT);
        boolean airRight = PsdProps.prop(state, PsdTopBlock.AIR_RIGHT);

        float x0 = -8.0F + (airLeft ? SIDE_PADDING : 0.0F);
        float x1 = 8.0F - (airRight ? SIDE_PADDING : 0.0F);
        float y0 = 1.5F + (persistent ? PsdTopBlock.PERSISTENT_OFFSET_SMALL * 16.0F : 0.0F);
        float y1 = 8.5F + (persistent ? PsdTopBlock.PERSISTENT_OFFSET_SMALL * 16.0F : 0.0F);

        matrices.push();
        matrices.translate(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-facing.asRotation()));

        VertexConsumer vc = vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(TEX_TOP));
        // 面板朝站台一侧（本空间的 -z），从站台看过去 u 必须右增，所以两个 u 反过来传
        PsdQuads.quadZ(matrices, vc, x0, x1, y0, y1, -1.95F, 1.0F, 0.0F, false, light, overlay);

        matrices.pop();
    }

    @Override
    public boolean rendersOutsideBoundingBox(PsdTopBlock.TileEntityPsdTop entity) {
        return true;
    }
}
