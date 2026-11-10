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
 * 厦门门扇的渲染器 —— MTR {@code mtr.render.RenderPSDAPGDoor} 的几何 + 本模组的贴图。
 *
 * <h2>为什么是「另一份渲染器」而不是直接继承</h2>
 * <p>{@code RenderPSDAPGDoor} 里贴图路径是写死的 {@code String.format("mtr:textures/block/psd_door_...")}，
 * 没有可覆盖的字段 / 方法；而模型（{@code ModelSingleCube}）又都是 private。所以要换厦门贴图只能
 * 照它的几何画一遍。真正<下决策>的东西都不在这里：
 * <ul>
 *   <li>方块状态（{@code SIDE} / {@code HALF} / {@code END} / {@code UNLOCKED}）→ MTR 的
 *       {@code BlockPSDAPGDoorBase}；</li>
 *   <li>开度 {@code open} / 插值 {@code openClient} → MTR 的
 *       {@code BlockPSDAPGDoorBase.TileEntityPSDAPGDoorBase#getOpen(float)}
 *       （列车调 {@code setOpen}，这里只跟着画）；</li>
 *   <li>门灯闪烁节奏 → 同一套 {@code getOpenValue()}。帧间隔用 MTR 的
 *       {@code MTRClient#getLastFrameDuration()}，和 MTR 自己的门完全同步。</li>
 * </ul>
 *
 * <h2>几何（数字全部照抄 MTR 的 ModelSingleCube）</h2>
 * <table border="1">
 *   <caption>单位是 1/16 格；+z 朝外（朝轨道），+x 沿门宽</caption>
 *   <tr><th>部件</th><th>x</th><th>y</th><th>z</th></tr>
 *   <tr><td>整扇门</td><td>-8..8</td><td>0..16</td><td>6..8</td></tr>
 *   <tr><td>端部门 外扇</td><td>-8..0 / 0..8</td><td>0..16</td><td>6..8</td></tr>
 *   <tr><td>端部门 内扇</td><td>0..8 / -8..0</td><td>0..16</td><td>4..6</td></tr>
 *   <tr><td>门灯</td><td>-1..0 / 7..8</td><td>16..17</td><td>2..3</td></tr>
 *   <tr><td>「暂停使用」牌</td><td>-3..3</td><td>4..10</td><td>7</td></tr>
 * </table>
 */
public class RenderPsdDoor implements BlockEntityRenderer<XiamenPsdBlocks.TileEntityXiamenPSDDoor> {

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
                        LightmapTextureManager.MAX_LIGHT_COORDINATE, overlay);
            } else {
                PsdQuads.cube(matrices, lightVc, -1, 0, 16, 17, 2, 3, 0.0F, 1.0F,
                        LightmapTextureManager.MAX_LIGHT_COORDINATE, overlay);
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
