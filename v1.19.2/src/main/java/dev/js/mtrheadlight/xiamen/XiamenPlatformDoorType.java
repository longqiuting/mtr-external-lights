// 站台方块「门型」识别的补齐件 —— 让本模组的全高屏蔽门也能把站台切成 platform_psd。
package dev.js.mtrheadlight.xiamen;

import mtr.block.BlockPlatform;
import mtr.block.BlockPSDAPGDoorBase;
import mtr.block.IBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;

/**
 * MTR {@code BlockPlatform#getActualState} 的等价物，只用于<b>本模组的全高屏蔽门</b>。
 *
 * <h2>MTR 原逻辑（javap -p -c，生产 jar 3.6.4.2 / 1.20.1）</h2>
 * <pre>
 * private net.minecraft.class_2680 getActualState(class_1922, class_2338, class_2680);
 *   // 0..10 : facing = IBlock.getStatePropertySafe(state, FACING)
 *   12: aload_1  13: aload_2
 *   14: invokevirtual class_2338.method_10084   // BlockPos.up()          ← 看的是**上方**一格
 *   17: invokeinterface class_1922.method_8320  // world.getBlockState(..)
 *   24: invokevirtual class_2680.method_26204  // state.getBlock()
 *   26: astore 6                              // Block block = 上方方块的 Block
 *
 *   // ── 门型判定：三个 instanceof，全是「MTR 自己的方块类」──
 *   31: aload 6  33: instanceof mtr/block/BlockPSDDoor      -> PSD 分支 (doorType=PSD)
 *   39: aload 6  41: instanceof mtr/block/BlockPSDGlass
 *   47: aload 6  49: instanceof mtr/block/BlockPSDGlassEnd
 *   76: aload 6  78: instanceof mtr/block/BlockAPGDoor      -> APG 分支 (doorType=APG)
 *   84: aload 6  86: instanceof mtr/block/BlockAPGGlass
 *   92: aload 6  94: instanceof mtr/block/BlockAPGGlassEnd
 *  121: getstatic mtr/block/BlockPlatform$EnumDoorType.NONE   -> 都不是 = NONE
 *
 *   // ── side（站台面上的箭头，0..4）：这一段的判据是基类，子类天然成立 ──
 *  126: aload 6 128: instanceof mtr/block/BlockPSDAPGDoorBase      -> isDoor
 *  133: pos.up().offset(facing.rotateYCounterclockwise()) → 邻居也是 BlockPSDAPGDoorBase ?
 *  163: pos.up().offset(facing.rotateYClockwise())        → 邻居也是 BlockPSDAPGDoorBase ?
 *  193: isDoor &amp;&amp; cw  -> side=2    209: isDoor &amp;&amp; ccw -> side=3
 *  225: cw            -> side=1 + facing 取 cw 的    249: ccw -> side=4 + facing 取 ccw 的
 *  273:                -> side=0
 *
 *  276: state.with(FACING, facing).with(DOOR_TYPE, doorType).with(SIDE, side)
 * </pre>
 *
 * <h2>为什么 APG 能用、PSD 不能</h2>
 * <p>判定是 {@code instanceof}，不是 {@code isOf(Blocks.PSD_DOOR)} —— 但三个 instanceof 的对象是
 * <b>MTR 的具体壳类</b>（{@code BlockPSDDoor} / {@code BlockPSDGlass} / {@code BlockPSDGlassEnd}），
 * 不是共同基类 {@code BlockPSDAPGBase}。本模组：
 * <ul>
 *   <li>半高 APG：{@code ApgDoor extends BlockAPGDoor}、{@code ApgGlass extends BlockAPGGlass}、
 *       {@code ApgEnd extends BlockAPGGlassEnd} —— 三个 instanceof <b>全中</b> → {@code doorType=APG}；</li>
 *   <li>全高 PSD：{@code Door extends BlockPSDAPGDoorBase}、{@code Glass extends BlockPSDAPGGlassBase}、
 *       {@code GlassEnd extends BlockPSDAPGGlassEndBase} —— 三个 instanceof <b>全不中</b>
 *       （{@code BlockPSDAPGDoorBase} 不是 {@code BlockPSDDoor} 的父类型反向）→ 落到
 *       {@code EnumDoorType.NONE} → 站台永远渲染 {@code platform_hk_1}。</li>
 * </ul>
 * <p>同一个原因也让 {@code facing} 没被换成门自己的朝向（MTR 只在 PSD / APG 两条分支里覆盖 facing），
 * 所以这里连 facing 一起重算。
 *
 * <h2>为什么用 mixin 而不是改父类</h2>
 * <p>把 {@code Door} 改成 {@code extends BlockPSDDoor} 理论上也能中，但那三个 MTR 壳类都带
 * {@code style} 构造参数与自己的方块实体（{@code BlockPSDDoor#createBlockEntity} 返回
 * {@code TileEntityPSDDoor}），本模组要额外注册方块实体类型，等于动渲染链路；而 {@code side} 那一
 * 段的判据本来就是基类 {@code BlockPSDAPGDoorBase}，本模组的门天然满足 —— 缺的<b>只有门型</b>一个
 * 属性。所以在判定点外面补一刀成本最低、影响面最小。
 *
 * <p>只接管「上方方块是本模组全高 PSD 一族」这一种情况；MTR 自己的方块和本模组的 APG 一族
 * 原样走 MTR 的逻辑。
 */
