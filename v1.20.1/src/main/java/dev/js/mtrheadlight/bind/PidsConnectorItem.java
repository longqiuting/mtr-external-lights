package dev.js.mtrheadlight.bind;

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
import java.util.List;

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
 * 不按类名认，按<b>方块实体类型</b>认：{@code mtr.data.IPIDS.TileEntityPIDS}。MTR 的
 * {@code BlockPIDSBaseHorizontal} / {@code BlockPIDSBaseVertical}、天津地铁
 * {@code ziyue.tjmetro.block.BlockPIDSTianjin}、JCM {@code com.jsblock.block.JobanPIDSBase}
 * 的方块实体全部继承它（反编译确认），所以一条 {@code instanceof} 覆盖三家。
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

        // 1) 右键 PIDS（任意一半都算）
        final List<MtrTiles.PidsTile> pids = BindTargets.pidsAt(world, clicked);
        if (!pids.isEmpty()) {
            return onPidsClicked(world, player, context.getStack(), clicked, pids);
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
            final boolean cleared = clearPids(pids);
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
        final long platformId = LteMtrLookup.platformIdOnRail(world, node);
        if (platformId == 0L) {
            LteLog.LOGGER.info("[lte] bind: pids pos={} node={} -> platforms=[]", pidsPos, node);
            say(player, "message.mtrlte.pids.no_platform", Formatting.RED);
            return ActionResult.SUCCESS;
        }
        LteLog.LOGGER.info("[lte] bind: pids pos={} -> platforms={}",
                pidsPos, Collections.singletonList(Long.valueOf(platformId)));
        final String platformName = LteMtrLookup.platformName(world, platformId);
        final Station station = LteMtrLookup.stationOfPlatform(world, platformId);

        int written = 0;
        for (final MtrTiles.PidsTile entry : pids) {
            if (MtrTiles.pidsBindSingle(entry.tile(), platformId)) {
                written++;
            }
        }
        if (written == 0) {
            say(player, "message.mtrlte.pids.pids_gone", Formatting.RED);
            return ActionResult.SUCCESS;
        }

        PidsLinkStore.get((ServerWorld) world).put(pidsPos, node, platformId, platformName);

        final String stationName = station == null || station.name == null || station.name.isEmpty()
                ? "-" : station.name;
        say(player, "message.mtrlte.pids.linked", Formatting.GREEN, platformName, stationName);
        // MTR 的 PIDS 渲染也先按位置找车站（RenderPIDS#getSchedules：station 为空直接返回空表），
        // 站外的 PIDS 即使 platformIds 写对了也不会显示到站。这是 MTR 的模型，不是绑定失败。
        if (LteMtrLookup.stationAt(world, pidsPos) == null) {
            say(player, "message.mtrlte.pids.outside_station", Formatting.YELLOW);
        }
        return ActionResult.SUCCESS;
    }

    /** 解绑：把 platformIds 清空（消息 / 隐藏位 / 翻页仍然保留）。 */
    private static boolean clearPids(final List<MtrTiles.PidsTile> pids) {
        boolean cleared = false;
        for (final MtrTiles.PidsTile entry : pids) {
            if (MtrTiles.pidsPlatformIds(entry.tile()).isEmpty()) {
                continue;
            }
            if (MtrTiles.pidsSetPlatforms(entry.tile(), Collections.emptyList())) {
                cleared = true;
            }
        }
        return cleared;
    }

    // ------------------------------------------------------------------ 杂项

    private static void clearPending(final NbtCompound nbt) {
        nbt.remove(TAG_PIDS);
        nbt.remove(TAG_NODE);
        nbt.remove(TAG_DIM);
    }

    private static Text posText(final BlockPos pos) {
        return Text.literal(pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
    }

    private static void say(final ServerPlayerEntity player, final String key,
                            final Formatting color, final Object... args) {
        player.sendMessage(Text.translatable(key, args).formatted(color), true);
    }

    // ------------------------------------------------------------------ 提示

    @Override
    public void appendTooltip(final ItemStack stack, final World world,
                              final List<Text> tooltip, final TooltipContext context) {
        final NbtCompound nbt = stack.getNbt();
        if (nbt != null && nbt.contains(TAG_PIDS)) {
            tooltip.add(Text.translatable("tooltip.mtrlte.pids.selected_pids",
                            posText(BlockPos.fromLong(nbt.getLong(TAG_PIDS))))
                    .formatted(Formatting.AQUA));
        } else if (nbt != null && nbt.contains(TAG_NODE)) {
            tooltip.add(Text.translatable("tooltip.mtrlte.pids.selected_node",
                            posText(BlockPos.fromLong(nbt.getLong(TAG_NODE))))
                    .formatted(Formatting.AQUA));
        } else {
            tooltip.add(Text.translatable("tooltip.mtrlte.pids.hint").formatted(Formatting.GRAY));
        }
        tooltip.add(Text.translatable("tooltip.mtrlte.pids.unbind").formatted(Formatting.DARK_GRAY));
    }
}
