// 贴墙标识的站名渲染器：自己画，不再借 MTR 的 RenderStationNameTiled（那个会把 MTR 的 logo 烤进贴图）。
package dev.js.mtrheadlight.xiamen.sign;

import java.util.concurrent.atomic.AtomicBoolean;

import dev.js.mtrheadlight.LteLog;
import mtr.block.BlockStationNameBase;
import mtr.block.BlockStationNameEntrance;
import mtr.block.IBlock;
import mtr.client.ClientData;
import mtr.client.IDrawing;
import mtr.render.RenderStationNameBase;
import mtr.render.RenderTrains;
import mtr.render.StoredMatrixTransformations;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;

/**
 * 贴墙标识（{@code mtrlte:wall_sign_*}）的方块实体渲染器：<b>只画站名</b>，牌面和品牌 logo 都在模型里。
 *
 * <h2>为什么不用 MTR 的 {@code RenderStationNameTiled}</h2>
 * <p>MTR 给出入口款挂的是 {@code new RenderStationNameTiled(dispatcher, true)}，那个 {@code showLogo}
 * 走的是 {@code ClientData.DATA_CACHE} 的 {@code getStationNameEntrance} 这条路（见
 * {@code RouteMapGenerator.generateStationNameEntrance}），它会往生成的贴图左边<b>画死 MTR 自己的 logo</b>
 * （{@code "textures/block/sign/logo.png"}，红底白 M）。这就是「logo 读到 MTR 原版的去了」的全部原因：
 * 那张贴图是运行时拼的，资源包里换不掉。换成 {@code showLogo = false} 又会把站名铺满整条板、
 * 压到我们的品牌 logo 上。所以这里继承 {@code RenderStationNameBase}，只覆写
 * {@link #drawStationName}，用 {@code getStationName} 那张干净的白字贴图
 * （透明底、<b>不带 logo</b>）画在牌面左边那条里，右边那条留给模型里的品牌 logo。
 *
 * <h2>版面（方块局部坐标，1.0 = 16 px，x 从 -0.5 到 0.5 = 模型的 x 0..16）</h2>
 * <pre>
 *   站名带 x -0.46875..0.125（模型的 0.5..10.0 px）
 *   logo   x  0.15625..0.46875（模型的 10.5..15.5 px，见 station_name_entrance_{tall,short}.json）
 *   站名带高：高板 0.75（12 px）/ 矮板 0.375（6 px），都竖直居中
 * </pre>
 * 局部 +x 与模型 +x 同位、局部 +y 与模型 +y 反号（父类把矩阵 {@code rotateZ(180)} 了），所以两块区域
 * 竖直方向都是「以牌面中线为中心」的对称带 —— 和模型里的 logo 对得上，四种 {@code facing} 都成立。
 *
 * <h2>STYLE 语义（照 MTR）</h2>
 * <p>偶数 = 矮板、奇数 = 高板；{@code 2..5} 是「无灯板」形态 —— blockstate 里压根没挂模型，
 * 所以这里也不画字（画了就是一行浮在空气里的白字）。
 *
 * <h2>画法</h2>
 * <p>{@code RenderTrains.scheduleRender(..., QueuedRenderLayer.INTERIOR, ...)}：和 MTR 自己的站名完全同一条
 * 提交路径（{@code INTERIOR} = {@code getEntityCutout}，透明底 + 背面剔除），矩阵由队列喂进来 ——
 * 父类的 {@link StoredMatrixTransformations} 是<b>绝对世界坐标</b>的，必须叠在 MTR 的世界矩阵上，
 * 自己 {@code new MatrixStack()} 会丢掉相机相对偏移。
 */
public class WallSignRenderer extends RenderStationNameBase<WallSignBlockEntity> {

    /** 站名带左边界：0.5 px（模型的 x=0.5）。 */
    private static final float TEXT_LEFT = -0.46875F;

    /** 站名带右边界：10.0 px —— 右边 10.5 px 起是 logo。 */
    private static final float TEXT_RIGHT = 0.125F;

    /** 站名带高度（高板）：12 px。 */
    private static final float TEXT_HEIGHT_TALL = 0.75F;

    /** 站名带高度（矮板）：6 px。 */
    private static final float TEXT_HEIGHT_SHORT = 0.375F;

    private static final AtomicBoolean REPORTED = new AtomicBoolean();

    public WallSignRenderer(final BlockEntityRenderDispatcher dispatcher) {
        super(dispatcher);
    }

    @Override
    protected void drawStationName(final BlockView world, final BlockPos pos, final BlockState state,
                                   final Direction facing, final StoredMatrixTransformations stored,
                                   final VertexConsumerProvider vertexConsumers, final String stationName,
                                   final int stationColor, final int color, final int light) {
        final int style = IBlock.getStatePropertySafe(state, BlockStationNameEntrance.STYLE);
        if (style >= 2) {
            // MTR 的「无灯板」形态：没有模型，也就没有牌面可以写字。
            return;
        }
        try {
            final boolean tall = (style & 1) == 1;
            final float textHeight = tall ? TEXT_HEIGHT_TALL : TEXT_HEIGHT_SHORT;
            final float textWidth = TEXT_RIGHT - TEXT_LEFT;

            final int lengthLeft = length(world, pos, false);
            final int lengthRight = length(world, pos, true);
            final int totalLength = lengthLeft + lengthRight - 1;

            // 一张贴图铺满整条牌子：宽度按「本格的字带宽度 × 格数」取，所以每格切 1/totalLength。
            final float aspectRatio = totalLength * textWidth / textHeight;
            final Identifier texture = ClientData.DATA_CACHE.getStationName(stationName, aspectRatio).resourceLocation;

            final float u1 = (lengthLeft - 1) / (float) totalLength;
            final float u2 = lengthLeft / (float) totalLength;

            RenderTrains.scheduleRender(texture, false, RenderTrains.QueuedRenderLayer.INTERIOR,
                    (matrices, vertexConsumer) -> {
                        stored.transform(matrices);
                        IDrawing.drawTexture(matrices, vertexConsumer, TEXT_LEFT, -textHeight / 2.0F,
                                textWidth, textHeight, u1, 0.0F, u2, 1.0F, facing, color, light);
                        matrices.pop();
                    });
        } catch (Throwable t) {
            if (REPORTED.compareAndSet(false, true)) {
                LteLog.LOGGER.warn("[lte] wall sign: could not draw the station name for " + pos, t);
            }
        }
    }

    /**
     * 这条牌子沿墙拼了几格（照抄 MTR {@code RenderStationNameTiled#getLength}）。
     *
     * <p>MTR 不存连接状态：从本格出发，沿 {@code facing.rotateYClockwise()} 一直数「同一个方块」，
     * 数到第一格不是为止。{@code thisBlock == checkBlock} 这个判断很关键 —— 相邻的另一块品牌牌子
     * 也继承 {@code BlockStationNameBase}，但不是同一个方块实例，所以不会串到别的品牌上去。
     */
    private static int length(final BlockView world, final BlockPos pos, final boolean lookRight) {
        if (world == null) {
            return 1;
        }
        final Direction facing = IBlock.getStatePropertySafe(world, pos, BlockStationNameBase.FACING);
        final Block thisBlock = world.getBlockState(pos).getBlock();
        int length = 1;
        Block checkBlock;
        while ((checkBlock = world.getBlockState(pos.offset(
                lookRight ? facing.rotateYClockwise() : facing.rotateYCounterclockwise(), length)).getBlock())
                instanceof BlockStationNameBase && checkBlock == thisBlock) {
            length++;
        }
        return length;
    }
}