public final class XiamenPlatformDoorType {

    /** 属性表里的取值名（blockstate JSON 的 {@code "door_type"} 就是它）。 */
    private static final String PSD = "psd";

    private XiamenPlatformDoorType() {
    }

    /** 上方这一格是不是本模组的「全高屏蔽门」一族（门扇 / 玻璃 / 端部）。 */
    public static boolean isFullHeightFamily(Block above) {
        return above instanceof XiamenPsdBlocks.Door
                || above instanceof XiamenPsdBlocks.Glass
                || above instanceof XiamenPsdBlocks.GlassEnd;
    }

    /**
     * 站台方块的 actual state：门型固定为 {@code psd}，facing 取门自己的朝向，side 按 MTR 的
     * 邻接规则重算（否则会拿站台自己的朝向去探邻格，箭头方向会错）。
     */
    public static BlockState actualState(BlockView world, BlockPos pos, BlockState state) {
        final BlockPos abovePos = pos.up();
        final BlockState above = world.getBlockState(abovePos);

        Direction facing = IBlock.getStatePropertySafe(above, Properties.HORIZONTAL_FACING);

        final boolean isDoor = above.getBlock() instanceof BlockPSDAPGDoorBase;
        final BlockState counterClockwise = world.getBlockState(abovePos.offset(facing.rotateYCounterclockwise()));
        final BlockState clockwise = world.getBlockState(abovePos.offset(facing.rotateYClockwise()));
        final boolean isDoorCounterClockwise = counterClockwise.getBlock() instanceof BlockPSDAPGDoorBase;
        final boolean isDoorClockwise = clockwise.getBlock() instanceof BlockPSDAPGDoorBase;

        final int side;
        if (isDoor && isDoorClockwise) {
            side = 2;
        } else if (isDoor && isDoorCounterClockwise) {
            side = 3;
        } else if (isDoorClockwise) {
            side = 1;
            facing = IBlock.getStatePropertySafe(clockwise, Properties.HORIZONTAL_FACING);
        } else if (isDoorCounterClockwise) {
            side = 4;
            facing = IBlock.getStatePropertySafe(counterClockwise, Properties.HORIZONTAL_FACING);
        } else {
            side = 0;
        }

        return withDoorType(state, PSD)
                .with(Properties.HORIZONTAL_FACING, facing)
                .with(BlockPlatform.SIDE, side);
    }

    /**
     * 写 {@code door_type}。
     *
     * <p><b>为什么这么绕</b>：{@code BlockPlatform.DOOR_TYPE} 的类型参数
     * {@code BlockPlatform$EnumDoorType} 是 <b>包私有</b>（javap：{@code final class
     * mtr.block.BlockPlatform$EnumDoorType}），包外的源码既不能 {@code import} 它，也不能写
     * {@code state.with(BlockPlatform.DOOR_TYPE, BlockPlatform.EnumDoorType.PSD)}。
     * <p>所以：把属性取成 raw {@link Property}，再从属性自己的候选值里按枚举常量名
     * （{@code PSD}）挑一个 —— 拿到的是同一个对象实例，交给 {@code with} 时泛型被擦成
     * {@code (Property, Comparable)}，编译通过且语义完全等价。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState withDoorType(BlockState state, String name) {
        final Property property = BlockPlatform.DOOR_TYPE;
        for (final Object candidate : (Iterable<?>) property.getValues()) {
            if (candidate instanceof Enum && name.equalsIgnoreCase(((Enum<?>) candidate).name())) {
                return state.with(property, (Comparable) candidate);
            }
        }
        // 属性值被改名（MTR 换了枚举常量名）时宁可不动，也不要写进非法值。
        return state;
    }
}
