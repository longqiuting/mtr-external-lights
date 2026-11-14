// 贴墙标识的方块实体：继承 MTR 的 TileEntityStationNameBase，偏移量照抄 MTR 的出入口款。
package dev.js.mtrheadlight.xiamen.sign;

import mtr.block.BlockStationNameBase;
import mtr.block.IBlock;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

/**
 * 贴墙标识 {@code mtrlte:wall_sign_*} 的方块实体。
 *
 * <h2>为什么继承 MTR 的 {@code TileEntityStationNameBase}</h2>
 * <p>MTR 的站名渲染器 {@code mtr.render.RenderStationNameBase} 只认这个类型的方块实体，
 * 它从这个实体的两个公开字段里拿「牌子在哪」：
 * <pre>
 *   public final float yOffset;        // 竖直方向：名字相对方块中心的偏移（格）
 *   public final float zOffset;        // 牌面离墙多远（格）
 *   public final boolean isDoubleSided; // 双面牌子才翻第二遍
 * </pre>
 * 所以只要继承它并给出和 MTR {@code station_name_entrance} <b>一模一样的两个常量</b>，
 * MTR 的渲染器就会把我们的牌子当成它自己的牌子来画（javap 取证：
 * {@code TileEntityStationNameEntrance} 的构造器就是 {@code fconst_0, 0.00625f, false}）。
 *
 * <h2>我们自己的类型，不是 MTR 那个</h2>
 * <p>父类构造器要 {@code BlockEntityType}，这里传我们自己的
 * （{@link WallSignObjects#entityType()}）：存档里的 id 才是 {@code mtrlte:wall_sign_xiamen}，
 * 渲染器也才挂得住（vanilla 的 {@code BlockEntityRenderDispatcher} 按实体的
 * {@code getType()} 查渲染器，类型必须是我们注册的那一个）。
 *
 * <h2>它什么都不存</h2>
 * <p>站名、这条牌子横跨几格、字色 —— 全部每帧现算（MTR 的渲染器从
 * {@code RailwayData.getStation(ClientData.STATIONS, ClientData.DATA_CACHE, pos)} 按位置取站名）。
 * 没有 ticker，没有 NBT，服务端一次都不碰它。
 */
public class WallSignBlockEntity extends BlockStationNameBase.TileEntityStationNameBase {

    /**
     * 照 MTR {@code TileEntityStationNameEntrance} 的三个常量：
     * {@code yOffset = 0}、{@code zOffset = 0.00625}（板在 {@code z = 0.05/16}，名字再往外一点点）、
     * {@code isDoubleSided = false}。
     */
    public WallSignBlockEntity(final BlockPos pos, final BlockState state) {
        super(WallSignObjects.entityType(), pos, state, 0.0F, 0.00625F, false);
    }

    /**
     * 字色（MTR 的 {@code TileEntityStationNameEntrance#getColor} 逐条照抄）。
     *
     * <p>{@code COLOR} 属性是 {@code BlockStationNameBase} 给的，但 {@code BlockStationNameEntrance}
     * 的 {@code appendProperties} 只注册了 {@code FACING + STYLE}（javap 核对过），所以我们这些方块
     * 状态里<b>没有</b> {@code COLOR}。{@code IBlock.getStatePropertySafe} 在这种情况返回该属性的
     * 第一个取值（0 = 白），于是三个开关永远走 {@code default} 分支 —— 和 MTR 出入口款的实际行为一致。
     */
    @Override
    public int getColor(final BlockState state) {
        final int color = IBlock.getStatePropertySafe(state, BlockStationNameBase.COLOR);
        return switch (color) {
            case 1 -> 0xFFAAAAAA;  // -5592406，灰字
            case 2 -> 0xFF000000;  // -16777216，黑字
            default -> 0xFFFFFFFF; // -1，白字
        };
    }
}
