package dev.js.mtrheadlight.collision;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/**
 * 「手持 MTR 驾驶钥匙 → 停用列车碰撞」的唯一判定点。
 *
 * <h2>为什么要这个开关</h2>
 * 车厢碰撞箱是<b>每 tick 把玩家推出去</b>的主动几何：玩家想走到车门前面按右键上车时，
 * 车体段先把他推开了，于是「拿驾驶钥匙也上不了车」。MTR 自己上车走的是
 * {@code Train#isPlayerRiding} 之外的「右键门 / 车头」交互，不要求玩家先挤进碰撞箱，
 * 所以只要把车体碰撞让开就行。
 *
 * <h2>判定规则</h2>
 * <ul>
 *   <li>只对 {@link PlayerEntity} 生效；生物（{@link MobCollision}）照常被撞。</li>
 *   <li>{@code mtr:driver_key} 在<b>主手或副手</b>都算 —— 主手要用来右键车门，玩家经常是
 *       副手拿着钥匙、主手空着。</li>
 *   <li>判定<b>每 tick 现问现答</b>，不缓存「这个玩家免碰撞」的状态：一松手，下一 tick
 *       碰撞、推挤、伤害就全部回来（不是永久免伤，也不是一次性开关）。</li>
 *   <li>物品 id 拿 {@link Registries#ITEM} 反查 id 比较，而不是缓存 {@code Item} 实例：
 *       {@code Registries.ITEM} 是 {@code DefaultedRegistry}（查不到会返回 {@code air} 而不是
 *       {@code null}），比 id 既躲开这个坑，也不怕 MTR 换注册时机。</li>
 * </ul>
 *
 * <h2>挂在哪（四个入口）</h2>
 * <ul>
 *   <li>{@link CollisionSystem#clampMovement} —— 车顶截断（不拿钥匙时免得被车顶「吸」住）；</li>
 *   <li>{@link CollisionSystem#afterMove} —— 车体推出 / 车顶站立 / 撞击伤害 / 流血窗口，
 *       <b>道床（trackbed）不受影响</b>；</li>
 *   <li>{@code dev.js.mtrheadlight.server.ServerCollision#collide} —— 服务端权威路径
 *       （专用服务器上玩家与生物的推开 / 扣血）；</li>
 *   <li>{@code CollisionBootstrap} 的撞飞冲量镜像 —— 服务端算出冲量时，本地玩家持有钥匙就不写速度。</li>
 * </ul>
 */
public final class DriverKeyBypass {

    /** MTR 3.6.4.2 的驾驶钥匙（{@code data/mtr/recipes/driver_key.json} 的产物 id）。 */
    public static final Identifier DRIVER_KEY_ID = new Identifier("mtr", "driver_key");

    private DriverKeyBypass() {
    }

    /** 这个实体是「手持驾驶钥匙的玩家」吗？每 tick 现问，松手立刻恢复。 */
    public static boolean active(final Entity entity) {
        if (!(entity instanceof PlayerEntity player)) {
            return false;
        }
        return holds(player);
    }

    /** 主手或副手任意一只手拿着驾驶钥匙。 */
    public static boolean holds(final PlayerEntity player) {
        if (player == null) {
            return false;
        }
        try {
            return isDriverKey(player.getMainHandStack()) || isDriverKey(player.getOffHandStack());
        } catch (Throwable ignored) {
            // 物品栏读失败不该让碰撞 tick 崩：当作没拿钥匙（碰撞照常）。
            return false;
        }
    }

    private static boolean isDriverKey(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            return DRIVER_KEY_ID.equals(Registries.ITEM.getId(stack.getItem()));
        } catch (Throwable ignored) {
            return false;
        }
    }
}
