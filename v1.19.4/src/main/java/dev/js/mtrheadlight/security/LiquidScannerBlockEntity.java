package dev.js.mtrheadlight.security;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionUtil;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 液体安检机的方块实体 —— 只存一件东西：<b>这台机器「直接没收」哪些液体</b>。
 *
 * <h2>为什么配置要落在方块实体上，而不是全局配置</h2>
 * 液体安检机是站内设施，每台机器守的可能是不同的口（机场口禁所有负面药水、药房口只禁剧毒），
 * 所以配置是 <b>每台机器一份</b>，跟着方块走：拆了机器配置一起没，换台机器互不影响。
 * 全局配置（{@code config/mtrlte.json}）做不到这一点。
 *
 * <h2>「没配过」≠「什么都不没收」</h2>
 * {@link #configured} 为 {@code false} 时（玩家从没用刷子开过界面保存），判定走
 * {@link LiquidScannerBlock#isHarmful(ItemStack)} 那套「有负面效果就没收」的启发式 ——
 * 这是模组原本的行为，老存档里的机器、以及从没打开过界面的机器都不会突然变成「什么都放行」。
 * <p>玩家一旦在界面里点过「保存」，{@link #confiscate} 就是<b>唯一</b>判据：
 * 名单里有的没收，没登记的一律放行（包括原版水瓶）。这样「哪些液体直接没收」才是玩家说了算，
 * 而不是被「有害 / 无害」这个默认规则覆盖。想回到默认，界面里有「恢复默认」按钮，
 * 它写的是「按有害效果自动判定」的那份名单（见 {@code LiquidScannerConfigScreen}）。
 *
 * <h2>名单里存的是药水 id，不是物品</h2>
 * 水瓶 / 喷溅 / 滞留药水共用药水类型（{@code minecraft:water}、{@code minecraft:swiftness} …），
 * 存 id 就同时覆盖三种容器，也不用跟着物品注册表变。自定义效果药水（{@code /give} 手写 NBT）
 * 没有自己的注册 id，只能按它的基础药水 id 判定 —— 但这时若机器<b>没</b>配过，
 * 启发式那条路读的是 {@link PotionUtil#getPotionEffects(ItemStack)}，自定义效果照样算进去。
 */
public class LiquidScannerBlockEntity extends BlockEntity {

    /** 「玩家配过没有」：{@code false} = 走有害效果启发式。 */
    private static final String TAG_CONFIGURED = "configured";
    /** 没收名单：字符串列表（药水 id）。 */
    private static final String TAG_CONFISCATE = "confiscate";

    private boolean configured;
    /** 有序，界面里的顺序稳定；{@link LinkedHashSet} 顺便去重。 */
    private final Set<Identifier> confiscate = new LinkedHashSet<>();

    public LiquidScannerBlockEntity(final BlockPos pos, final BlockState state) {
        super(SecurityObjects.liquidScannerEntityType(), pos, state);
    }

    // ------------------------------------------------------------------ 判定

    public boolean isConfigured() {
        return this.configured;
    }

    /** 只读视图（界面 / 网络包用）。 */
    public Set<Identifier> confiscate() {
        return java.util.Collections.unmodifiableSet(this.confiscate);
    }

    /**
     * 这瓶东西要不要没收。
     * <p>配过 → 只看名单；没配过 → 回落 {@link LiquidScannerBlock#isHarmful(ItemStack)}。
     */
    public boolean shouldConfiscate(final ItemStack stack) {
        if (!this.configured) {
            return LiquidScannerBlock.isHarmful(stack);
        }
        final Identifier id = potionId(stack);
        return id != null && this.confiscate.contains(id);
    }

    /** 物品栈里的药水类型 id（无法解析 → {@code null}）。 */
    public static Identifier potionId(final ItemStack stack) {
        final Potion potion = PotionUtil.getPotion(stack);
        if (potion == null) {
            return null;
        }
        return Registries.POTION.getId(potion);
    }

    /** 覆盖整份名单（服务端写、{@link LiquidScannerNet} 校验过的 id）。 */
    public void setConfiscate(final Collection<Identifier> ids) {
        this.confiscate.clear();
        if (ids != null) {
            this.confiscate.addAll(ids);
        }
        this.configured = true;
        this.markDirty();
    }

    // ------------------------------------------------------------------ NBT

    @Override
    public void writeNbt(final NbtCompound nbt) {
        super.writeNbt(nbt);
        nbt.putBoolean(TAG_CONFIGURED, this.configured);
        final NbtList list = new NbtList();
        for (final Identifier id : this.confiscate) {
            list.add(NbtString.of(id.toString()));
        }
        nbt.put(TAG_CONFISCATE, list);
    }

    @Override
    public void readNbt(final NbtCompound nbt) {
        super.readNbt(nbt);
        this.configured = nbt.getBoolean(TAG_CONFIGURED);
        this.confiscate.clear();
        final NbtList list = nbt.getList(TAG_CONFISCATE, NbtElement.STRING_TYPE);
        for (int i = 0; i < list.size(); i++) {
            final Identifier id = Identifier.tryParse(list.getString(i));
            if (id != null) {
                this.confiscate.add(id);
            }
        }
    }
}
