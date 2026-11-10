package dev.js.mtrheadlight.bind;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * MTR 方块实体的<b>反射</b>门面：读 / 写投影机与 PIDS 里的 {@code platformIds}。
 *
 * <h2>为什么不能直接 instanceof / 调方法</h2>
 * <p>MTR 的 jar 没有 remap，{@code mtr.mappings.BlockEntityMapper} 在它字节码里的父类写的是
 * intermediary 名 {@code net/minecraft/class_2586}。本工程编译时 classpath 上同时有两个 Minecraft：
 * yarn 命名的那个（{@code net.minecraft.block.entity.BlockEntity}）和 intermediary 那个
 * （{@code net.minecraft.class_2586}）。于是 javac 眼里
 * {@code TileEntityPIDS} 的祖先链通向的是<b>另一个</b> {@code class_2586}，
 * 「{@code BlockEntity instanceof TileEntityPIDS}」直接被判成「不兼容的类型」
 * （{@code BindTargets} 第一版就是这么挂的）。
 *
 * <p>那就干脆不碰它们的静态类型：方块实体一律用 {@code Object} 拿、用 {@code Object} 传，
 * 类型判定看<b>运行时类名</b>（MTR 自己的类名不参与 remap，稳定），字段/方法按名字反射。
 * 反射只打 MTR 自己声明的东西 —— {@code setData} / {@code getPlatformIds} /
 * {@code getMaxArrivals} / {@code getHideArrival} / {@code getDisplayPage} / 字段 {@code messages}。
 * <b>不反射任何 Minecraft 方法</b>：那些在正式环境里是 intermediary 名（{@code getPos} 是
 * {@code method_11016}），按名字找必挂。
 *
 * <h2>写的是什么</h2>
 * <ul>
 *   <li><b>投影机</b> {@code mtr.block.BlockArrivalProjectorBase$TileEntityArrivalProjectorBase}：
 *       {@code setData(Set<Long> platformIds, int displayPage)}；</li>
 *   <li><b>PIDS</b> {@code mtr.data.IPIDS$TileEntityPIDS}：
 *       {@code setData(String[] messages, boolean[] hideArrival, Set<Long> platformIds, int displayPage)}。
 *       调用时把现有的 messages / hideArrival / displayPage 原样读出来再传回去，只换 platformIds ——
 *       玩家改过的自定义消息和翻页不会被绑定动作冲掉。</li>
 * </ul>
 * 两者都会在 {@code setData} 内部 {@code markDirty()} + {@code syncData()}，即写盘并推给附近客户端。
 */
public final class MtrTiles {

    private static final String PROJECTOR = "mtr.block.BlockArrivalProjectorBase$TileEntityArrivalProjectorBase";
    private static final String PIDS = "mtr.data.IPIDS$TileEntityPIDS";

    /** 绑定后显式推 NBT 的半径（格）。MTR 自己的 PIDS 渲染距离上限是 16 格，64 已经很宽。 */
    private static final int PUSH_RADIUS = 64;
    private static final double PUSH_RADIUS_SQ = (double) PUSH_RADIUS * PUSH_RADIUS;

    /** 一块 PIDS 方块实体 + 它在世界里的位置（位置是我们自己记的，不从反射读）。 */
    public record PidsTile(Object tile, BlockPos pos) {
    }

    private MtrTiles() {
    }

    // ---------------------------------------------------------------- 判定

    public static boolean isProjector(final Object tile) {
        return tile != null && hasSupertype(tile.getClass(), PROJECTOR);
    }

    public static boolean isPids(final Object tile) {
        return tile != null && hasSupertype(tile.getClass(), PIDS);
    }

