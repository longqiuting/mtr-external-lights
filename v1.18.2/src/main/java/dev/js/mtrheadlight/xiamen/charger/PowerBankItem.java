// 充电宝物品：租借凭证（NBT rentedAt）+ 右键空气投掷。
package dev.js.mtrheadlight.xiamen.charger;

import java.util.List;

import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import net.minecraft.text.TranslatableText;

/**
 * 充电宝 {@code mtrlte:power_bank}。
 *
 * <h2>它同时是「租借凭证」</h2>
 * <p>租借开始时间存在物品的 NBT 里（键 {@value #NBT_RENTED_AT}，值是<b>服务端</b>
 * {@code System.currentTimeMillis()}），所以物品<b>自己</b>带着租期走：扔掉、放进箱子、
 * 换手都不会丢时间，归还时机器只读这一个数。写这个键的只有租借机的服务端逻辑，
 * 客户端读到的只是同步下来的 NBT —— 客户端改不了自己的计费。
 *
 * <h2>右键空气 = 投掷</h2>
 * <p>照原版雪球：服务端生成 {@link PowerBankProjectileEntity}，发射速度 1.5、散布 1.0
 * （与 {@code Items.SNOWBALL} 逐字一致），重力由实体自己取 {@code ThrownEntity#getGravity()} = 0.03。
 * 创造模式不扣物品，其余情况扣 1 个。
 *
 * <p>右键机器上半部分是「归还」：那条路径由 {@link ChargerBlock#onUse} 拦下，
 * 方块的 {@code onUse} 先于物品的 {@code use} 执行，所以站在机器前右键不会误投。
 */
public class PowerBankItem extends Item {

    /** NBT 键：租借开始时间（epoch 毫秒，服务端写的）。 */
    public static final String NBT_RENTED_AT = "rentedAt";

    /** 计费周期：每满 10 分钟。 */
    public static final long PERIOD_MILLIS = 10L * 60L * 1000L;

    /** 每个周期的租金（MTR 余额单位）。 */
    public static final int FEE_PER_PERIOD = 2;

    public PowerBankItem(final Settings settings) {
        super(settings);
    }

    // ------------------------------------------------------------------ 租期

    /** 一件「刚刚租出」的充电宝：写入 {@code rentedAt}。 */
    public static ItemStack rented(final Item item, final long nowMillis) {
        final ItemStack stack = new ItemStack(item);
        stack.getOrCreateNbt().putLong(NBT_RENTED_AT, nowMillis);
        return stack;
    }

    /** 这件物品上有没有租借记录。 */
    public static boolean isRented(final ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.hasNbt()
                && stack.getNbt().contains(NBT_RENTED_AT);
    }

    /** 租借开始时间；没有记录时返回 {@code 0}。 */
    public static long rentedAt(final ItemStack stack) {
        if (!isRented(stack)) {
            return 0L;
        }
        return stack.getNbt().getLong(NBT_RENTED_AT);
    }

    /** 已持有多少毫秒（负数按 0 处理：服务器改过时间 / 存档回滚都当「刚租」）。 */
    public static long heldMillis(final ItemStack stack, final long nowMillis) {
        if (!isRented(stack)) {
            return 0L;
        }
        return Math.max(0L, nowMillis - rentedAt(stack));
    }

    /** 已持有多少整分钟。 */
    public static long heldMinutes(final ItemStack stack, final long nowMillis) {
        return heldMillis(stack, nowMillis) / 60000L;
    }

    /** 该扣多少：每满一个 {@link #PERIOD_MILLIS} 收 {@link #FEE_PER_PERIOD}，不满一期不扣。 */
    public static int fee(final ItemStack stack, final long nowMillis) {
        final long periods = heldMillis(stack, nowMillis) / PERIOD_MILLIS;
        return (int) Math.min(Integer.MAX_VALUE, periods * FEE_PER_PERIOD);
    }

    // ------------------------------------------------------------------ 投掷

    @Override
    public TypedActionResult<ItemStack> use(final World world, final PlayerEntity user, final Hand hand) {
        final ItemStack stack = user.getStackInHand(hand);
        world.playSound(null, user.getX(), user.getY(), user.getZ(), SoundEvents.ENTITY_SNOWBALL_THROW,
                SoundCategory.NEUTRAL, 0.5F, 0.4F / (world.getRandom().nextFloat() * 0.4F + 0.8F));
        if (!world.isClient) {
            final PowerBankProjectileEntity projectile = new PowerBankProjectileEntity(world, user);
            projectile.setItem(stack);
            // 与 Items.SNOWBALL#use 完全一致：速度 1.5、散布 1.0。
            projectile.setVelocity(user, user.getPitch(), user.getYaw(), 0.0F, 1.5F, 1.0F);
            world.spawnEntity(projectile);
        }
        if (!user.getAbilities().creativeMode) {
            stack.decrement(1);
        }
        return TypedActionResult.success(stack, world.isClient());
    }

    // ------------------------------------------------------------------ 提示

    @Override
    public void appendTooltip(final ItemStack stack, @Nullable final World world,
                              final List<Text> tooltip, final TooltipContext context) {
        tooltip.add(new TranslatableText("tooltip.mtrlte.power_bank.rent").formatted(Formatting.GRAY));
        tooltip.add(new TranslatableText("tooltip.mtrlte.power_bank.throw").formatted(Formatting.GRAY));
        if (isRented(stack)) {
            // 客户端时钟足够：这里只显示「已持有多久」的近似值，计费永远在服务端。
            tooltip.add(new TranslatableText("tooltip.mtrlte.power_bank.held",
                    heldMinutes(stack, System.currentTimeMillis())).formatted(Formatting.AQUA));
        }
    }
}
