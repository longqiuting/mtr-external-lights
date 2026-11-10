package dev.js.mtrheadlight.xiamen;

import net.minecraft.block.BlockState;
import net.minecraft.block.enums.DoubleBlockHalf;
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
 * 门扇渲染器 —— 对应 MTR 的 {@code mtr:render/RenderPSDAPGDoor}。
 *
 * <h2>上下分段贴图怎么用（javap / 资源包实证）</h2>
 * <p>MTR 在 {@code RenderPSDAPGDoor#render} 里按 {@code HALF} 选贴图：
 * <pre>
 * String.format("mtr:textures/block/psd_door_%s_%s_%s.png",
 *                half == UPPER ? "top" : "bottom",        // ← 第一段：上半 / 下半
 *                side == RIGHT ? "right" : "left",        // ← 第二段：右扇 / 左扇
 *                type == PSD_2 ? "2" : "1");              // ← 第三段：样式
 * </pre>
 * 也就是说 {@code psd_door_top_1.png} 画的是<b>上面那一格方块</b>（y+1）、
 * {@code psd_door_bottom_1.png} 画的是<b>下面那一格</b>（y+0），两张都是 16×16 的整面板贴图，
 * 不是「一格内部再切两半」。MTR 的模型 json 也印证了这一点：
 * {@code psd_glass_bottom_left_1.json} 把 {@code #front} 指到 {@code psd_glass_bottom_1}、
 * {@code psd_glass_top_left_1.json} 指到 {@code psd_glass_top_1}，两者的 {@code elements}
 * 都是同一个 {@code from [2,0,0] to [4,16,16]}。
 * 端部门则是 {@code psd_door_end_%s_%s_2_%s.png}（{@code BlockPSDAPGDoorBase.END} 为 true 时）。
 *
 * <p>所以这里：{@code half=lower} 的方块整格用 {@code xiamen_psd_door_bottom}，
 * {@code half=upper} 的整格用 {@code xiamen_psd_door_top}；
 * {@code end=true} 的整格改用 {@code xiamen_psd_end}（厦门这套的 {@code psd_door_end_*}）。
 *
 * <h2>开门的滑动</h2>
 * <p>MTR：{@code matrices.translate(open * (side == RIGHT ? -1 : 1), 0, 0)}，
 * {@code open} 是 {@code TileEntityPSDAPGDoorBase.getOpen(tickDelta)} 插值出来的 0..1
 * （BE 里存 0..32 的 {@code open} 与 {@code openClient}）。这里把同样的量换到「基准系本地 z」上：
 * 左扇（{@code side=LEFT}）往 {@code -z} 滑、右扇往 {@code +z} 滑，两扇背向分开，
 * 各滑 16px（正好一格，钻进旁边玻璃板后面）。
 */
public class XiamenPsdDoorRenderer implements BlockEntityRenderer<XiamenPsdDoorEntity> {

    private static final Identifier TOP = new Identifier("mtrlte", "block/xiamen_psd_door_top");
    private static final Identifier TOP_BACK = new Identifier("mtrlte", "block/xiamen_psd_door_top_back");
    private static final Identifier BOTTOM = new Identifier("mtrlte", "block/xiamen_psd_door_bottom");
    private static final Identifier BOTTOM_BACK = new Identifier("mtrlte", "block/xiamen_psd_door_bottom_back");
    /** 端部门（{@code end=true}）：厦门资源包里 {@code psd_door_end_top_*} 那套。 */
    private static final Identifier END = new Identifier("mtrlte", "block/xiamen_psd_end");
    private static final Identifier END_BACK = new Identifier("mtrlte", "block/xiamen_psd_end_back");

    /**
     * 门叶的两个面（基准系 facing=west 的像素）。
     *
     * <p>玻璃板在 x∈[2,4]（MTR 的 {@code psd_glass_left_base}），门叶画在 x∈[0,2]：
     * 正面 = x=2（法线 +X，和玻璃正面同朝），背面 = x=0。开门时门叶沿 z 滑到隔壁格，
     * 正好躲在玻璃后面而不是穿模。
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
        if (!(state.getBlock() instanceof XiamenPsdDoorBlock door)) {
            return;
        }

        final Direction facing = state.get(XiamenPsdBaseBlock.FACING);
        final boolean right = state.get(XiamenPsdDoorBlock.SIDE) == XiamenPsdBaseBlock.EnumSide.RIGHT;
        final boolean end = state.get(XiamenPsdDoorBlock.END);
        final boolean upper = state.get(XiamenPsdBaseBlock.HALF) == DoubleBlockHalf.UPPER;
        final float open = entity.getOpen(tickDelta);

        final Identifier front = end ? END : (upper ? TOP : BOTTOM);
        final Identifier back = end ? END_BACK : (upper ? TOP_BACK : BOTTOM_BACK);

        // 沿墙滑出去：左扇 -z、右扇 +z（见类注释）；1 格 = 16px。
        final float slide = (right ? 1.0F : -1.0F) * open * 16.0F;
        final float z0 = slide;
        final float z1 = 16.0F + slide;

        final Matrix4f m = matrices.peek().getPositionMatrix();
        final VertexConsumer frontBuf = vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(front));
        final VertexConsumer backBuf = vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(back));

        // 正面（法线 +X）：从 +X 侧看，+Z 在左手边 → u 随 +Z 递减
        quad(frontBuf, m, facing, light, overlay,
                FRONT_X, 0.0F, z0, 1.0F, 1.0F,
                FRONT_X, 0.0F, z1, 0.0F, 1.0F,
                FRONT_X, 16.0F, z1, 0.0F, 0.0F,
                FRONT_X, 16.0F, z0, 1.0F, 0.0F,
                1.0F, 0.0F, 0.0F, 1.0F);

        // 背面（法线 -X）：u 随 +Z 递增，否则字是镜像的
        quad(backBuf, m, facing, light, overlay,
                BACK_X, 0.0F, z0, 0.0F, 1.0F,
                BACK_X, 0.0F, z1, 1.0F, 1.0F,
                BACK_X, 16.0F, z1, 1.0F, 0.0F,
                BACK_X, 16.0F, z0, 0.0F, 0.0F,
                -1.0F, 0.0F, 0.0F, 0.85F);
    }

    @Override
    public boolean rendersOutsideBoundingBox(XiamenPsdDoorEntity entity) {
        return true;
    }

    /**
     * 把一个四边形（基准系像素坐标 + 0..1 贴图坐标）按 facing 映射后画出来。
     *
     * <p>映射与 blockstate json 的 y 旋转一致：west 0°、north 90° → (x,z)=(16-z, x)、
     * east 180° → (16-x, 16-z)、south 270° → (z, 16-x)。
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
