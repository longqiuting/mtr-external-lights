// PSD-LCD 挂板的方块实体：整条链只有「最左一格（anchor）」存配置并负责绘制。
package dev.js.mtrheadlight.xiamen.pids;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/**
 * 厦门地铁 PSD-LCD 挂板（{@code mtrlte:psd_lcd_xiamen}）的方块实体。
 *
 * <h2>为什么继承 {@link PidsXiamenBlockEntity} 而不是再写一遍</h2>
 * <p>刷子界面（{@link PidsXiamenConfigScreen}）读的是「站台筛选 / 显示页码 / 广告分类 /
 * 轮播起点」这四样，MTR 的 PIDS 配置包（{@code sendPIDSConfigC2S}）写的也是前两样，
 * 广告那两样是本模组自己的 NBT 字段。这些在 {@link PidsXiamenBlockEntity} 里已经全齐了，
 * 所以这里<b>只多两件事</b>：
 * <ol>
 *   <li>换成本方块自己的方块实体类型（{@code mtrlte:psd_lcd_xiamen}，存档里的 id）；</li>
 *   <li>到站行数固定 <b>2</b> 行（版式就是「开往 / 下一趟开往」两行，不像地铁 PIDS 有三行）。</li>
 * </ol>
 *
 * <h2>只有 anchor 那一格有数据</h2>
 * <p>整条链（1~N 格）由 {@link PsdLcdBlock#anchorOf} 找出最左一格，配置只写在它上面；
 * 其它格的方块实体是空的，渲染器也只从 anchor 画（见 {@link PsdLcdRenderer}）。
 */
public class PsdLcdBlockEntity extends PidsXiamenBlockEntity {

    /** 固定两行到站（构造期就会被 {@code IPIDS.TileEntityPIDS} 虚调用，所以必须是常量）。 */
    private static final int ARRIVALS = 2;

    public PsdLcdBlockEntity(final BlockPos pos, final BlockState state) {
        super(PsdLcdObjects.entityType(), pos, state, PidsXiamenBlock.Kind.METRO);
    }

    @Override
    public int getMaxArrivals() {
        return ARRIVALS;
    }

    /**
     * 渲染包围盒：整条链可能横跨十几格，按「自己那一格」算会被视锥体整块剔掉。
     *
     * <p>这里直接放大 24 格（渲染器本身还有 {@code rendersOutsideBoundingBox} +
     * {@link dev.js.mtrheadlight.client.RenderDistance} 两道保险，见 {@link PsdLcdRenderer}）。
     */
    @Override
    public Box getRenderBoundingBox() {
        return new Box(this.pos).expand(24.0D, 4.0D, 24.0D);
    }
}
