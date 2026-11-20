package dev.js.mtrheadlight.collision;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/**
 * 「手持 YUM（Yomi 的 MTR 实用拓展）的『随时上车工具』→ 停用列车碰撞」的唯一判定点。
 *
 * <h2>为什么要这个开关</h2>
 * <p>{@code mtryum:onboard_tool}（lang key {@code item.mtryum.onboard_tool} = 随时上车工具）
 * 的用途就是「站在车旁边随手右键就能上车」。我们的车体几何是<b>每 tick 主动把玩家推出去</b>的，
 * 玩家还没点到车门就被推开了 —— 和 {@link DriverKeyBypass}（MTR 驾驶钥匙）是同一个问题，
 * 所以用同一个形状的开关：<b>手里拿着它，就不参与列车碰撞</b>。
 *
 * <h2>判定规则</h2>
 * <ul>
 *   <li>只对 {@link PlayerEntity} 生效；生物（{@code MobCollision}）与其它玩家照常被撞；</li>
 *   <li>主手<b>或</b>副手任意一只拿着就算 —— 玩家经常是主手空着 / 副手拿工具，
 *       而 YUM 自己认的是「主手或副手」（见 {@code PlayerEntity#getMainHandStack()} /
 *       {@code getOffHandStack()}）；</li>
 *   <li>每 tick 现问现答，不缓存「这个玩家免碰撞」：一松手，下一 tick 推挤 / 撞飞 / 伤害全部回来；</li>
 *   <li>物品 id 用 {@link Registries#ITEM} <b>反查</b> {@code getId(stack.getItem())} 再比 id，
 *       而不是 {@code Registries.ITEM.get(id)}：1.20.1 的 {@code ITEM} 是 {@code DefaultedRegistry}，
 *       查不到会返回 {@code air} 而不是 {@code null}；比 id 也没有这个问题，
 *       而且 <b>没有任何地方 import YUM 的类</b> —— 不装 YUM / 换了 YUM 版本都只是「查不到」，
 *       返回 {@code false}，碰撞照常。</li>
 * </ul>
 *
 * <h2>挂在哪</h2>
 * <ul>
 *   <li>{@link CollisionSystem#clampMovement} —— 车顶截断（别被车顶「吸」住）；</li>
 *   <li>{@link CollisionSystem#afterMove} —— 车体推出 / 车顶站立 / 撞击伤害 / 流血窗口，
 *       <b>道床（trackbed）不受影响</b>（那是轨面，不是列车）；</li>
 *   <li>{@code dev.js.mtrheadlight.server.ServerCollision#collide} —— 服务端权威路径
 *       （专用服务器上玩家被推开 / 扣血的那一边）；</li>
 *   <li>{@link CollisionBootstrap} 的撞飞冲量镜像 —— 服务端算出冲量时，本地玩家拿着它就不写速度。</li>
 * </ul>
 */
public final class OnboardToolBypass {

    /**
     * YUM 的「随时上车工具」（{@code mods/[Yomi的MTR实用拓展] MTR-YUM-fabric-1.20.1-1.4.0.jar}
     * 里的 {@code mtryum:onboard_tool}）。用 id 字符串而不是 {@code Item} 常量：
     * YUM 不装时这个 id 根本不在注册表里，{@link #holds} 自然返回 {@code false}。
     */
    public static final Identifier ONBOARD_TOOL_ID = new Identifier("mtryum", "onboard_tool");

    private OnboardToolBypass() {
    }

    /** 这个实体是「手持随时上车工具的玩家」吗？每 tick 现问，松手立刻恢复。 */
    public static boolean active(final Entity entity) {
        if (!(entity instanceof PlayerEntity player)) {
            return false;
        }
        return holds(player);
    }

    /** 主手或副手任意一只手拿着随时上车工具。 */
    public static boolean holds(final PlayerEntity player) {
        if (player == null) {
            return false;
        }
        try {
            return isOnboardTool(player.getMainHandStack()) || isOnboardTool(player.getOffHandStack());
        } catch (Throwable ignored) {
            // 物品栏读失败不该让碰撞 tick 崩：当作没拿（碰撞照常）。
            return false;
        }
    }

    private static boolean isOnboardTool(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            return ONBOARD_TOOL_ID.equals(Registries.ITEM.getId(stack.getItem()));
        } catch (Throwable ignored) {
            return false;
        }
    }
}
