// 被扔出去的充电宝：落地小范围「爆」一下（只伤生物、不炸方块），并在落点点燃一格火焰。
package dev.js.mtrheadlight.xiamen.charger;

import net.minecraft.entity.damage.DamageSource;
import dev.js.mtrheadlight.compat.Pos;
import dev.js.mtrheadlight.compat.Stacks;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.FlyingItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.thrown.ThrownEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * 投掷出去的充电宝 {@code mtrlte:power_bank_projectile}。
 *
 * <h2>速度与重力照雪球</h2>
 * <p>原版雪球的发射参数不在实体里，而在 {@code Items.SNOWBALL} 的 {@code use}：
 * {@code setVelocity(user, pitch, yaw, 0.0F, 1.5F, 1.0F)}（速度 1.5、散布 1.0），重力来自
 * {@code ThrownEntity#getGravity()} = {@code 0.03}。本类<b>直接继承 {@link ThrownEntity}</b>
 * 就是雪球那条继承链（雪球 = {@code SnowballEntity → ThrownItemEntity → ThrownEntity →
 * ProjectileEntity}）—— 它<b>不</b>在 {@code PersistentProjectileEntity} 之下
 * （那一支是箭矢：有入地判定、可拾取、有穿透 / 暴击，投掷物用不上）。所以这里同样从
 * {@code ThrownEntity} 起，重力、水中阻力、寿命全部和雪球一致；
 * 发射速度由 {@link PowerBankItem} 在 {@code use} 里给同样的 1.5 / 1.0。
 *
 * <h2>落地之后</h2>
 * <ul>
 *   <li><b>粒子</b>：一颗 {@code EXPLOSION}（小烟团）+ 20 颗 {@code FLAME}（落点火焰）
 *       + 少量 {@code SMOKE}，全部服务端 {@code spawnParticles} 广播给附近客户端；</li>
 *   <li><b>伤害</b>：半径 {@value #BLAST_RADIUS} 格内的 {@link LivingEntity}（生物与玩家）
 *       各吃 {@value #BLAST_DAMAGE} 点，来源是 {@code DamageSources.thrown(this, owner)}；
 *       投掷者本人（{@code getOwner()}）<b>永远</b>跳过；</li>
 *   <li><b>点火</b>：落点<b>最多一格</b>真正的 {@link Blocks#FIRE} 方块
 *       （{@link #igniteLandingTile}）—— 有支撑才点，没支撑就一格都不点；</li>
 *   <li><b>不炸方块</b>：不调 {@code createExplosion}，除了上面那一格火之外不碰世界 ——
 *       机器、箱子、地形都完好。</li>
 * </ul>
 *
 * <h2>为什么不做数据同步</h2>
 * <p>投放的永远只有一种物品（充电宝），客户端渲染用 {@link #getStack()} 的默认值就够了，
 * 不值得为一个常量再挂一层 {@code DataTracker}。手上那份 {@link ItemStack} 只在服务端保留，
 * 并写进 NBT（读档后依然知道自己是谁扔的 / 是哪一件）。
 */
public class PowerBankProjectileEntity extends ThrownEntity implements FlyingItemEntity {

    /** 爆炸波及半径（格）。 */
    public static final double BLAST_RADIUS = 2.0D;

    /** 半径内每个生物吃到的伤害（2 点 = 1 颗心）。 */
    public static final float BLAST_DAMAGE = 2.0F;

    /** 手上那一份（只为落点 / NBT 保留；渲染用默认值）。 */
    private ItemStack stack = ItemStack.EMPTY;

    /** 只炸一次：同一 tick 里方块命中与实体命中理论上互斥，标记一下更省心。 */
    private boolean detonated;

    public PowerBankProjectileEntity(final EntityType<? extends PowerBankProjectileEntity> type,
                                     final World world) {
        super(type, world);
    }

    public PowerBankProjectileEntity(final World world, final LivingEntity owner) {
        super(ChargerObjects.projectileType(), owner, world);
    }

    /** 记下扔出去的是哪一件（带不带 {@code rentedAt} 都不影响飞行）。 */
    public void setItem(final ItemStack stack) {
        this.stack = dev.js.mtrheadlight.compat.Stacks.copyWithCount(stack, 1);
    }

    /**
     * 没有要同步的数据：{@code Entity} 把这个方法声明成抽象的，{@code ThrownItemEntity}
     * 是给它自己那份 {@code ITEM} 用的；这里空实现即可。
     */
    @Override
    protected void initDataTracker() {
    }

    /** 渲染用（{@link net.minecraft.client.render.entity.FlyingItemEntityRenderer} 读它）。 */
    @Override
    public ItemStack getStack() {
        return this.stack.isEmpty() ? new ItemStack(ChargerObjects.powerBankItem()) : this.stack;
    }

    // ------------------------------------------------------------------ 命中

    @Override
    protected void onEntityHit(final EntityHitResult result) {
        super.onEntityHit(result);
        if (!this.getWorld().isClient) {
            this.detonate();
        }
    }

    @Override
    protected void onCollision(final HitResult hit) {
        super.onCollision(hit);
        if (!this.getWorld().isClient) {
            this.detonate();
        }
    }

    /** 落到哪儿就在哪儿「爆」：粒子 + 音效 + 半径内伤害，然后自己消失。 */
    private void detonate() {
        if (this.detonated) {
            return;
        }
        this.detonated = true;

        final World world = this.getWorld();
        final Vec3d at = this.getPos();

        if (world instanceof ServerWorld server) {
            // 小范围爆炸的观感：一团 EXPLOSION 烟 + 落点火焰 + 一点余烟。
            server.spawnParticles(ParticleTypes.EXPLOSION, at.x, at.y, at.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            server.spawnParticles(ParticleTypes.FLAME, at.x, at.y + 0.05D, at.z, 20, 0.4D, 0.15D, 0.4D, 0.02D);
            server.spawnParticles(ParticleTypes.SMOKE, at.x, at.y + 0.1D, at.z, 8, 0.35D, 0.1D, 0.35D, 0.01D);
            world.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_GENERIC_EXPLODE,
                    SoundCategory.PLAYERS, 0.7F, 1.5F);
            // 「爆炸之后」点火：只在这一刻、只放一格（见 igniteLandingTile）。
            igniteLandingTile(server, dev.js.mtrheadlight.compat.Pos.ofFloored(at.x, at.y, at.z));
        }

        // 半径 2 格内的活物（生物 + 玩家），投掷者本人除外。
        final Box box = this.getBoundingBox().expand(BLAST_RADIUS);
        final Entity owner = this.getOwner();
        final double radiusSquared = BLAST_RADIUS * BLAST_RADIUS;
        for (final Entity entity : world.getOtherEntities(this, box)) {
            if (entity == owner || entity == this) {
                continue;
            }
            if (!(entity instanceof LivingEntity living)) {
                continue;
            }
            if (entity.squaredDistanceTo(at) > radiusSquared) {
                continue;
            }
            living.damage(DamageSource.thrownProjectile(this, owner), BLAST_DAMAGE);
        }

        this.discard();
    }

    // ------------------------------------------------------------------ 点火

    /**
     * 在落点点燃<b>一格</b>火。
     *
     * <h2>为什么要挑位置</h2>
     * <p>投掷物「撞到方块」那一刻的位置正好压在方块表面上，向下一取整可能落在
     * 地面方块<b>里面</b>，也可能落在地面上那一格空气里 —— 取决于它从哪个角度撞的。
     * 所以按三个候选顺序试，<b>第一个成功的立刻 return</b>，这就是「最多一格」的保证：
     * <ol>
     *   <li>落点本身（撞在地面上一格空气的常见情况）；</li>
     *   <li>落点下方一格；</li>
     *   <li>落点上方一格（落点算到地面方块内部时，火该点在它顶上）。</li>
     * </ol>
     *
     * <p>三个都放不下（例如空中撞到生物、脚下没有实心面）就<b>一格都不放</b> ——
     * 宁可没火，也不要在空气里塞一个下一 tick 就自己灭掉的火方块。
     */
    private static void igniteLandingTile(final ServerWorld world, final BlockPos landing) {
        for (final BlockPos candidate : new BlockPos[] {landing, landing.down(), landing.up()}) {
            if (ignite(world, candidate)) {
                return;
            }
        }
    }

    /**
     * 在 {@code pos} 点一格火，放不下返回 {@code false}。
     *
     * <p>两道判定缺一不可：
     * <ul>
     *   <li>位置必须是空气或可替换方块（草 / 花 / 雪片…），实心方块里不能塞火；</li>
     *   <li>{@code FireBlock#canPlaceAt}（1.20.1：下方是完整实心面
     *       <b>或</b>四周有可燃物）—— 这条和火方块自己的 {@code scheduledTick} 用的
     *       是同一个判定，所以通过它放下的火<b>不会</b>下一 tick 就因为「没支撑」消失。</li>
     * </ul>
     *
     * <p>状态用 {@link AbstractFireBlock#getState} 现算（原版打火石同款）：它会把
     * {@code up} 与四邻的火焰连接属性按现场邻居写好，比 {@code getDefaultState()} 更像
     * 「真的被点着了」。
     */
    private static boolean ignite(final ServerWorld world, final BlockPos pos) {
        final BlockState current = world.getBlockState(pos);
        if (!current.isAir() && !current.getMaterial().isReplaceable()) {
            return false;
        }
        if (!Blocks.FIRE.getDefaultState().canPlaceAt(world, pos)) {
            return false;
        }
        return world.setBlockState(pos, AbstractFireBlock.getState(world, pos), Block.NOTIFY_ALL);
    }

    // ------------------------------------------------------------------ NBT

    @Override
    public void writeCustomDataToNbt(final net.minecraft.nbt.NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        if (!this.stack.isEmpty()) {
            nbt.put("Item", this.stack.writeNbt(new net.minecraft.nbt.NbtCompound()));
        }
    }

    @Override
    public void readCustomDataFromNbt(final net.minecraft.nbt.NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        if (nbt.contains("Item")) {
            this.stack = ItemStack.fromNbt(nbt.getCompound("Item"));
        }
    }
}
