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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 投影机连接器（{@code mtrlte:projector_connector}）：把 MTR 的到站投影机绑到「某个站台所属的车站」。
 *
 * <h2>两次右键，两种顺序</h2>
 * <table border="1">
 *   <caption>两次右键的语义</caption>
 *   <tr><th>第一次</th><th>第二次</th><th>结果</th></tr>
 *   <tr><td>投影机</td><td>站台方块 / 轨道节点 / 车站范围内的方块</td><td>投影机显示该车站所有站台的到站</td></tr>
 *   <tr><td>站台方块 / 轨道节点 / 车站范围内的方块</td><td>投影机</td><td>同上（只是顺序反过来）</td></tr>
 * </table>
 *
 * <h2>为什么第二次点的东西不再要求「车站」</h2>
 * <p>以前这条路是 {@code stationAt(点到的位置)} 或「站台 → 车站」两步，站外点轨道节点时第一步必然空，
 * 第二步又依赖 {@code platformIdToStation} 这张只在 {@code DataCache#sync} 之后才准的表，于是经常
 * 返回 {@code null}，玩家得到的就是「这里推不出车站」。现在和服务端 PIDS 那条路完全一样：
 * <b>一律先解析站台</b>（{@link LteMtrLookup#platformIdOnRail} 三级判定：MTR 的
 * {@code getClosePlatformId}（±5 格）→ 这一格是不是站台轨道 → <b>沿 {@code getRailsMap()} 做 BFS</b>
 * 走到第一个站台），再由站台反推车站（{@link LteMtrLookup#stationOfPlatform}）。解析不到车站时
 * 仍然允许绑定 —— 把解析到的那一个站台 id 写进投影机，总比什么都不写强。
 *
 * <h2>写进去的是什么（javap 证据）</h2>
 * <p>{@code mtr.block.BlockArrivalProjectorBase$TileEntityArrivalProjectorBase} 的字段只有：
 * <pre>{@code
 * private final Set<Long> platformIds;   // NBT "platform_ids"，long[]
 * private int displayPage;               // NBT "display_page"
 * public void setData(Set<Long> platformIds, int displayPage);
 * }</pre>
 * <b>没有 station 参数、没有车站字段</b> —— 所以「绑定到车站」在 MTR 的数据模型里就是
 * 「把这几个站台的 id 写进 platformIds」，别的写法 MTR 都不认。绑定动作是：
 * <pre>{@code
 * te.setData(该车站名下所有站台的 id, te.getDisplayPage());
 * }</pre>
 * {@code setData} 是 public 的，内部 {@code markDirty()}（javap：{@code BlockEntity#markDirty} =
 * {@code method_5431}）+ {@code syncData()}。<b>不需要 mixin 去顶渲染</b>，也不改任何 BlockState。
 *
 * <p>两条 MTR 自身的限制（本类只提示，改不了）：
 * <ol>
 *   <li>MTR 渲染时按<b>方块自己的位置</b>去 {@code RailwayData.getStation(...)} 找车站
 *       （{@code RenderPIDS#getSchedules} 第一行就 {@code return new ArrayList<>()}），再用 platformIds
 *       在这座车站的站台里过滤。所以「投影机站在 A 车站里、却绑到 B 车站」会得到空屏；</li>
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
    /** 解析到的站台 id。站外绑定时这是唯一可靠的锚点（车站可能推不出来）。 */
    private static final String TAG_PLATFORM = "lte_platform_id";
    private static final String TAG_DIM = "lte_dim";

    /** 一次绑定的目标：车站（可能 {@code null}）+ 解析到的那一个站台 + 显示名。 */
    private record Target(Station station, long platformId, String name) {
    }

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

        // 反向顺序：上一步点的是站台 / 节点 / 车站范围内的方块 → 这一次点投影机，完成绑定。
        if (nbt.contains(TAG_STATION) || nbt.contains(TAG_PLATFORM)) {
            if (!dim.equals(nbt.getString(TAG_DIM))) {
                say(player, "message.mtrlte.projector.wrong_dim", Formatting.RED);
                return ActionResult.SUCCESS;
            }
            final Target target = resolve(world, nbt);
            clearPending(nbt);
            if (target == null) {
                say(player, "message.mtrlte.projector.no_station", Formatting.RED);
                return ActionResult.SUCCESS;
            }
            return bind(world, player, projector, target);
        }

        nbt.putLong(TAG_PROJECTOR, projector.asLong());
        nbt.putString(TAG_DIM, dim);
        say(player, "message.mtrlte.projector.projector_set", Formatting.AQUA, posText(projector));
        return ActionResult.SUCCESS;
    }

    // ------------------------------------------------------------------ 站台 / 节点这一侧

    private ActionResult onStationClicked(final World world, final ServerPlayerEntity player,
                                          final ItemStack stack, final BlockPos clicked) {
        // 和服务端 PIDS 同一套三级解析（含沿轨道 BFS）：站台附近的节点、站台轨道本身、以及
        // 站在站外很远的节点都能推到一个站台。以前这里走的是 stationAt → platformIdToStation，
        // 后者在「刚划完站台还没 sync」和「站在站外」两种情况下都是空的。
        final long platformId = LteMtrLookup.platformIdOnRail(world, clicked);
        if (platformId == 0L) {
            // 真的推不出来：明确告诉玩家该点什么，别静默失败。
            say(player, "message.mtrlte.projector.no_station_here", Formatting.RED);
            return ActionResult.PASS;
        }
        final Station station = pickStation(world, clicked, platformId);
        final String name = nameOf(world, station, platformId);
        final NbtCompound nbt = stack.getOrCreateNbt();

        // 正向顺序：上一步点的是投影机 → 这一次点站台/节点，完成绑定。
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
            return bind(world, player, projector, new Target(station, platformId, name));
        }

        // 什么都没选 → 这个车站/站台成为「源」，下一次点投影机。
        nbt.putLong(TAG_PLATFORM, platformId);
        if (station != null) {
            nbt.putLong(TAG_STATION, station.id);
        }
        nbt.putLong(TAG_STATION_POS, clicked.asLong());
        nbt.putString(TAG_STATION_NAME, name);
        nbt.putString(TAG_DIM, world.getRegistryKey().getValue().toString());
        say(player, "message.mtrlte.projector.station_set", Formatting.AQUA, name);
        return ActionResult.SUCCESS;
    }

    /** 站台 → 车站；站台反推不出来时退到「这一格在不在某个车站矩形里」。 */
    private static Station pickStation(final World world, final BlockPos clicked, final long platformId) {
        final Station byPlatform = LteMtrLookup.stationOfPlatform(world, platformId);
        if (byPlatform != null) {
            return byPlatform;
        }
        return LteMtrLookup.stationAt(world, clicked);
    }

    /** 显示名：车站名优先；只有站台时用站台名。 */
    private static String nameOf(final World world, final Station station, final long platformId) {
        if (station != null && station.name != null && !station.name.isEmpty()) {
            return station.name;
        }
        final String platformName = LteMtrLookup.platformName(world, platformId);
        return platformName == null ? "" : platformName;
    }

    // ------------------------------------------------------------------ 真正的写入

    /**
     * 把「上一步选中的东西」重新解析成一次绑定目标。
     *
     * <p>逐级回退，任何一级成功就停：
     * <ol>
     *   <li>当初点的那一格还在某个车站矩形里 → 就是它（车站被改过范围也还是同一个对象）；</li>
     *   <li>车站 id 查 {@code DataCache.stationIdMap}；</li>
     *   <li>当初点的那一格重跑一次 {@link LteMtrLookup#platformIdOnRail}（轨道可能被改过）；</li>
     *   <li>由站台反推车站。</li>
     * </ol>
     * 车站推不出来但站台还在时不算失败 —— 返回一个 {@code station == null} 的目标。
     */
    private static Target resolve(final World world, final NbtCompound nbt) {
        final long stationId = nbt.getLong(TAG_STATION);
        final long savedPlatform = nbt.getLong(TAG_PLATFORM);
        final BlockPos stationPos = nbt.contains(TAG_STATION_POS)
                ? BlockPos.fromLong(nbt.getLong(TAG_STATION_POS)) : null;

        Station station = stationPos == null ? null : LteMtrLookup.stationAt(world, stationPos);
        if (station == null && stationId != 0L) {
            station = LteMtrLookup.stationById(world, stationId);
        }
        long platformId = savedPlatform;
        if (platformId == 0L && stationPos != null) {
            platformId = LteMtrLookup.platformIdOnRail(world, stationPos);
        }
        if (station == null && platformId != 0L) {
            station = LteMtrLookup.stationOfPlatform(world, platformId);
        }
        if (station == null && platformId == 0L) {
            return null;
        }
        String name = nbt.getString(TAG_STATION_NAME);
        if (name == null || name.isEmpty()) {
            name = nameOf(world, station, platformId);
        }
        return new Target(station, platformId, name);
    }

    private ActionResult bind(final World world, final ServerPlayerEntity player,
                              final BlockPos projector, final Target target) {
        final Object tile = BindTargets.projectorAt(world, projector);
        if (tile == null) {
            say(player, "message.mtrlte.projector.projector_gone", Formatting.RED);
            return ActionResult.SUCCESS;
        }
        final List<Long> platforms = new ArrayList<>();
        if (target.station() != null) {
            platforms.addAll(LteMtrLookup.platformIdsOf(world, target.station()));
        }
        // 车站推不出来（站外绑定），或者这座车站名下还没登记任何站台 → 把解析到的那一个写进去。
        if (platforms.isEmpty() && target.platformId() != 0L) {
            platforms.add(Long.valueOf(target.platformId()));
        }
        LteLog.LOGGER.info("[lte] bind: projector pos={} platform={} -> platforms={}",
                projector, Long.valueOf(target.platformId()), platforms);
        if (!MtrTiles.projectorSetPlatforms(tile, platforms)) {
            say(player, "message.mtrlte.projector.projector_gone", Formatting.RED);
            return ActionResult.SUCCESS;
        }
        // 双端 + 落盘：setData 里的 syncData 只排「下个 tick 广播」，这里显式推一次、
        // 再把区块标脏（理由见 MtrTiles#pushToClients / #markChunkSaved）。
        if (world instanceof ServerWorld serverWorld) {
            MtrTiles.pushToClients(serverWorld, tile, projector);
            MtrTiles.markChunkSaved(serverWorld, projector);
        }

        // 回读校验：真正躺在方块实体里的 platformIds。
        final Set<Long> verify = new TreeSet<>(MtrTiles.projectorPlatformIds(tile));
        LteLog.LOGGER.info("[lte] bind: projector verify platforms={}", verify);

        final long stationId = target.station() == null ? 0L : target.station().id;
        ProjectorLinkStore.get((ServerWorld) world)
                .put(projector, stationId, target.name(), platforms.size());

        final String shown = target.name() == null || target.name().isEmpty()
                ? (target.station() != null && target.station().name != null ? target.station().name : "")
                : target.name();
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
        if (target.station() != null && (here == null || here.id != target.station().id)) {
            say(player, "message.mtrlte.projector.station_mismatch", Formatting.YELLOW, shown);
        } else if (target.station() == null && here == null) {
            // 站外 + 站台也反推不出车站：写进去的是单个站台，MTR 在投影机位置找不到车站，同样不显示。
            say(player, "message.mtrlte.projector.outside_station", Formatting.YELLOW);
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
        final boolean cleared = MtrTiles.projectorSetPlatforms(tile, java.util.Collections.emptyList());
        if (cleared && world instanceof ServerWorld serverWorld) {
            MtrTiles.pushToClients(serverWorld, tile, projector);
            MtrTiles.markChunkSaved(serverWorld, projector);
        }
        return cleared;
    }

    // ------------------------------------------------------------------ 杂项

    private static void clearPending(final NbtCompound nbt) {
        nbt.remove(TAG_PROJECTOR);
        nbt.remove(TAG_STATION);
        nbt.remove(TAG_STATION_POS);
        nbt.remove(TAG_STATION_NAME);
        nbt.remove(TAG_PLATFORM);
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
        } else if (nbt != null && nbt.contains(TAG_PLATFORM)) {
            // 站外绑定：只解析到站台，没有车站对象。
            tooltip.add(Text.translatable("tooltip.mtrlte.projector.selected_platform",
                            nbt.getString(TAG_STATION_NAME))
                    .formatted(Formatting.AQUA));
        } else {
            tooltip.add(Text.translatable("tooltip.mtrlte.projector.hint").formatted(Formatting.GRAY));
        }
        tooltip.add(Text.translatable("tooltip.mtrlte.projector.unbind").formatted(Formatting.DARK_GRAY));
    }
}
