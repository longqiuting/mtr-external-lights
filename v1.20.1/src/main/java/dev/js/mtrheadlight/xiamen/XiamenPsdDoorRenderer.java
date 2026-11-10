package dev.js.mtrheadlight.xiamen;

import net.minecraft.block.BlockState;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.joml.Matrix4f;

/**
 * 门扇渲染器 —— 对应 MTR 的 {@code mtr:render/RenderPSDAPGDoor} 与天津地铁的
 * {@code ziyue.tjmetro.render.RenderTianjinPSDDoor}。
 *
 * <h2>和 MTR 一样的三件事</h2>
 * <ol>
 *   <li><b>门叶是「画」出来的，不是静态模型</b>：BlockEntityRenderer 每帧读
 *       {@code entity.getOpen(tickDelta)}，把整块板沿墙面平移 {@code open} 格
 *       （MTR：{@code storedMatrixTransformations.add(m -> m.translate(open * (side ? -1 : 1), 0, 0))}）；</li>
 *   <li><b>上下分段贴图</b>：MTR 在 {@code RenderPSDAPGDoor} 里用
 *       {@code String.format("mtr:textures/block/psd_door_%s_%s_%s.png", half ? "top" : "bottom", side ? "right" : "left", type)}
 *       选贴图；这里同理，同一个方块内下半 8px 用 {@code xiamen_psd_door_bottom}、
 *       上半 8px 用 {@code xiamen_psd_door_top}，正反面各一张（{@code _back}）；</li>
 *   <li><b>朝向变换</b>：MTR 用 {@code translate(0.5+x, y, 0.5+z) → rotateY(-facing.asRotation()) → rotateX(180)}。
 *       我们不用矩阵旋转，而是把「facing=west」这一基准系里的像素坐标按 blockstate 的
 *       y 旋转规则（0/90/180/270）直接映射到世界局部坐标，结果和 MTR 的几何完全重合
 *       （和 {@code mtr:block/psd_glass_left_base} 那块 2px 板在同一条平面上）。</li>
 * </ol>
 */
public class XiamenPsdDoorRenderer implements BlockEntityRenderer<XiamenPsdDoorEntity> {

    private static final Identifier TOP = new Identifier("mtrlte", "block/xiamen_psd_door_top");
    private static final Identifier TOP_BACK = new Identifier("mtrlte", "block/xiamen_psd_door_top_back");
    private static final Identifier BOTTOM = new Identifier("mtrlte", "block/xiamen_psd_door_bottom");
    private static final Identifier BOTTOM_BACK = new Identifier("mtrlte", "block/xiamen_psd_door_bottom_back");

    /**
     * 门板的两个面（west 基准系，像素）。
     *
     * <p>MTR 的固定玻璃 {@code mtr:block/psd_glass_left_base} 是 {@code from [2,0,0] to [4,16,16]}，
     * 并且把「east 面」(= x=4) 贴 {@code #front}、「west 面」(= x=2) 贴 {@code #back}。
     * 也就是说这套贴图的「正面」朝 +X。门叶要滑动到玻璃<b>后面</b>（真实屏蔽门就是这样），
     * 所以门板放在 x∈[0,2]：正面 = x=2 那个面（法线 +X，和玻璃正面同朝），背面 = x=0。
     * 这样开门时门叶和邻居的玻璃板不会共面打架（MTR 的门也是和玻璃错开 2px 的）。
     */
    private static final float FRONT_X = 2.0F;
    private static final float BACK_X = 0.0F;

    public XiamenPsdDoorRenderer(BlockEntityRendererFactory.Context ctx) {
    }

