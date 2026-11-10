// Xiamen door renderer — MTR's geometry / animation, Xiamen textures.
package dev.js.mtrheadlight.xiamen;

import mtr.MTRClient;
import mtr.block.BlockPSDAPGDoorBase;
import mtr.block.IBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.client.render.LightmapTextureManager;
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
 * 厦门门扇的渲染器 —— MTR {@code mtr.render.RenderPSDAPGDoor} 的几何 + 动画 + 门灯，
 * 贴图换成厦门资源包的 {@code psd_door_*}。
 *
 * <h2>为什么是「另一份渲染器」而不是直接继承</h2>
 * <p>{@code RenderPSDAPGDoor} 里贴图路径是写死的 {@code String.format("mtr:textures/block/psd_door_%s_%s_%s.png")}，
 * 没有可覆盖的字段 / 方法；模型（{@code ModelSingleCube}）又都是 private。所以这里照它的
 * 几何与贴图布局重画一遍，<b>真正做决策的东西仍然全在 MTR 里</b>：
 * <ul>
 *   <li>方块状态（{@code SIDE} / {@code HALF} / {@code END} / {@code UNLOCKED}）→ MTR 的
 *       {@code BlockPSDAPGDoorBase}；</li>
 *   <li>开度 {@code open} / 插值 {@code openClient} → MTR 的
 *       {@code BlockPSDAPGDoorBase.TileEntityPSDAPGDoorBase#getOpen(float)}
 *       （列车调 {@code setOpen}，这里只跟着画）；</li>
 *   <li>门灯亮灭 → 同一套 {@code getOpenValue()}（MTR 的 private {@code isDoorLightOn}）；
 *       帧间隔用 {@code MTRClient#getLastFrameDuration()}，和 MTR 自己的门完全同步。</li>
 * </ul>
 *
 * <h2>贴图（盒子 UV，与 MTR 的 ModelSingleCube 同一套数字）</h2>
 * <table border="1">
 *   <caption>MTR 的 {@code new ModelSingleCube(2(d+w), d+h, x, y, z, w, h, d)}，16 倍缩放的图集</caption>
 *   <tr><th>部件</th><th>图集</th><th>单位</th></tr>
 *   <tr><td>上段门扇（普通）</td><td>{@code psd_door_top_left_1.png} / {@code _right_1}</td><td>36 × 18</td></tr>
 *   <tr><td>下段门扇（普通）</td><td>{@code psd_door_bottom_left_1.png} / {@code _right_1}</td><td>36 × 18</td></tr>
 *   <tr><td>上段端部门扇 主扇</td><td>{@code psd_door_end_top_{left,right}_1_1.png}</td><td>20 × 18</td></tr>
 *   <tr><td>上段端部门扇 副扇</td><td>{@code psd_door_end_top_{left,right}_2_1.png}</td><td>20 × 18</td></tr>
 *   <tr><td>下段端部门扇 主/副</td><td>{@code psd_door_end_bottom_{left,right}_{1,2}_1.png}</td><td>20 × 18</td></tr>
 *   <tr><td>门灯</td><td>{@code light_on.png} / {@code light_off.png}</td><td>整张</td></tr>
 *   <tr><td>「暂停使用」牌</td><td>{@code sign/door_not_in_use.png}</td><td>整张</td></tr>
 * </table>
 *
 * <h2>几何（数字全部照抄 MTR 的 ModelSingleCube）</h2>
 * <table border="1">
 *   <caption>单位是 1/16 格；+z 朝外（朝轨道），+x 沿门宽</caption>
 *   <tr><th>部件</th><th>x</th><th>y</th><th>z</th></tr>
 *   <tr><td>整扇门</td><td>-8..8</td><td>0..16</td><td>6..8</td></tr>
 *   <tr><td>端部门 主扇</td><td>-8..0 / 0..8</td><td>0..16</td><td>6..8</td></tr>
 *   <tr><td>端部门 副扇</td><td>0..8 / -8..0</td><td>0..16</td><td>4..6</td></tr>
 *   <tr><td>门灯</td><td>-8..-7 / 7..8</td><td>16..17</td><td>2..3</td></tr>
 *   <tr><td>「暂停使用」牌</td><td>-3..3</td><td>4..10</td><td>2..3</td></tr>
 * </table>
 */
public class RenderPsdDoor implements BlockEntityRenderer<XiamenPsdBlocks.TileEntityXiamenPSDDoor> {

    /** 门板厚度 2px（MTR 的 MODEL_PSD 的 d 参数）。 */
    private static final float THICK = 2.0F;
    /** 普通门扇：盒宽 16 → 图集 36 × 18 单位。 */
    private static final PsdQuads.UvSet UV_DOOR = PsdQuads.UvSet.box(16.0F, 16.0F, THICK);
    /** 端部门扇：盒宽 8 → 图集 20 × 18 单位。 */
    private static final PsdQuads.UvSet UV_END = PsdQuads.UvSet.box(8.0F, 16.0F, THICK);

    private static final Identifier TEX_DOOR_TOP_LEFT = tex("psd_door_top_left_1");
    private static final Identifier TEX_DOOR_TOP_RIGHT = tex("psd_door_top_right_1");
    private static final Identifier TEX_DOOR_BOTTOM_LEFT = tex("psd_door_bottom_left_1");
    private static final Identifier TEX_DOOR_BOTTOM_RIGHT = tex("psd_door_bottom_right_1");
    private static final Identifier TEX_END_TOP_LEFT_1 = tex("psd_door_end_top_left_1_1");
    private static final Identifier TEX_END_TOP_RIGHT_1 = tex("psd_door_end_top_right_1_1");
    private static final Identifier TEX_END_TOP_LEFT_2 = tex("psd_door_end_top_left_2_1");
    private static final Identifier TEX_END_TOP_RIGHT_2 = tex("psd_door_end_top_right_2_1");
    private static final Identifier TEX_END_BOTTOM_LEFT_1 = tex("psd_door_end_bottom_left_1_1");
    private static final Identifier TEX_END_BOTTOM_RIGHT_1 = tex("psd_door_end_bottom_right_1_1");
    private static final Identifier TEX_END_BOTTOM_LEFT_2 = tex("psd_door_end_bottom_left_2_1");
    private static final Identifier TEX_END_BOTTOM_RIGHT_2 = tex("psd_door_end_bottom_right_2_1");
    private static final Identifier TEX_LIGHT_ON = tex("light_on");
    private static final Identifier TEX_LIGHT_OFF = tex("light_off");
    private static final Identifier TEX_LOCKED = tex("sign/door_not_in_use");

    public RenderPsdDoor(BlockEntityRendererFactory.Context context) {
    }

    private static Identifier tex(String path) {
        return new Identifier("mtrlte", "textures/block/" + path + ".png");
    }

    @Override
    public void render(XiamenPsdBlocks.TileEntityXiamenPSDDoor entity, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light, int overlay) {
        World world = entity.getWorld();
        if (world == null) {
            return;
        }
        BlockPos pos = entity.getPos();
        BlockState state = world.getBlockState(pos);
        Direction facing = IBlock.getStatePropertySafe(state, Properties.HORIZONTAL_FACING);
        boolean side = IBlock.getStatePropertySafe(state, IBlock.SIDE) == IBlock.EnumSide.RIGHT;
        boolean half = IBlock.getStatePropertySafe(state, IBlock.HALF) == DoubleBlockHalf.UPPER;
        boolean end = IBlock.getStatePropertySafe(state, BlockPSDAPGDoorBase.END);
        boolean unlocked = IBlock.getStatePropertySafe(state, BlockPSDAPGDoorBase.UNLOCKED);
        float open = Math.min(entity.getOpen(MTRClient.getLastFrameDuration()), 1.0F);
        float direction = side ? -1.0F : 1.0F;

        matrices.push();
        matrices.translate(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-facing.asRotation()));

        VertexConsumer front = vertexConsumers.getBuffer(
                RenderLayer.getEntityCutoutNoCull(atlas1(half, end, side)));
        VertexConsumer back = vertexConsumers.getBuffer(
                RenderLayer.getEntityCutoutNoCull(atlas2(half, end, side)));

        // 1) 门灯（MTR：只在上段画，1×1×1 的小方块挂在格顶内侧，正好藏在吊板那 1px 缝里）
        if (half) {
            boolean lightOn = isDoorLightOn(entity);
            VertexConsumer lightVc = vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(
                    lightOn ? TEX_LIGHT_ON : TEX_LIGHT_OFF));
            // MTR：亮 = QueuedRenderLayer.LIGHT（全亮），灭 = EXTERIOR（跟随环境光）
            int lightCoord = lightOn ? LightmapTextureManager.MAX_LIGHT_COORDINATE : light;
            float lx = side ? 7.0F : -8.0F;
            PsdQuads.cube(matrices, lightVc, lx, lx + 1.0F, 16.0F, 17.0F, 2.0F, 3.0F,
                    0.0F, 1.0F, lightCoord, overlay);
        }

        // 2) 端部门的副扇（MTR：不跟着整扇平移，只走一半；z 比主扇靠里 2px）
        if (end) {
            VertexConsumer front2 = vertexConsumers.getBuffer(
                    RenderLayer.getEntityCutoutNoCull(atlas3(half, side)));
            VertexConsumer back2 = front2;
            matrices.push();
            matrices.translate(open / 2.0F * direction, 0.0F, 0.0F);
            float x0 = side ? -8.0F : 0.0F;
            PsdQuads.plate(matrices, front2, back2, x0, x0 + 8.0F, 0.0F, 16.0F, 4.0F, 6.0F,
                    UV_END, light, overlay);
            matrices.pop();
        }

        // 3) 整扇门（端头只画半扇；主扇随 open 走满 1 格）
        matrices.push();
        matrices.translate(open * direction, 0.0F, 0.0F);
        if (end) {
            float x0 = side ? 0.0F : -8.0F;
            PsdQuads.plate(matrices, front, back, x0, x0 + 8.0F, 0.0F, 16.0F, 6.0F, 8.0F,
                    UV_END, light, overlay);
        } else {
            PsdQuads.plate(matrices, front, back, -8.0F, 8.0F, 0.0F, 16.0F, 6.0F, 8.0F,
                    UV_DOOR, light, overlay);
        }

        // 4) 「暂停使用」牌（锁着的门；MTR 是个 6×6×1 的小立方体，两面都能看见）
        if (half && !unlocked) {
            VertexConsumer lockedVc = vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(TEX_LOCKED));
            float xOffset = end ? (side ? 2.0F : -2.0F) : 0.0F;
            PsdQuads.cube(matrices, lockedVc, -3.0F + xOffset, 3.0F + xOffset, 4.0F, 10.0F,
                    2.0F, 3.0F, 0.0F, 1.0F, light, overlay);
        }
        matrices.pop();
        matrices.pop();
    }

    /** 正面图集：普通门 / 端部主扇，上段还是下段，左扇还是右扇。 */
    private static Identifier atlas1(boolean half, boolean end, boolean side) {
        if (end) {
            return half ? (side ? TEX_END_TOP_RIGHT_1 : TEX_END_TOP_LEFT_1)
                    : (side ? TEX_END_BOTTOM_RIGHT_1 : TEX_END_BOTTOM_LEFT_1);
        }
        return half ? (side ? TEX_DOOR_TOP_RIGHT : TEX_DOOR_TOP_LEFT)
                : (side ? TEX_DOOR_BOTTOM_RIGHT : TEX_DOOR_BOTTOM_LEFT);
    }

    /**
     * 背面图集。厦门包的 {@code psd_door_*_1.png} 是全盒子图集，背面就在同一张图的右半，
     * 所以正反面共用一张（MTR 的 3.6 图集也是这样，{@code _back} 那套是旧格式遗留）。
     */
    private static Identifier atlas2(boolean half, boolean end, boolean side) {
        return atlas1(half, end, side);
    }

    /** 端部副扇图集（{@code _2_1}）。 */
    private static Identifier atlas3(boolean half, boolean side) {
        return half ? (side ? TEX_END_TOP_RIGHT_2 : TEX_END_TOP_LEFT_2)
                : (side ? TEX_END_BOTTOM_RIGHT_2 : TEX_END_BOTTOM_LEFT_2);
    }

    @Override
    public boolean rendersOutsideBoundingBox(XiamenPsdBlocks.TileEntityXiamenPSDDoor entity) {
        return true;
    }

    /** MTR 的 {@code isDoorLightOn}：关着 = 灭，全开 = 亮，中间闪（500ms 一个周期）。 */
    private static boolean isDoorLightOn(XiamenPsdBlocks.TileEntityXiamenPSDDoor entity) {
        int openValue = entity.getOpenValue();
        if (openValue <= 0) {
            return false;
        }
        if (openValue >= BlockPSDAPGDoorBase.MAX_OPEN_VALUE) {
            return true;
        }
        return System.currentTimeMillis() / 500L % 2L == 0L;
    }
}
