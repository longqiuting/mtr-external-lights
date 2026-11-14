// 被扔出去的充电宝：落地小范围「爆」一下（只伤生物、不点火、不炸方块）。
package dev.js.mtrheadlight.xiamen.charger;

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
 *   <li><b>不动世界</b>：不调 {@code createExplosion}、不 {@code setBlockState}，
 *       所以既不会点燃火焰方块，也不会破坏方块 —— 只借用一个爆炸的声光效果。</li>
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
        this.stack = stack.copyWithCount(1);
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
            living.damage(world.getDamageSources().thrown(this, owner), BLAST_DAMAGE);
        }

        this.discard();
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
