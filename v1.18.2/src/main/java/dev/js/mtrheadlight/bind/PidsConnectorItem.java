package dev.js.mtrheadlight.bind;

import net.minecraft.text.LiteralText;
import net.minecraft.text.TranslatableText;

import dev.js.mtrheadlight.LteLog;
import mtr.data.Station;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * PIDS 连接器（{@code mtrlte:pids_connector}）：把一块 PIDS 绑到「某个轨道节点所属的站台」。
 *
 * <h2>两次右键，两种顺序</h2>
 * <table border="1">
 *   <caption>两次右键的语义</caption>
 *   <tr><th>第一次</th><th>第二次</th><th>结果</th></tr>
 *   <tr><td>PIDS（任意一半）</td><td>站台方块，或同一条轨道上的节点</td><td>PIDS 只显示该站台的到站</td></tr>
 *   <tr><td>站台方块 / 轨道节点</td><td>PIDS（任意一半）</td><td>同上（只是顺序反过来）</td></tr>
 * </table>
 * <p>第二次点哪里都行，<b>不要求在车站范围内</b>：站台附近的节点（MTR 的 ±5 格）直接命中，
 * 离得远的节点则顺着 {@code getRailsMap()} 上的同一条轨道走到站台为止。
 *
 * <h2>PIDS 认哪些方块</h2>
 * 不按类名认，按<b>方块实体类型</b>认：{@code mtr.data.IPIDS$TileEntityPIDS}。MTR 的
 * {@code BlockPIDSBaseHorizontal} / {@code BlockPIDSBaseVertical}、天津地铁
 * {@code ziyue.tjmetro.block.BlockPIDSTianjin}、JCM {@code com.jsblock.block.JobanPIDSBase}
 * 的方块实体全部继承它（javap 核对：{@code TileEntityBlockPIDSTianjin extends
 * mtr.data.IPIDS$TileEntityPIDS}），所以一条 {@code instanceof} 覆盖三家。
 *
 * <h2>一次只绑「这一台屏」</h2>
 * <p>{@code BindTargets.pidsAt} 只收「数据格 + 同一台屏的另一半（同方块且 HALF 相反）」，
 * <b>不再扫同方块类的 6 邻居</b>。两块独立 PIDS 挨着摆 / 上下叠时方块类完全相同，旧写法会把
 * 一整片屏一起写掉 —— 玩家在已绑定的屏旁边新放一块，下一次绑定时新屏也变成同一个站台，
 * 看起来就是「绑定一次之后，新放的屏自己就被绑到那个站台了」。绑定只写这一台屏，
 * 新放的屏永远是「没绑过」（{@code platformIds} 空 = MTR 的自动模式）。
 *
 * <h2>写进去的是什么</h2>
 * <p>PIDS 里没有车站名字段，只有 {@code Set<Long> platformIds}（NBT {@code platform_ids}）——
 * 显示哪几路车完全由它决定。{@code setData(String[] messages, boolean[] hideArrival,
 * Set<Long> platformIds, int displayPage)} 是 public 的（MTR 自己的 PIDS 配置界面服务端就调它），
 * 所以我们把「消息 / 隐藏位 / 翻页」原样读出来、只把 platformIds 换成该站台，再写回去 ——
 * 玩家已经调好的自定义消息和翻页不会被这次绑定冲掉。
 *
 * <p>站台是这么算出来的：节点 → {@code RailwayData.getClosePlatformId(...)}（MTR 给 PIDS 找站台用的
 * 就是这一条，半径 5）；半径内没有站台时（节点在站区外、离站台很远），再顺着 {@code getRailsMap()}
 * 上的同一条轨道往外走，碰到第一个站台就用它（{@link LteMtrLookup#platformIdOnRail}）。
 * 也就是说<b>绑定不要求点在车站范围内</b>：站外的轨道节点、站台方块都能绑。
 *
 * <p>绑定记录另存 {@link PidsLinkStore}（{@code world/data/mtrlte_pids_links.dat}），与信号表、
 * 投影机表互不相干。
 *
 * <h2>“绑上了，过一会儿又像没绑”是怎么修的</h2>
 * <p>写入本身走的是 MTR 自己的 {@code setData(...)}，里面已经 {@code markDirty()} +
 * {@code syncData()}（javap 核对：{@code markDirty} = {@code method_5431}，{@code syncData} =
 * {@code ServerWorld#getChunkManager().markForUpdate(pos)}）。问题出在 {@code syncData} 的语义：
 * 它只是把这一格排进「下个 tick 广播给<b>正在跟踪该 chunk 的玩家</b>」的队列，并不保证刚绑定的
 * 这个玩家在这份集合里。而 MTR 的 PIDS 显示读的是<b>客户端自己的方块实体</b>，并且对空集合走
 * 「自动模式」（{@code RenderPIDS#getSchedules} 里 {@code platformIds.isEmpty() →
 * getPlatformId(PLATFORMS, DATA_CACHE)}，即就近站台）—— 客户端那份没收到，屏幕就是一块
 * 「没绑过」的 PIDS，等客户端数据缓存刷新、全部到站刷出来，看起来就像绑定被撤销了。
 *
 * <p>所以每次绑定都做四件事，缺一不可：
 * <ol>
 *   <li><b>服务端写</b>：{@code setData(...)}（MTR 的唯一合法入口，<b>不存在「只写车站」的写法</b>）；</li>
 *   <li><b>客户端写</b>：{@link MtrTiles#pushToClients} 把整份 NBT 直接
 *       {@code BlockEntityUpdateS2CPacket} 推给附近每个玩家，不再依赖那条 tick 队列；</li>
 *   <li><b>落盘</b>：{@link MtrTiles#markChunkSaved} 显式把区块标脏（不依赖 {@code markDirty} 的
 *       实现细节），重进世界数据还在；</li>
 *   <li><b>回读校验</b>：写完再 {@code getPlatformIds()} 读一遍，日志打
 *       {@code [lte] bind: pids verify platforms=[...]}；读不回来就红字告诉玩家，不假装成功。</li>
 * </ol>
 */
public final class PidsConnectorItem extends Item {

    private static final String TAG_PIDS = "lte_pids";
    private static final String TAG_NODE = "lte_node";
    private static final String TAG_DIM = "lte_dim";

    public PidsConnectorItem(final Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult useOnBlock(final ItemUsageContext context) {
        final World world = context.getWorld();
        if (world.isClient) {
            return ActionResult.SUCCESS;
        }
        final ServerPlayerEntity player = context.getPlayer() instanceof ServerPlayerEntity p ? p : null;
        if (player == null) {
            return ActionResult.PASS;
        }

        final BlockPos clicked = context.getBlockPos();

        // 1) 右键 PIDS（任意一半都算）—— 一律归一到「数据那一格」，
        //    这样绑定表、/ltebind 查询、补推 ticker 用的是同一个位置。
        final List<MtrTiles.PidsTile> pids = BindTargets.pidsAt(world, clicked);
        if (!pids.isEmpty()) {
            return onPidsClicked(world, player, context.getStack(),
                    BindTargets.dataPos(world, clicked), pids);
        }

        // 2) 右键轨道节点（或节点附近）
        final BlockPos node = BindTargets.findNode(world, clicked, context.getHitPos());
        if (node != null) {
            return onNodeClicked(world, player, context.getStack(), node);
        }

        // 3) 右键站台方块（站台轨道上的那一串坐标）：这一格本身就是「要绑的站台」。
        //    允许在站台区外点 —— 只要这一格真是站台轨道，站台 id 就有了。
        if (LteMtrLookup.platformIdAtExact(world, clicked) != 0L) {
            return onNodeClicked(world, player, context.getStack(), clicked);
        }

        // 4) 什么都没点到：说清楚该点什么，别静默失败。
        say(player, "message.mtrlte.pids.not_linkable", Formatting.RED);
        return ActionResult.PASS;
    }

    // ------------------------------------------------------------------ PIDS 这一侧

    private ActionResult onPidsClicked(final World world, final ServerPlayerEntity player,
                                       final ItemStack stack, final BlockPos pidsPos,
                                       final List<MtrTiles.PidsTile> pids) {
        final NbtCompound nbt = stack.getOrCreateNbt();
        final String dim = world.getRegistryKey().getValue().toString();

        if (player.isSneaking()) {
            final boolean removed = PidsLinkStore.get((ServerWorld) world).remove(pidsPos);
            final boolean cleared = clearPids(pids, world);
            if (removed || cleared) {
                say(player, "message.mtrlte.pids.unlinked", Formatting.YELLOW);
            } else {
                say(player, "message.mtrlte.pids.not_linked", Formatting.GRAY);
            }
            clearPending(nbt);
            return ActionResult.SUCCESS;
        }

        // 反向顺序：上一步点的是节点 → 这一次点 PIDS，完成绑定。
        if (nbt.contains(TAG_NODE)) {
            if (!dim.equals(nbt.getString(TAG_DIM))) {
                say(player, "message.mtrlte.pids.wrong_dim", Formatting.RED);
                return ActionResult.SUCCESS;
            }
            final BlockPos node = BlockPos.fromLong(nbt.getLong(TAG_NODE));
            clearPending(nbt);
            return bind(world, player, pidsPos, pids, node);
        }

        nbt.putLong(TAG_PIDS, pidsPos.asLong());
        nbt.putString(TAG_DIM, dim);
        say(player, "message.mtrlte.pids.pids_set", Formatting.AQUA, posText(pidsPos));
        return ActionResult.SUCCESS;
    }

    // ------------------------------------------------------------------ 节点这一侧

    private ActionResult onNodeClicked(final World world, final ServerPlayerEntity player,
                                       final ItemStack stack, final BlockPos node) {
        final NbtCompound nbt = stack.getOrCreateNbt();
        final String dim = world.getRegistryKey().getValue().toString();

        // 正向顺序：上一步点的是 PIDS → 这一次点节点，完成绑定。
        if (nbt.contains(TAG_PIDS)) {
            if (!dim.equals(nbt.getString(TAG_DIM))) {
                say(player, "message.mtrlte.pids.wrong_dim", Formatting.RED);
                return ActionResult.SUCCESS;
            }
            final BlockPos pidsPos = BlockPos.fromLong(nbt.getLong(TAG_PIDS));
            clearPending(nbt);
            final List<MtrTiles.PidsTile> pids = BindTargets.pidsAt(world, pidsPos);
            if (pids.isEmpty()) {
                say(player, "message.mtrlte.pids.pids_gone", Formatting.RED);
                return ActionResult.SUCCESS;
            }
            return bind(world, player, pidsPos, pids, node);
        }

        nbt.putLong(TAG_NODE, node.asLong());
        nbt.putString(TAG_DIM, dim);
        say(player, "message.mtrlte.pids.node_set", Formatting.AQUA, posText(node));
        return ActionResult.SUCCESS;
    }

    // ------------------------------------------------------------------ 真正的写入

    private ActionResult bind(final World world, final ServerPlayerEntity player,
                              final BlockPos pidsPos, final List<MtrTiles.PidsTile> pids,
                              final BlockPos node) {
        // 站台 id 允许从「站区外」推：先看节点旁边（MTR 的 ±5），推不出就顺着同一条轨道走。
        final long platformId = LteMtrLookup.platformIdOnRail(world, node, "pids");
        if (platformId == 0L) {
            LteLog.LOGGER.info("[lte] bind: pids pos={} node={} -> platforms=[]", pidsPos, node);
            say(player, "message.mtrlte.pids.no_platform", Formatting.RED);
            return ActionResult.SUCCESS;
        }
        LteLog.LOGGER.info("[lte] bind: pids pos={} -> platforms={}", pidsPos,
                Collections.singletonList(Long.valueOf(platformId)));
        final String platformName = LteMtrLookup.platformName(world, platformId);
        final Station station = LteMtrLookup.stationOfPlatform(world, platformId);
        final ServerWorld serverWorld = world instanceof ServerWorld sw ? sw : null;

        int written = 0;
        final StringBuilder writtenAt = new StringBuilder();
        for (final MtrTiles.PidsTile entry : pids) {
            if (!MtrTiles.pidsBindSingle(entry.tile(), platformId)) {
                continue;
            }
            written++;
            if (writtenAt.length() > 0) {
                writtenAt.append(", ");
            }
            writtenAt.append(entry.pos());
            // 双端都写：
            //   服务端 —— pidsBindSingle 走的是 MTR 自己的 setData(...)，内部 markDirty + syncData；
            //   客户端 —— syncData 那条路只把这一格排进「下个 tick 广播给正在跟踪该 chunk 的玩家」的队列
            //   （javap：ServerChunkManager#markForUpdate → ChunkHolder#markForBlockUpdate），
            //   漏了就是「客户端 platformIds 还是空的」。空集合在 RenderPIDS#getSchedules 里等于
            //   「自动模式」（就近站台），屏幕上看就是没绑过。所以这里显式再推一次整份 NBT。
            if (serverWorld != null) {
                MtrTiles.pushToClients(serverWorld, entry.tile(), entry.pos());
                // 区块落盘（setData 的 markDirty 已经会做，这里补一刀不依赖实现细节）。
                MtrTiles.markChunkSaved(serverWorld, entry.pos());
            }
        }
        if (written == 0) {
            say(player, "message.mtrlte.pids.pids_gone", Formatting.RED);
            return ActionResult.SUCCESS;
        }

        PidsLinkStore.get((ServerWorld) world).put(pidsPos, node, platformId, platformName);

        // 回读校验：真正躺在方块实体里的 platformIds 是什么（不是我们「打算写」的那个）。
        final Set<Long> verify = new TreeSet<>();
        for (final MtrTiles.PidsTile entry : pids) {
            verify.addAll(MtrTiles.pidsPlatformIds(entry.tile()));
        }
        LteLog.LOGGER.info("[lte] bind: pids verify platforms={} tiles=[{}]", verify, writtenAt);
        final boolean persisted = verify.contains(Long.valueOf(platformId));

        final String stationName = station == null || station.name == null || station.name.isEmpty()
                ? "-" : station.name;
        if (persisted) {
            say(player, "message.mtrlte.pids.linked", Formatting.GREEN, platformName, stationName);
        } else {
            // 写了但读不回来：MTR 的方法签名变了 / 这块 PIDS 的方块实体不是我们以为的那个类。
            say(player, "message.mtrlte.pids.verify_failed", Formatting.RED,
                    MtrTiles.join(verify), MtrTiles.describe(pids.get(0).tile()));
        }

        // MTR 的 PIDS 渲染也先按位置找车站（RenderPIDS#getSchedules：station 为空直接返回空表），
        // 站外的 PIDS 即使 platformIds 写对了也不会显示到站。这是 MTR 的模型，不是绑定失败。
        final Station atPids = LteMtrLookup.stationAt(world, pidsPos);
        if (atPids == null) {
            say(player, "message.mtrlte.pids.outside_station", Formatting.YELLOW);
        } else if (station != null && atPids.id != station.id) {
            // 写进去的站台属于别的车站 —— getSchedules 会拿「PIDS 所在车站」的站台列表去过滤，
            // 一个都对不上，屏幕是空的。
            say(player, "message.mtrlte.pids.platform_mismatch", Formatting.YELLOW,
                    platformName, stationName);
        }
        return ActionResult.SUCCESS;
    }

    /** 解绑：把 platformIds 清空（消息 / 隐藏位 / 翻页仍然保留）。 */
    private static boolean clearPids(final List<MtrTiles.PidsTile> pids, final World world) {
        boolean cleared = false;
        final ServerWorld serverWorld = world instanceof ServerWorld sw ? sw : null;
        for (final MtrTiles.PidsTile entry : pids) {
            if (MtrTiles.pidsPlatformIds(entry.tile()).isEmpty()) {
                continue;
            }
            if (!MtrTiles.pidsSetPlatforms(entry.tile(), Collections.emptyList())) {
                continue;
            }
            cleared = true;
            // 屏幕显示读的是「客户端自己的方块实体」：只写服务端不清客户端，屏幕会继续放原来的站台。
            if (serverWorld != null) {
                MtrTiles.pushToClients(serverWorld, entry.tile(), entry.pos());
                MtrTiles.markChunkSaved(serverWorld, entry.pos());
            }
        }
        return cleared;
    }

    // ------------------------------------------------------------------ 解绑（左键 / 方块被拆）

    /**
     * 客户端判定：这一格是不是 PIDS（任意一半，左键时要不要拦下来）。
     *
     * <p>不拦的话创造模式左键会直接把屏拆了 —— 玩家想解绑，结果把屏弄没了。
     */
    public static boolean isUnbindTarget(final World world, final BlockPos pos) {
        try {
            return !BindTargets.pidsAt(world, pos).isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 左键解绑：删表里的记录，<b>同时</b>把这块屏的 {@code platformIds} 清空。
     *
     * <p>只删表不清屏的话，屏幕照样在放原来那个站台的到站（显示读的是方块实体，不是我们的表），
     * 玩家会以为没解掉。清空 = 回到 MTR 的「自动模式」（就近站台）。
     *
     * @return 这一下归不归 PIDS 连接器管
     */
    public static boolean unlinkAt(final World world, final ServerPlayerEntity player, final BlockPos clicked) {
        if (!(world instanceof ServerWorld server)) {
            return false;
        }
        final List<MtrTiles.PidsTile> pids = BindTargets.pidsAt(world, clicked);
        if (pids.isEmpty()) {
            return false;
        }
        final boolean removed = !PidsLinkStore.get(server)
                .removeInvolving(BindTargets.dataPos(world, clicked)).isEmpty();
        final boolean cleared = clearPids(pids, world);
        if (removed || cleared) {
            say(player, "message.mtrlte.link.unlinked", Formatting.GREEN);
        } else {
            say(player, "message.mtrlte.link.none", Formatting.YELLOW);
        }
        return true;
    }

    /**
     * 方块被拆：清掉与这一格相关的记录，不弹提示。
     *
     * <p>两种拆法都要认：
     * <ul>
     *   <li>拆的是<b>屏</b>：记录的键就是它 —— 表要清，MTR 那份随方块实体消失，不用管；</li>
     *   <li>拆的是<b>节点</b>：记录里的 {@code node} 是它，屏还在 —— 表要清，屏的
     *       {@code platformIds} 也要清（否则这块屏继续显示一个已经拆掉的节点所属的站台，
     *       而且原地再放一个节点，这块屏会「自动继承」回来）。</li>
     * </ul>
     *
     * @param brokenLowerHalf 被拆的是双格屏的<b>下半</b>时传 {@code true}：数据格（键）在上面那一格，
     *                        MTR 拆下半会连带清掉上半，但我们这时已经读不到 HALF 属性了，只能由调用方
     *                        从「拆之前」的状态告诉这里
     */
    public static void unlinkBroken(final ServerWorld world, final BlockPos pos, final boolean brokenLowerHalf) {
        final PidsLinkStore store = PidsLinkStore.get(world);
        final Set<BlockPos> affected = new LinkedHashSet<>();
        affected.addAll(store.removeInvolving(pos).keySet());
        if (brokenLowerHalf) {
            // 键在上半：双格屏被拆下半时，记录键是 pos.up()。
            affected.addAll(store.removeInvolving(pos.up()).keySet());
        }
        for (final BlockPos pidsPos : affected) {
            final List<MtrTiles.PidsTile> pids = BindTargets.pidsAt(world, pidsPos);
            if (pids.isEmpty()) {
                // 屏本身被拆了：方块实体已经没了，没有可清的。
                continue;
            }
            clearPids(pids, world);
        }
    }

    // ------------------------------------------------------------------ 杂项

    private static void clearPending(final NbtCompound nbt) {
        nbt.remove(TAG_PIDS);
        nbt.remove(TAG_NODE);
        nbt.remove(TAG_DIM);
    }

    private static Text posText(final BlockPos pos) {
        return new LiteralText(pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
    }

    private static void say(final ServerPlayerEntity player, final String key,
                            final Formatting color, final Object... args) {
        player.sendMessage(new TranslatableText(key, args).formatted(color), true);
    }

    // ------------------------------------------------------------------ 提示

    @Override
    public void appendTooltip(final ItemStack stack, final World world,
                              final List<Text> tooltip, final TooltipContext context) {
        final NbtCompound nbt = stack.getNbt();
        if (nbt != null && nbt.contains(TAG_PIDS)) {
            tooltip.add(new TranslatableText("tooltip.mtrlte.pids.selected_pids",
                            posText(BlockPos.fromLong(nbt.getLong(TAG_PIDS))))
                    .formatted(Formatting.AQUA));
        } else if (nbt != null && nbt.contains(TAG_NODE)) {
            tooltip.add(new TranslatableText("tooltip.mtrlte.pids.selected_node",
                            posText(BlockPos.fromLong(nbt.getLong(TAG_NODE))))
                    .formatted(Formatting.AQUA));
        } else {
            tooltip.add(new TranslatableText("tooltip.mtrlte.pids.hint").formatted(Formatting.GRAY));
        }
        tooltip.add(new TranslatableText("tooltip.mtrlte.link.right_click").formatted(Formatting.GRAY));
        tooltip.add(new TranslatableText("tooltip.mtrlte.link.left_click").formatted(Formatting.DARK_GRAY));
    }
}
