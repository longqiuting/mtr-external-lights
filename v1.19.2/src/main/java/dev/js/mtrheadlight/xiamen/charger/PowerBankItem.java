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

/**
 * 充电宝 {@code mtrlte:power_bank}。
 *
 * <h2>它同时是「租借凭证」</h2>
 * <p>租借开始时间存在物品的 NBT 里（键 {@value #NBT_RENTED_AT}，值是<b>服务端</b>
 * {@code System.currentTimeMillis()}），所以物品<b>自己</b>带着租期走：扔掉、放进箱子、
 * 换手都不会丢时间，归还时机器只读这一个数。写这个键的只有租借机的服务端逻辑，
 * 客户端读到的只是同步下来的 NBT —— 客户端改不了自己的计费。
 *
 * <h2 id="fee">计费（新规则）</h2>
 * <ol>
 *   <li><b>前 {@value #GRACE_MINUTES} 分钟免费</b>：{@code held ≤ 5min} → 0 元；</li>
 *   <li>超过之后<b>每 {@value #PERIOD_MINUTES} 分钟 2 元</b>，<b>向上取整</b>
 *       （不足一档也算一档）：{@code periods = ceil((held − 5min) / 30min)}；</li>
 *   <li><b>24 小时封顶</b>：档数不超过 {@value #MAX_PERIODS} 档 = {@value #MAX_FEE} 元；</li>
 *   <li><b>超过 {@value #OVERDUE_HOURS} 小时不可归还</b>：机器拒收，扣
 *       {@value #OVERDUE_FEE} 元「买断」罚款 —— <b>只扣一次</b>，扣完在物品 NBT 上写
 *       {@value #NBT_OVERDUE_SETTLED} 标记，<b>充电宝留在玩家手里</b>（见
 *       {@code ChargerBlock#returnPowerBank}）。</li>
 * </ol>
 * <p>计费只读物品 NBT 里的 {@code rentedAt}，所以与客户端时钟无关；
 * 扣费允许把余额扣成负数（欠费是「余额为负不能租借」的前提，见 {@code MtrBalance}）。
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

    /**
     * NBT 键：超时「买断」罚款已结算（布尔）。写一次就不再扣钱 —— 玩家相当于花
     * {@value #OVERDUE_FEE} 元把这块充电宝买下来了，物品<b>不会</b>被机器收走。
     */
    public static final String NBT_OVERDUE_SETTLED = "expiredFine";

    /** 免费时长：前 5 分钟一分不扣。 */
    public static final int GRACE_MINUTES = 5;

    /** 免费时长（毫秒）。 */
    public static final long GRACE_MILLIS = GRACE_MINUTES * 60_000L;

    /** 计费周期：免费时长之后每满 30 分钟一档（不足一档向上取整）。 */
    public static final int PERIOD_MINUTES = 30;

    /** 计费周期（毫秒）。 */
    public static final long PERIOD_MILLIS = PERIOD_MINUTES * 60_000L;

    /** 每档租金（MTR 余额单位）。 */
    public static final int FEE_PER_PERIOD = 2;

    /** 封顶档数：(24h − 5min) / 30min 向上取整 = 48 档。 */
    public static final int MAX_PERIODS = 48;

    /** 封顶金额：48 档 × 2 = 96 元。 */
    public static final int MAX_FEE = MAX_PERIODS * FEE_PER_PERIOD;

    /** 超时门槛：超过 24 小时不能再归还。 */
    public static final int OVERDUE_HOURS = 24;

    /** 超时门槛（毫秒）。 */
    public static final long OVERDUE_MILLIS = OVERDUE_HOURS * 60L * 60_000L;

    /** 超时惩罚：拒收并没收时扣的金额（允许扣成负数）。 */
    public static final int OVERDUE_FEE = 100;

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

    /** 已持有多少整小时（超时提示用）。 */
    public static long heldHours(final ItemStack stack, final long nowMillis) {
        return heldMillis(stack, nowMillis) / 3_600_000L;
    }

    /**
     * 已计费多少档：免费时长之内的部分不计，之后每 {@link #PERIOD_MILLIS} 一档、
     * <b>向上取整</b>，且不超过 {@link #MAX_PERIODS}。
     *
     * <p>这就是「不足 30 分钟按 1 个计费档」和「24 小时封顶」两句话的合并实现。
     */
    public static long billedPeriods(final ItemStack stack, final long nowMillis) {
        final long billable = heldMillis(stack, nowMillis) - GRACE_MILLIS;
        if (billable <= 0L) {
            return 0L;
        }
        final long periods = (billable + PERIOD_MILLIS - 1L) / PERIOD_MILLIS;
        return Math.min(periods, MAX_PERIODS);
    }

    /** 该扣多少：档数 × {@link #FEE_PER_PERIOD}（已含封顶）。 */
    public static int fee(final ItemStack stack, final long nowMillis) {
        return (int) Math.min(Integer.MAX_VALUE, billedPeriods(stack, nowMillis) * FEE_PER_PERIOD);
    }

    /** 是否已经超时到「不可归还」（严格大于 24 小时；正好 24:00 仍可归还，收费已封顶）。 */
    public static boolean isOverdue(final ItemStack stack, final long nowMillis) {
        return isRented(stack) && heldMillis(stack, nowMillis) > OVERDUE_MILLIS;
    }

    /** 超时罚款是否已经收过（{@value #NBT_OVERDUE_SETTLED}）。收过就只提示、不再扣钱。 */
    public static boolean isOverdueSettled(final ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.hasNbt()
                && stack.getNbt().getBoolean(NBT_OVERDUE_SETTLED);
    }

    /** 记下「超时罚款已收」：之后这块充电宝永远不再被收费，也不会被机器收走。 */
    public static void markOverdueSettled(final ItemStack stack) {
        stack.getOrCreateNbt().putBoolean(NBT_OVERDUE_SETTLED, true);
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
        tooltip.add(Text.translatable("tooltip.mtrlte.power_bank.rent").formatted(Formatting.GRAY));
        tooltip.add(Text.translatable("tooltip.mtrlte.power_bank.throw").formatted(Formatting.GRAY));
        if (isRented(stack)) {
            // 客户端时钟足够：这里只显示「已持有多久」的近似值，计费永远在服务端。
            tooltip.add(Text.translatable("tooltip.mtrlte.power_bank.held",
                    heldMinutes(stack, System.currentTimeMillis())).formatted(Formatting.AQUA));
        }
        // 最下面那行红色小字（用户要求：无 3C 认证提醒）
        tooltip.add(Text.translatable("tooltip.mtrlte.power_bank.warning").formatted(Formatting.RED));
    }
}
