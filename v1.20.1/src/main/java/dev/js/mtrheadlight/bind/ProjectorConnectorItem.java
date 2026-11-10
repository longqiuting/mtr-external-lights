package dev.js.mtrheadlight.bind;

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

import java.util.HashSet;
import java.util.List;

/**
 * 投影机连接器（{@code mtrlte:projector_connector}）：把 MTR 的到站投影机绑到一个<b>车站</b>上。
 *
 * <h2>两次右键，两种顺序</h2>
 * <table border="1">
 *   <caption>两次右键的语义</caption>
 *   <tr><th>第一次</th><th>第二次</th><th>结果</th></tr>
 *   <tr><td>投影机</td><td>车站范围内的任意方块</td><td>投影机显示该车站所有站台的到站</td></tr>
 *   <tr><td>车站范围内的任意方块</td><td>投影机</td><td>同上（只是顺序反过来）</td></tr>
 * </table>
 * 第一次点击的状态挂在物品 NBT 上（{@code lte_proj} / {@code lte_station_id} / {@code lte_dim}），
 * 和 {@code SignalLinkItem} 一样：{@code ItemUsageContext} 只给「这一次点击」的方块，跨两次点击的
 * 状态只能挂在玩家手上。NBT 只由服务端写（{@code world.isClient} 时直接返回），客户端伪造不了。
 *
 * <h2>写进去的是什么</h2>
 * <p>MTR 的投影机里没有「车站名」这个字段 —— {@code TileEntityArrivalProjectorBase} 只有
 * {@code Set<Long> platformIds} 和 {@code int displayPage}（NBT {@code platform_ids} / {@code display_page}），
 * 「属于哪个车站」是靠 platformIds 反推的。所以绑定时做的事是：
 * <pre>{@code
 * te.setData(该车站名下所有站台的 id, te.getDisplayPage());
 * }</pre>
 * {@code setData} 是 public 的，内部会 {@code markDirty()} + {@code syncData()}
 * （后者会把新 NBT 推给附近客户端，投影机立刻改显示）。<b>不需要 mixin 去顶渲染</b>，也不改
 * 任何 BlockState —— 和信号连接器一样，绑定动作没有副作用。
 *
 * <p>两条 MTR 自身的限制（本类只提示，改不了）：
 * <ol>
 *   <li>MTR 渲染时按<b>方块自己的位置</b>去 {@code RailwayData.getStation(...)} 找车站
 *       （{@code RenderPIDS#getSchedules}），再用 platformIds 在这座车站的站台里过滤。
 *       所以「投影机站在 A 车站里、却绑到 B 车站」会得到空屏 —— 绑定时检测到这种情况会黄字提醒；</li>
 *   <li>MTR 里没有「投影机 → 车站」这个字段，所以「当初绑的是哪个车站」我们自己另存一份
 *       （{@link ProjectorLinkStore}，{@code world/data/mtrlte_projector_links.dat}），
 *       供 {@code /ltebind projector <x> <y> <z>} 查询。</li>
 * </ol>
 */
public final class ProjectorConnectorItem extends Item {

    private static final String TAG_PROJECTOR = "lte_proj";
    private static final String TAG_STATION = "lte_station_id";
    private static final String TAG_STATION_POS = "lte_station_pos";
    private static final String TAG_STATION_NAME = "lte_station_name";
    private static final String TAG_DIM = "lte_dim";