    @Override
    public void render(XiamenPsdDoorEntity entity, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light, int overlay) {
        final World world = entity.getWorld();
        if (world == null) {
            return;
        }
        final BlockState state = world.getBlockState(entity.getPos());
        if (!(state.getBlock() instanceof XiamenPsdDoorBlock)) {
            return;
        }

        final Direction facing = state.get(XiamenPsdDoorBlock.FACING);
        final boolean right = state.get(XiamenPsdDoorBlock.SIDE) == XiamenPsdDoorBlock.Side.RIGHT;
        final float open = entity.getOpen(tickDelta);

        // 沿墙滑出去：左扇往局部 +Z、右扇往 -Z（和 XiamenPsdDoorBlock.leftDirection 一致）；
        // 位移 1 格 = 16px。
        final float slide = (right ? -1.0F : 1.0F) * open * 16.0F;
        final float z0 = slide;
        final float z1 = 16.0F + slide;
        final float mid = 8.0F; // 上下分段的分界

        final Matrix4f m = matrices.peek().getPositionMatrix();
        final VertexConsumer bottomFront = vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(BOTTOM));
        final VertexConsumer topFront = vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(TOP));
        final VertexConsumer bottomBack = vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(BOTTOM_BACK));
        final VertexConsumer topBack = vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(TOP_BACK));

        // 正面（法线 +X，和玻璃正面同朝）：从 +X 侧看过去 +Z 在左手边，所以 u 随 +Z 递减。
        quad(bottomFront, m, facing, light, overlay,
                FRONT_X, 0.0F, z0, 1.0F, 1.0F,
                FRONT_X, 0.0F, z1, 0.0F, 1.0F,
                FRONT_X, mid, z1, 0.0F, 0.0F,
                FRONT_X, mid, z0, 1.0F, 0.0F,
                1.0F, 0.0F, 0.0F, 1.0F);
        quad(topFront, m, facing, light, overlay,
                FRONT_X, mid, z0, 1.0F, 1.0F,
                FRONT_X, mid, z1, 0.0F, 1.0F,
                FRONT_X, 16.0F, z1, 0.0F, 0.0F,
                FRONT_X, 16.0F, z0, 1.0F, 0.0F,
                1.0F, 0.0F, 0.0F, 1.0F);

        // 背面（法线 -X）：从 -X 侧看 +Z 在右手边，u 随 +Z 递增，否则字是镜像的。
        quad(bottomBack, m, facing, light, overlay,
                BACK_X, 0.0F, z0, 0.0F, 1.0F,
                BACK_X, 0.0F, z1, 1.0F, 1.0F,
                BACK_X, mid, z1, 1.0F, 0.0F,
                BACK_X, mid, z0, 0.0F, 0.0F,
                -1.0F, 0.0F, 0.0F, 0.85F);
        quad(topBack, m, facing, light, overlay,
                BACK_X, mid, z0, 0.0F, 1.0F,
                BACK_X, mid, z1, 1.0F, 1.0F,
                BACK_X, 16.0F, z1, 1.0F, 0.0F,
                BACK_X, 16.0F, z0, 0.0F, 0.0F,
                -1.0F, 0.0F, 0.0F, 0.85F);
    }

    @Override
    public boolean rendersOutsideBoundingBox(XiamenPsdDoorEntity entity) {
        return true;
    }

    /**
     * 把一个四边形（坐标是 west 基准系的像素，u/v 是 0..1 贴图坐标）按 facing 映射后画出来。
     *
     * <p>映射规则与 blockstate json 的 y 旋转一致（也是 {@code XiamenPsdBlock} 里注释过的那套）：
     * west 0°、north 90° → (x,z)=(16-z, x)、east 180° → (16-x, 16-z)、south 270° → (z, 16-x)。
     */
    private static void quad(VertexConsumer vc, Matrix4f m, Direction facing, int light, int overlay,
                             float x0, float y0, float z0, float u0, float v0,
                             float x1, float y1, float z1, float u1, float v1,
                             float x2, float y2, float z2, float u2, float v2,
                             float x3, float y3, float z3, float u3, float v3,
                             float nx, float ny, float nz, float shade) {
        vertex(vc, m, facing, x0, y0, z0, u0, v0, nx, ny, nz, light, overlay, shade);
        vertex(vc, m, facing, x1, y1, z1, u1, v1, nx, ny, nz, light, overlay, shade);
        vertex(vc, m, facing, x2, y2, z2, u2, v2, nx, ny, nz, light, overlay, shade);
        vertex(vc, m, facing, x3, y3, z3, u3, v3, nx, ny, nz, light, overlay, shade);
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, Direction facing,
                               float x, float y, float z, float u, float v,
                               float nx, float ny, float nz, int light, int overlay, float shade) {
        final float px;
        final float pz;
        switch (facing) {
            case NORTH -> {
                px = 16.0F - z;
                pz = x;
            }
            case EAST -> {
                px = 16.0F - x;
                pz = 16.0F - z;
            }
            case SOUTH -> {
                px = z;
                pz = 16.0F - x;
            }
            default -> {
                px = x;
                pz = z;
            }
        }
        final float mx;
        final float mz;
        switch (facing) {
            case NORTH -> {
                mx = -nz;
                mz = nx;
            }
            case EAST -> {
                mx = -nx;
                mz = -nz;
            }
            case SOUTH -> {
                mx = nz;
                mz = -nx;
            }
            default -> {
                mx = nx;
                mz = nz;
            }
        }
        vc.vertex(m, px / 16.0F, y / 16.0F, pz / 16.0F)
                .color(shade, shade, shade, 1.0F)
                .texture(u, v)
                .overlay(overlay)
                .light(light)
                .normal(mx, ny, mz)
                .next();
    }
}
