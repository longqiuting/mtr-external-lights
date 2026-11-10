// Ported from Minecraft Transit Railway (MTR), MIT License.
package dev.js.mtrheadlight.xiamen;

import net.minecraft.block.BlockState;
import net.minecraft.block.enums.DoubleBlockHalf;
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
 * MTR {@code mtr.render.RenderPSDAPGDoor}（type 0 = PSD 样式 1）的移植。
 *
 * <h2>几何（数字全部照抄 MTR 的 ModelSingleCube）</h2>
 * <table border="1">
 *   <caption>单位是 1/16 格；+z 朝外（朝轨道），+x 沿门宽</caption>
 *   <tr><th>部件</th><th>x</th><th>y</th><th>z</th></tr>
 *   <tr><td>整扇门（MODEL_PSD）</td><td>-8..8</td><td>0..16</td><td>6..8</td></tr>
 *   <tr><td>端部门 外扇（END_*_1）</td><td>-8..0 / 0..8</td><td>0..16</td><td>6..8</td></tr>
 *   <tr><td>端部门 内扇（END_*_2）</td><td>0..8 / -8..0</td><td>0..16</td><td>4..6</td></tr>
 *   <tr><td>门灯（LIGHT_LEFT / RIGHT）</td><td>-1..0 / 7..8</td><td>16..17</td><td>2..3</td></tr>
 *   <tr><td>「暂停使用」牌</td><td>-3..3</td><td>4..10</td><td>7</td></tr>
 * </table>
 *
 * <p>变换：{@code translate(0.5+x, y, 0.5+z) + rotateY(-facing.asRotation())}。开了多少就整扇
 * 沿 ±x 平移多少（右扇往 -x、左扇往 +x）；端部门的内扇只平移一半（{@code open/2}）。
 */
public class RenderPsdDoor implements BlockEntityRenderer<PsdDoorBlock.TileEntityPsdDoor> {

    private static final Identifier TEX_DOOR_BOTTOM = id("xiamen_psd_door_bottom");
    private static final Identifier TEX_DOOR_BOTTOM_BACK = id("xiamen_psd_door_bottom_back");
    private static final Identifier TEX_DOOR_TOP = id("xiamen_psd_door_top");
    private static final Identifier TEX_DOOR_TOP_BACK = id("xiamen_psd_door_top_back");
    private static final Identifier TEX_LIGHT_ON = id("light_on");
    private static final Identifier TEX_LIGHT_OFF = id("light_off");
    private static final Identifier TEX_LOCKED = id("sign/door_not_in_use");

    public RenderPsdDoor(BlockEntityRendererFactory.Context context) {
    }

    private static Identifier id(String path) {
        return new Identifier("mtrlte", "textures/block/" + path + ".png");
    }

    @Override
    public void render(PsdDoorBlock.TileEntityPsdDoor entity, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light, int overlay) {
        World world = entity.getWorld();
        if (world == null) {
            return;
        }
        BlockPos pos = entity.getPos();
        BlockState state = world.getBlockState(pos);
        Direction facing = PsdProps.prop(state, Properties.HORIZONTAL_FACING);
        boolean side = PsdProps.prop(state, PsdProps.SIDE) == PsdProps.Side.RIGHT;
        boolean half = PsdProps.prop(state, PsdProps.HALF) == DoubleBlockHalf.UPPER;
        boolean end = PsdProps.prop(state, PsdProps.END);
        boolean unlocked = PsdProps.prop(state, PsdProps.UNLOCKED);
        float open = Math.min(entity.getOpenForRender(), 1.0F);
        float direction = side ? -1.0F : 1.0F;

        matrices.push();
        matrices.translate(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-facing.asRotation()));

        VertexConsumer front = vertexConsumers.getBuffer(
                RenderLayer.getEntityCutoutNoCull(half ? TEX_DOOR_TOP : TEX_DOOR_BOTTOM));
        VertexConsumer back = vertexConsumers.getBuffer(
                RenderLayer.getEntityCutoutNoCull(half ? TEX_DOOR_TOP_BACK : TEX_DOOR_BOTTOM_BACK));

        // 1) 门灯（上段才有）+ 端部门的内扇（不跟着整扇平移，只走一半）
        if (half) {
            boolean lightOn = isDoorLightOn(entity);
            VertexConsumer lightVc = vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(
                    lightOn ? TEX_LIGHT_ON : TEX_LIGHT_OFF));
            if (side) {
                PsdQuads.cube(matrices, lightVc, 7, 8, 16, 17, 2, 3, 0.0F, 1.0F,
                        net.minecraft.client.render.LightmapTextureManager.MAX_LIGHT_COORDINATE, overlay);
            } else {
                PsdQuads.cube(matrices, lightVc, -1, 0, 16, 17, 2, 3, 0.0F, 1.0F,
                        net.minecraft.client.render.LightmapTextureManager.MAX_LIGHT_COORDINATE, overlay);
            }
            if (end) {
                matrices.push();
                matrices.translate(open / 2.0F * direction, 0.0F, 0.0F);
                float x0 = side ? 0.0F : -8.0F;
                PsdQuads.plate(matrices, front, back, x0, x0 + 8.0F, 0.0F, 16.0F, 4.0F, 6.0F,
                        side ? 0.5F : 0.0F, side ? 1.0F : 0.5F, light, overlay);
                matrices.pop();
            }
        }

        // 2) 整扇门（端头只画半扇）
        matrices.push();
        matrices.translate(open * direction, 0.0F, 0.0F);
        if (end) {
            float x0 = side ? 0.0F : -8.0F;
            PsdQuads.plate(matrices, front, back, x0, x0 + 8.0F, 0.0F, 16.0F, 6.0F, 8.0F,
                    side ? 0.5F : 0.0F, side ? 1.0F : 0.5F, light, overlay);
        } else {
            PsdQuads.plate(matrices, front, back, -8.0F, 8.0F, 0.0F, 16.0F, 6.0F, 8.0F,
                    0.0F, 1.0F, light, overlay);
        }

        // 3) 「暂停使用」牌（锁着的门）
        if (half && !unlocked) {
            VertexConsumer lockedVc = vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(TEX_LOCKED));
            float xOffset = end ? (side ? 2.0F : -2.0F) : 0.0F;
            PsdQuads.quadZ(matrices, lockedVc, -3.0F + xOffset, 3.0F + xOffset, 4.0F, 10.0F, 7.0F,
                    0.0F, 1.0F, true, light, overlay);
        }
        matrices.pop();
        matrices.pop();
    }

    @Override
    public boolean rendersOutsideBoundingBox(PsdDoorBlock.TileEntityPsdDoor entity) {
        return true;
    }

    /** MTR 的 {@code isDoorLightOn}：关着 = 灭，全开 = 亮，中间闪（500ms 一个周期）。 */
    private static boolean isDoorLightOn(PsdDoorBlock.TileEntityPsdDoor entity) {
        int openValue = entity.getOpenValue();
        if (openValue <= 0) {
            return false;
        }
        if (openValue >= PsdDoorBlock.MAX_OPEN_VALUE) {
            return true;
        }
        return System.currentTimeMillis() / 500L % 2L == 0L;
    }
}