    public ProjectorConnectorItem(final Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult useOnBlock(final ItemUsageContext context) {
        final World world = context.getWorld();
        if (world.isClient) {
            // 服务端权威：客户端只负责摆手。
            return ActionResult.SUCCESS;
        }
        final ServerPlayerEntity player = context.getPlayer() instanceof ServerPlayerEntity p ? p : null;
        if (player == null) {
            return ActionResult.PASS;
        }

        final BlockPos clicked = context.getBlockPos();
        if (BindTargets.isProjector(world, clicked)) {
            return onProjectorClicked(world, player, context.getStack(), clicked);
        }
        return onStationClicked(world, player, context.getStack(), clicked);
    }

    // ------------------------------------------------------------------ 投影机这一侧

    private ActionResult onProjectorClicked(final World world, final ServerPlayerEntity player,
                                            final ItemStack stack, final BlockPos projector) {
        final NbtCompound nbt = stack.getOrCreateNbt();
        final String dim = world.getRegistryKey().getValue().toString();

        // 潜行右键 = 解绑：清掉我们这张表，同时把 MTR 里的 platformIds 清空（否则投影机还挂在
        // 原来的车站上，玩家会以为解绑没生效）。
        if (player.isSneaking()) {
            final boolean removed = ProjectorLinkStore.get((ServerWorld) world).remove(projector);
            final boolean cleared = clearProjector(world, projector);
            if (removed || cleared) {
                say(player, "message.mtrlte.projector.unlinked", Formatting.YELLOW);
            } else {
                say(player, "message.mtrlte.projector.not_linked", Formatting.GRAY);
            }
            clearPending(nbt);
            return ActionResult.SUCCESS;
        }

        // 反向顺序：上一步点的是车站范围里的方块 → 这一次点投影机，完成绑定。
        if (nbt.contains(TAG_STATION)) {
            if (!dim.equals(nbt.getString(TAG_DIM))) {
                say(player, "message.mtrlte.projector.wrong_dim", Formatting.RED);
                return ActionResult.SUCCESS;
            }
            final long stationId = nbt.getLong(TAG_STATION);
            final String stationName = nbt.getString(TAG_STATION_NAME);
            final BlockPos stationPos = nbt.contains(TAG_STATION_POS)
                    ? BlockPos.fromLong(nbt.getLong(TAG_STATION_POS)) : null;
            clearPending(nbt);
            // 车站优先按「当初点的那一格」重新几何求一次：车站被改过范围也还是同一个对象；
            // 那一格已经不在车站里了才退回 id 查表。
            Station station = stationPos == null ? null : LteMtrLookup.stationAt(world, stationPos);
            if (station == null) {
                station = LteMtrLookup.stationById(world, stationId);
            }
            if (station == null) {
                say(player, "message.mtrlte.projector.no_station", Formatting.RED);
                return ActionResult.SUCCESS;
            }
            return bind(world, player, projector, station, stationName);
        }

        nbt.putLong(TAG_PROJECTOR, projector.asLong());
        nbt.putString(TAG_DIM, dim);
        say(player, "message.mtrlte.projector.projector_set", Formatting.AQUA, posText(projector));
        return ActionResult.SUCCESS;
    }

    // ------------------------------------------------------------------ 车站这一侧

    private ActionResult onStationClicked(final World world, final ServerPlayerEntity player,
                                          final ItemStack stack, final BlockPos clicked) {
        final Station station = LteMtrLookup.stationAt(world, clicked);
        if (station == null) {
            say(player, "message.mtrlte.projector.no_station", Formatting.RED);
            return ActionResult.PASS;
        }
        final String stationName = station.name == null ? "" : station.name;
        final NbtCompound nbt = stack.getOrCreateNbt();

        // 正向顺序：上一步点的是投影机 → 这一次点车站，完成绑定。
        if (nbt.contains(TAG_PROJECTOR)) {
            final String dim = world.getRegistryKey().getValue().toString();
            if (!dim.equals(nbt.getString(TAG_DIM))) {
                say(player, "message.mtrlte.projector.wrong_dim", Formatting.RED);
                return ActionResult.SUCCESS;
            }
            final BlockPos projector = BlockPos.fromLong(nbt.getLong(TAG_PROJECTOR));
            clearPending(nbt);
            if (!BindTargets.isProjector(world, projector)) {
                say(player, "message.mtrlte.projector.projector_gone", Formatting.RED);
                return ActionResult.SUCCESS;
            }
            return bind(world, player, projector, station, stationName);
        }

        // 什么都没选 → 这个车站成为「源」，下一次点投影机。
        nbt.putLong(TAG_STATION, station.id);
        nbt.putLong(TAG_STATION_POS, clicked.asLong());
        nbt.putString(TAG_STATION_NAME, stationName);
        nbt.putString(TAG_DIM, world.getRegistryKey().getValue().toString());
        say(player, "message.mtrlte.projector.station_set", Formatting.AQUA, stationName);
        return ActionResult.SUCCESS;
    }

    // ------------------------------------------------------------------ 真正的写入

    private ActionResult bind(final World world, final ServerPlayerEntity player,
                              final BlockPos projector, final Station station,
                              final String stationName) {
        final Object tile = BindTargets.projectorAt(world, projector);
        if (tile == null) {
            say(player, "message.mtrlte.projector.projector_gone", Formatting.RED);
            return ActionResult.SUCCESS;
        }
        final List<Long> platforms = LteMtrLookup.platformIdsOf(world, station);
        if (!MtrTiles.projectorSetPlatforms(tile, platforms)) {
            say(player, "message.mtrlte.projector.projector_gone", Formatting.RED);
            return ActionResult.SUCCESS;
        }

        ProjectorLinkStore.get((ServerWorld) world)
                .put(projector, station.id, stationName, platforms.size());

        final String shown = stationName == null || stationName.isEmpty()
                ? (station.name == null ? "" : station.name) : stationName;
        say(player, "message.mtrlte.projector.linked", Formatting.GREEN,
                shown, Integer.valueOf(platforms.size()));
        if (platforms.isEmpty()) {
            // 车站画了范围但一个站台都没划进去：绑定本身成立，只是没东西可显示。
            say(player, "message.mtrlte.projector.no_platforms", Formatting.YELLOW);
        }
        // MTR 渲染时是拿「投影机所在位置」去 getStation(...) 的（RenderPIDS#getSchedules），
        // 只在这座车站的站台列表里按 platformIds 过滤。所以投影机站在别的车站里时，
        // 我们写的 id 一个都对不上，屏幕上会是空的 —— 这属于 MTR 的模型限制，只能明确告诉玩家。
        final Station here = LteMtrLookup.stationAt(world, projector);
        if (here == null || here.id != station.id) {
            say(player, "message.mtrlte.projector.station_mismatch", Formatting.YELLOW, shown);
        }
        return ActionResult.SUCCESS;
    }

    /** 解绑时把投影机的 platformIds 清空（保留 displayPage，别把玩家的翻页设置也弄丢）。 */
    private static boolean clearProjector(final World world, final BlockPos projector) {
        final Object tile = BindTargets.projectorAt(world, projector);
        if (tile == null) {
            return false;
        }
        if (MtrTiles.projectorPlatformIds(tile).isEmpty()) {
            return false;
        }
        return MtrTiles.projectorSetPlatforms(tile, java.util.Collections.emptyList());
    }

    // ------------------------------------------------------------------ 杂项

    private static void clearPending(final NbtCompound nbt) {
        nbt.remove(TAG_PROJECTOR);
        nbt.remove(TAG_STATION);
        nbt.remove(TAG_STATION_POS);
        nbt.remove(TAG_STATION_NAME);
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
        if (nbt != null && nbt.contains(TAG_PROJECTOR)) {
            tooltip.add(Text.translatable("tooltip.mtrlte.projector.selected_projector",
                            posText(BlockPos.fromLong(nbt.getLong(TAG_PROJECTOR))))
                    .formatted(Formatting.AQUA));
        } else if (nbt != null && nbt.contains(TAG_STATION)) {
            tooltip.add(Text.translatable("tooltip.mtrlte.projector.selected_station",
                            nbt.getString(TAG_STATION_NAME))
                    .formatted(Formatting.AQUA));
        } else {
            tooltip.add(Text.translatable("tooltip.mtrlte.projector.hint").formatted(Formatting.GRAY));
        }
        tooltip.add(Text.translatable("tooltip.mtrlte.projector.unbind").formatted(Formatting.DARK_GRAY));
    }
}
