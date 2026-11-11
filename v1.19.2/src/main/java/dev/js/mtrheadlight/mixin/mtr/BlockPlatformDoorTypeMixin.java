// 站台方块的门型识别 —— 让 mtrlte 的全高屏蔽门也能把站台切成 platform_psd。
package dev.js.mtrheadlight.mixin.mtr;

import dev.js.mtrheadlight.xiamen.XiamenPlatformDoorType;
import mtr.block.BlockPlatform;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 补上 {@code BlockPlatform#getActualState} 的门型判定，让本模组的<b>全高屏蔽门</b>也能把站台
 * 切成 {@code platform_psd}。
 *
 * <h2>注入点</h2>
 * <pre>
 * 目标：mtr.block.BlockPlatform（remap = false —— MTR 自己编译，方法名没有混淆）
 *        private BlockState getActualState(BlockView world, BlockPos pos, BlockState state)
 *        // 生产 jar 里：private net.minecraft.class_2680 getActualState(class_1922, class_2338, class_2680)
 * 位置：@At("HEAD") + cancellable
 * </pre>
 *
 * <h2>为什么补在 HEAD</h2>
 * <p>MTR 的判定是<b>三个 instanceof 具体壳类</b>（字节码见 {@link XiamenPlatformDoorType} 的类注释）：
 * <pre>
 *   31: instanceof mtr/block/BlockPSDDoor      39: BlockPSDGlass      47: BlockPSDGlassEnd   → PSD
 *   76: instanceof mtr/block/BlockAPGDoor      84: BlockAPGGlass      92: BlockAPGGlassEnd   → APG
 *  121: 全不中 → BlockPlatform$EnumDoorType.NONE
 * </pre>
 * 本模组的 {@code Door/Glass/GlassEnd} 继承的是<b>上两层基类</b>
 * （{@code BlockPSDAPGDoorBase} / {@code BlockPSDAPGGlassBase} / {@code BlockPSDAPGGlassEndBase}），
 * 一个 instanceof 都中不了；而半高 APG 那三个类继承的是 MTR 自己的 {@code BlockAPGDoor} /
 * {@code BlockAPGGlass} / {@code BlockAPGGlassEnd}，三个全中 —— 这就是「APG 正常、PSD 不正常」的
 * 全部原因。
 *
 * <p>没在 RETURN 上改返回值，是因为 {@code facing} 也在同一条分支里被覆盖（MTR 只在 PSD/APG 两条
 * 分支里把 facing 换成门的朝向），而 {@code side}（站台面箭头）是用 facing 去探邻格算出来的 ——
 * 只改 {@code door_type} 会留下「门型对了、朝向和箭头还是站台自己的」的错状态。HEAD 接管可以一次
 * 算准三个属性。
 *
 * <p><b>只接管本模组的全高 PSD 一族</b>：其它方块（MTR 原版 PSD/APG、本模组 APG 一族、以及没有门的
 * 站台）直接 {@code return}，原方法照常执行，行为与不装本模组时逐字节一致。
 */
@Mixin(value = BlockPlatform.class, remap = false)
public abstract class BlockPlatformDoorTypeMixin {

    @Inject(method = "getActualState", at = @At("HEAD"), cancellable = true, remap = false)
    private void lte$resolveXiamenPsdDoorType(final BlockView world, final BlockPos pos, final BlockState state,
                                              final CallbackInfoReturnable<BlockState> cir) {
        // 原方法看的是 pos.up()（字节码 14: BlockPos.method_10084 = up()）—— 门的下段就贴在站台顶面。
        if (!XiamenPlatformDoorType.isFullHeightFamily(world.getBlockState(pos.up()).getBlock())) {
            return;
        }
        cir.setReturnValue(XiamenPlatformDoorType.actualState(world, pos, state));
    }
}
