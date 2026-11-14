// 饮料售卖机的方块实体：几何由 BER 画（纯锚点）+ 一份「当前卖哪一组药水」的预设。
package dev.js.mtrheadlight.xiamen.drinks;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;

/**
 * 饮料售卖机的方块实体。上下两块各一个实例（同一个方块实体类型）。
 *
 * <h2>它到底存了什么</h2>
 * <p>只有一项：{@code PotionPreset} —— 刷子右键循环出来的那一组药水（{@link DrinksMachinePotions.Preset#id()}）。
 * <ul>
 *   <li><b>朝向</b>在 {@code BlockState} 上（{@code facing}），不重复存；</li>
 *   <li><b>开没开门</b>也在 {@code BlockState} 上（下段的 {@code open}）——
 *       客户端要靠它选模型，方块状态本来就双向同步，放方块实体里还得额外发包；</li>
 *   <li><b>预设</b>只有服务端卖饮料时用得到，客户端一个字节都不需要，所以放心放 NBT
 *       （顺带老存档里没有这个键也能读：缺省 = 默认池）。</li>
 * </ul>
 * <p>{@link #getPreset()} / {@link #setPreset} 只有服务端会调；两个方法都容忍
 * 「方块实体还没初始化好」以外的一切情况（类型不认识的 id 退回默认池，见
 * {@link DrinksMachinePotions.Preset#byId}）。
 *
 * <p>构造器容忍 {@code null} BlockState（{@code BlockItem} 在物品栏里也会问一次）。
 */
public class DrinksMachineBlockEntity extends BlockEntity {

    /** NBT 键：预设 id（字符串）。 */
    private static final String NBT_PRESET = "PotionPreset";

    /** 这台机器当前卖哪一组；默认 = 需求点名的那 7 种。 */
    private DrinksMachinePotions.Preset preset = DrinksMachinePotions.Preset.MIXED;

    public DrinksMachineBlockEntity(final BlockPos pos, final BlockState state) {
        super(DrinksMachineObjects.blockEntityType(), pos, state == null ? Blocks.AIR.getDefaultState() : state);
    }

    /** 当前预设。 */
    public DrinksMachinePotions.Preset getPreset() {
        return this.preset;
    }

    /** 换一组（调用方负责给玩家回话；这里只管存盘 + 重发方块实体包）。 */
    public void setPreset(final DrinksMachinePotions.Preset preset) {
        this.preset = preset;
        this.markDirty();
        if (this.world != null && !this.world.isClient) {
            // 只有服务端会改，客户端不需要知道；保留 sync 是为了将来若有界面能立刻看到。
            this.world.updateListeners(this.pos, this.getCachedState(), this.getCachedState(), 3);
        }
    }

    @Override
    public void readNbt(final NbtCompound nbt) {
        super.readNbt(nbt);
        if (nbt.contains(NBT_PRESET)) {
            this.preset = DrinksMachinePotions.Preset.byId(nbt.getString(NBT_PRESET));
        }
    }

    @Override
    protected void writeNbt(final NbtCompound nbt) {
        super.writeNbt(nbt);
        nbt.putString(NBT_PRESET, this.preset.id());
    }
}
