// 贴墙标识方块（厦门 / 角美 / LTE）：**直接继承 MTR 的 mtr:station_name_entrance**，
// 只多加一个「这张牌子用哪张品牌 logo」的字段。
package dev.js.mtrheadlight.xiamen.sign;

import mtr.block.BlockStationNameEntrance;
import mtr.mappings.BlockEntityMapper;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

/**
 * 贴墙标识方块 {@code mtrlte:wall_sign_xiamen} / {@code wall_sign_jiaomei} / {@code wall_sign_lte}。
 *
 * <h2>不自己写任何几何 / 连接 / 刷子逻辑：{@code extends BlockStationNameEntrance}</h2>
 * <p>父类就是 MTR 3.6.4.2 自己那个 {@code mtr:station_name_entrance} 方块（javap 取证）：
 * <pre>
 *   public class mtr.block.BlockStationNameEntrance extends mtr.block.BlockStationNameBase implements mtr.block.IBlock {
 *     public static final IntProperty STYLE;                      // IntProperty.of("propagate_property", 0, 5)
 *     public BlockStationNameEntrance(AbstractBlock.Settings);
 *     public ActionResult onUse(...);                             // 刷子：STYLE.cycle() + 左右各传 1 格
 *     public boolean canPlaceAt(...);                             // 背后那格 isSolid()
 *     public BlockState getPlacementState(ItemPlacementContext);  // 只认水平面，FACING = side.getOpposite()
 *     public BlockState getStateForNeighborUpdate(...);           // 支撑没了变空气
 *     public VoxelShape getOutlineShape(...);                     // 薄板 z 0..1，STYLE 偶 4..12 / 奇 0..16
 *     public VoxelShape getCollisionShape(...);                   // VoxelShapes.empty()
 *     public BlockEntityMapper createBlockEntity(...);            // 见下面的覆写
 *     protected void appendProperties(...);                       // FACING + STYLE（没有 COLOR）
 *   }
 * </pre>
 * 这些我们<b>一行都不重写</b>：
 * <ul>
 *   <li><b>贴哪面墙</b>：{@code FACING} = 被点那一面的 {@code opposite}，背后必须是实心方块；</li>
 *   <li><b>刷新形态</b>：{@code STYLE}（{@code propagate_property} 0..5）—— 偶数 = 矮（8 px 板），
 *       奇数 = 高（16 px 板）；{@code 0/1} 白字、{@code 2/3} 黑字无灯板、{@code 4/5} 白字无灯板，
 *       和 MTR 完全同一套语义（模型只在 0/1 有灯板，见 {@code blockstates/wall_sign_*.json}）；</li>
 *   <li><b>连接</b>：MTR 不存连接状态，渲染时沿 {@code facing.rotateYClockwise()} 数隔壁同款方块
 *       （{@code RenderStationNameTiled#getLength}），所以这里<b>没有任何 link 位掩码</b>；</li>
 *   <li><b>形状</b>：轮廓是那块薄板，碰撞箱是空的。</li>
 * </ul>
 *
 * <h2>唯一覆写：{@link #createBlockEntity}</h2>
 * <p>父类默认给的是 MTR 自己的 {@code TileEntityStationNameEntrance}，它把方块实体类型
 * <b>写死</b>成 {@code mtr:station_name_entrance}（javap：构造器里直接 {@code BlockEntityTypes
 * .STATION_NAME_ENTRANCE_TILE_ENTITY.get()}）。我们要的是自己的类型（存档里 id 是
 * {@code mtrlte:wall_sign_xiamen}，且渲染器挂在它上面），所以换成
 * {@link WallSignBlockEntity} —— 它继承 MTR 的 {@code TileEntityStationNameBase}，
 * 偏移量照抄 MTR 的出入口款（{@code yOffset = 0}、{@code zOffset = 0.00625}）。
 *
 * <h2>渲染：MTR 的渲染器，只有 logo 那张图是我们的</h2>
 * <p>站名不是我们画的：客户端注册的是 {@link WallSignRenderer}，它就是 MTR 的
 * {@code RenderStationNameTiled(dispatcher, true)}（{@code showLogo = true} 正是 MTR 给出入口款用的那个，
 * 它按 {@code STYLE} 选字号 / 字色、按 {@code getLength} 沿墙拼字），只多压了一个「这是哪张牌子」的标记。
 * MTR 把 logo <b>画进生成贴图里</b>（{@code RouteMapGenerator.drawResource} 里写死
 * {@code mtr:textures/block/sign/logo.png}，模型里没有 logo），那个标记让
 * {@link dev.js.mtrheadlight.mixin.mtr.StationNameEntranceLogoMixin} 把取图换成
 * {@code mtrlte:textures/block/<brand>_logo.png} —— 资源文件一个不改。
 */
public class WallSignBlock extends BlockStationNameEntrance {

    /**
     * 任意位置可放：父类 {@code BlockStationNameEntrance#canPlaceAt} 要求背后是实心方块，
     * 这里恒真。父类的 {@code getStateForNeighborUpdate} 问的也是这个方法，
     * 所以「支撑没了自己变空气」那条也一并失效 —— 牌子拆墙后仍然留着。
     */
    @Override
    public boolean canPlaceAt(final BlockState state, final net.minecraft.world.WorldView world,
                              final BlockPos pos) {
        return true;
    }

    /**
     * 品牌 logo 的贴图名（{@code block/xiamen_logo} 这种，不含命名空间、不含扩展名）。
     * 只在模型 / 日志里用到 —— 方块本身不认识它。
     */
    private final String logo;

    /**
     * @param settings 方块设置（照 MTR 的 {@code station_name_entrance}：{@code requiresTool()}
     *                 + {@code strength(2.0F)} + {@code nonOpaque()}，见 {@link WallSignObjects}）
     * @param logo     品牌 logo 贴图名（见 {@link #logo()}）
     */
    public WallSignBlock(final AbstractBlock.Settings settings, final String logo) {
        super(settings);
        this.logo = logo == null ? "xiamen_logo" : logo;
    }

    /** 这一张牌子的品牌 logo 贴图名（{@code xiamen_logo} / {@code jiaomei_logo} / {@code lte_logo}）。 */
    public String logo() {
        return this.logo;
    }

    /** 换成我们自己的方块实体（类型是 {@code mtrlte:wall_sign_xiamen}，不是 MTR 那个）。 */
    @Override
    public BlockEntityMapper createBlockEntity(final BlockPos pos, final BlockState state) {
        return new WallSignBlockEntity(pos, state);
    }
}
