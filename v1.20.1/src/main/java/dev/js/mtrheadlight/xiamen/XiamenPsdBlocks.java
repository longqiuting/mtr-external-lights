// Ported from Minecraft Transit Railway (MTR), MIT License.
package dev.js.mtrheadlight.xiamen;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;

/**
 * 三个具体方块（MTR 里是 {@code BlockPSDDoor} / {@code BlockPSDGlass} / {@code BlockPSDGlassEnd}）。
 *
 * <p>MTR 把这些做成具体类只为了给每个样式注册各自的方块实体类型；这里只有一种样式，
 * 所以 {@link Door} 只多一个 {@code createBlockEntity}，另外两个就是空的子类。
 */
public final class XiamenPsdBlocks {

    private XiamenPsdBlocks() {
    }

    /** 门扇（y+0 / y+1 两段，左右两扇对开）。 */
    public static final class Door extends PsdDoorBlock {

        @Override
        public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
            return new TileEntityPsdDoor(XiamenObjects.psdDoorEntityType(), pos, state);
        }
    }

    /** 玻璃板（y+0 / y+1 两段，一条链里按 side 自动切 left/middle/right/single）。 */
    public static final class Glass extends PsdGlassBlock {
    }

    /** 端部收口（带 touching_left / touching_right，最外侧补转角板）。 */
    public static final class GlassEnd extends PsdGlassEndBlock {
    }
}