    /** 运行时类或其任一祖先的二进制名等于 {@code name}。 */
    private static boolean hasSupertype(final Class<?> type, final String name) {
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            if (cursor.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** 这一格上的方块实体（没有就 {@code null}）。 */
    public static Object tileAt(final World world, final BlockPos pos) {
        try {
            return world.getBlockEntity(pos);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 投影机

    @SuppressWarnings("unchecked")
    public static Set<Long> projectorPlatformIds(final Object tile) {
        try {
            final Object value = method(tile, "getPlatformIds").invoke(tile);
            return value instanceof Set ? new LinkedHashSet<>((Set<Long>) value) : Collections.emptySet();
        } catch (Throwable t) {
            return Collections.emptySet();
        }
    }

    /** 把投影机的 platformIds 换成 {@code ids}（displayPage 保留）。 */
    public static boolean projectorSetPlatforms(final Object tile, final Collection<Long> ids) {
        if (!isProjector(tile)) {
            return false;
        }
        try {
            final int page = ((Number) method(tile, "getDisplayPage").invoke(tile)).intValue();
            method(tile, "setData", Set.class, int.class)
                    .invoke(tile, new HashSet<>(ids), Integer.valueOf(page));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- PIDS

    @SuppressWarnings("unchecked")
    public static Set<Long> pidsPlatformIds(final Object tile) {
        try {
            final Object value = method(tile, "getPlatformIds").invoke(tile);
            return value instanceof Set ? new LinkedHashSet<>((Set<Long>) value) : Collections.emptySet();
        } catch (Throwable t) {
            return Collections.emptySet();
        }
    }

    /** PIDS 的 messages（父类 {@code public final String[] messages}，读一份拷贝）。 */
    public static String[] pidsMessages(final Object tile) {
        try {
            final Field field = tile.getClass().getField("messages");
            final Object value = field.get(tile);
            return value instanceof String[] ? ((String[]) value).clone() : new String[0];
        } catch (Throwable t) {
            return new String[0];
        }
    }

    /** 把 PIDS 的 platformIds 换成 {@code ids}，消息 / 隐藏位 / 翻页原样保留。 */
    public static boolean pidsSetPlatforms(final Object tile, final Collection<Long> ids) {
        if (!isPids(tile)) {
            return false;
        }
        try {
            final String[] messages = pidsMessages(tile);
            final int max = ((Number) method(tile, "getMaxArrivals").invoke(tile)).intValue();
            final boolean[] hideArrival = new boolean[max];
            final Method hide = method(tile, "getHideArrival", int.class);
            for (int i = 0; i < max; i++) {
                hideArrival[i] = Boolean.TRUE.equals(hide.invoke(tile, Integer.valueOf(i)));
            }
            final int page = ((Number) method(tile, "getDisplayPage").invoke(tile)).intValue();
            method(tile, "setData", String[].class, boolean[].class, Set.class, int.class)
                    .invoke(tile, messages, hideArrival, new HashSet<>(ids), Integer.valueOf(page));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 写完之后：同步 + 落盘

    /**
     * 把方块实体<b>整份</b> NBT 直接推给附近玩家（不是等 MTR 那条「下个 tick 广播」）。
     *
     * <h2>为什么需要这一步</h2>
     * <p>{@code setData(...)} 内部只做两件事：{@code markDirty()}（标区块待存）和
     * {@code syncData()}，而 {@code syncData()} 的实现是
     * {@code ((ServerWorld) world).getChunkManager().markForUpdate(pos)} —— javap 核对：
     * {@code ServerChunkManager#markForUpdate} 只调用 {@code ChunkHolder#markForBlockUpdate(pos)}，
     * 也就是把这一格排进「下个 tick 广播给正在跟踪这个 chunk 的玩家」的队列。它<b>不</b>保证
     * 绑定的这个玩家就在那份跟踪集合里（区块刚好在边界、ChunkHolder 还没到 block-entity 级别、
     * 玩家正跨维度/刚传送都会漏）。
     *
     * <p>漏掉的后果正是「绑上了，过一会儿又像没绑」：MTR 的
     * {@code RenderPIDS#getSchedules} 读的是<b>客户端自己的方块实体</b>，而且对空集合走的是
     * 「自动模式」分支（{@code platformIds.isEmpty() → getPlatformId(PLATFORMS, DATA_CACHE)}，
     * 即就近站台），屏幕上就是一块「没绑过」的 PIDS —— 等客户端数据缓存刷新后，全部到站刷出来，
     * 看起来就像绑定被撤销了。
     *
     * <p>这里用 {@code BlockEntityUpdateS2CPacket.create(be)} 显式发一次：它走
     * {@code BlockEntity#toInitialChunkDataNbt()}，而 MTR 的
     * {@code BlockEntityClientSerializableMapper#toInitialChunkDataNbt()} 会把
     * {@code writeCompoundTag()} 的内容（含 {@code platform_ids}）拼进去 ——
     * 所以客户端拿到的就是刚写进去的那份。
     *
     * <p><b>关于 {@code instanceof BlockEntity}</b>：{@link MtrTiles} 类注释里说「不碰静态类型」，
     * 指的是 {@code BlockEntity instanceof TileEntityPIDS}（MTR 的祖先链指向另一个 {@code class_2586}，
     * javac 判不兼容）。这里的方向相反：静态类型是 {@code Object}，向下转型永远合法；而本模组
     * 构建时会被 remap 成 intermediary，运行期 {@code net.minecraft.block.entity.BlockEntity}
     * 与 MTR 引用的 {@code net.minecraft.class_2586} <b>是同一个类</b>，所以运行期也成立。
     *
     * @return 是否至少推给了一个玩家
     */
    public static boolean pushToClients(final ServerWorld world, final Object tile, final BlockPos pos) {
        if (world == null || pos == null || !(tile instanceof BlockEntity entity)) {
            return false;
        }
        try {
            final BlockEntityUpdateS2CPacket packet = BlockEntityUpdateS2CPacket.create(entity);
            final double x = pos.getX() + 0.5D;
            final double y = pos.getY() + 0.5D;
            final double z = pos.getZ() + 0.5D;
            boolean sent = false;
            for (final ServerPlayerEntity player : world.getPlayers()) {
                if (player.squaredDistanceTo(x, y, z) <= PUSH_RADIUS_SQ) {
                    player.networkHandler.sendPacket(packet);
                    sent = true;
                }
            }
            return sent;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 显式把这一格所在区块标成「需要保存」。
     *
     * <p>{@code setData} 里的 {@code markDirty()} 已经会走到
     * {@code World#markDirty(pos) → WorldChunk#setNeedsSaving(true)}（javap 核对过），
     * 但它依赖 {@code BlockEntity.world/cachedState} 非空。绑定完再补一刀几乎零成本，
     * 换来「关服/换维度/区块卸载后数据还在」这件事不取决于反编译出来的实现细节。
     */
    public static void markChunkSaved(final World world, final BlockPos pos) {
        if (world == null || pos == null) {
            return;
        }
        try {
            world.getWorldChunk(pos).setNeedsSaving(true);
        } catch (Throwable ignored) {
            // 区块没加载（理论上不会，我们刚写完方块实体）—— 让 MTR 自己的 markDirty 兜着。
        }
    }

    // ---------------------------------------------------------------- 反射小工具

    private static Method method(final Object target, final String name, final Class<?>... parameters)
            throws NoSuchMethodException {
        return target.getClass().getMethod(name, parameters);
    }

    /** 只用于日志 / 调试：类名链，方便判断第三方 PIDS 有没有继承 MTR 的基类。 */
    public static String describe(final Object tile) {
        if (tile == null) {
            return "null";
        }
        final StringBuilder out = new StringBuilder();
        for (Class<?> cursor = tile.getClass(); cursor != null && cursor != Object.class;
             cursor = cursor.getSuperclass()) {
            if (out.length() > 0) {
                out.append(" <- ");
            }
            out.append(cursor.getName());
        }
        return out.toString();
    }

    /** 便利方法：把一组 id 拼成 {@code "1, 2"}，查表命令与提示共用。 */
    public static String join(final Collection<Long> ids) {
        final StringBuilder out = new StringBuilder();
        for (final Long id : ids) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(id);
        }
        return out.toString();
    }

    /** 只在这块 PIDS 上写一个站台。 */
    public static boolean pidsBindSingle(final Object tile, final long platformId) {
        return pidsSetPlatforms(tile, Collections.singletonList(platformId));
    }
}
