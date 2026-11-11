package dev.js.mtrheadlight.bind;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Set;

/**
 * 服务端查询指令 {@code /ltebind}：看看某个投影机 / PIDS 现在绑到了哪儿。
 *
 * <p>为什么是服务端指令而不是塞进 {@code /mtr_lte}：{@code /mtr_lte} 是<b>客户端</b>指令
 * （{@code ClientCommandRegistrationCallback}，锚在 {@code FabricClientCommandSource} 上），而绑定表是
 * 服务端的 {@code PersistentState} —— 客户端读不到。同名根在客户端/服务端指令树合并时还会让两边的
 * 参数类型撞在一起，所以这里用独立的根 {@code /ltebind}。
 *
 * <pre>
 *   /ltebind projector &lt;x&gt; &lt;y&gt; &lt;z&gt;   投影机绑在哪个车站、MTR 里现在写着几个站台
 *   /ltebind pids &lt;x&gt; &lt;y&gt; &lt;z&gt;        PIDS 绑在哪个站台 / 哪个车站
 * </pre>
 *
 * <p>输出同时给「我们表里记的」和「MTR 方块实体里实际写着的」两份 —— 两者不一致时（比如有人用 MTR
 * 自己的界面改了 PIDS 的站台），一眼就能看出来。
 */
public final class LteBindCommand {

    private LteBindCommand() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("ltebind")
                        .then(CommandManager.literal("projector")
                                .then(coord("x")
                                        .then(coord("y")
                                                .then(coord("z")
                                                        .executes(LteBindCommand::projector)))))
                        .then(CommandManager.literal("pids")
                                .then(coord("x")
                                        .then(coord("y")
                                                .then(coord("z")
                                                        .executes(LteBindCommand::pids)))))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<ServerCommandSource, Integer> coord(
            final String name) {
        return CommandManager.argument(name, IntegerArgumentType.integer());
    }

    private static BlockPos pos(final CommandContext<ServerCommandSource> context) {
        return new BlockPos(IntegerArgumentType.getInteger(context, "x"),
                IntegerArgumentType.getInteger(context, "y"),
                IntegerArgumentType.getInteger(context, "z"));
    }

    // ------------------------------------------------------------------ projector

    private static int projector(final CommandContext<ServerCommandSource> context) {
        final ServerCommandSource source = context.getSource();
        final ServerWorld world = source.getWorld();
        final BlockPos pos = pos(context);

        final ProjectorLinkStore.Link link = ProjectorLinkStore.get(world).at(pos);
        if (link == null) {
            source.sendFeedback(Text.translatable("message.mtrlte.bind.projector_none",
                    posText(pos)).formatted(Formatting.GRAY), false);
        } else {
            source.sendFeedback(Text.translatable("message.mtrlte.bind.projector_link",
                    posText(pos), link.stationName(), String.valueOf(link.stationId()),
                    String.valueOf(link.platformCount())).formatted(Formatting.GREEN), false);
        }

        // 方块实体里实际写着的 platformIds（真值，与 MTR 自己的界面读写的是同一份）
        final Object tile = BindTargets.projectorAt(world, pos);
        if (tile == null) {
            source.sendFeedback(Text.translatable("message.mtrlte.bind.not_a_projector")
                    .formatted(Formatting.RED), false);
            return 0;
        }
        final Set<Long> ids = MtrTiles.projectorPlatformIds(tile);
        final String list = MtrTiles.join(ids);
        final String displayPage = String.valueOf(readDisplayPage(tile));
        source.sendFeedback(Text.translatable("message.mtrlte.bind.live_platforms",
                String.valueOf(ids.size()), list.isEmpty() ? "-" : list)
                .formatted(Formatting.AQUA).append(" ").append(
                        Text.translatable("message.mtrlte.bind.display_page", displayPage)
                                .formatted(Formatting.AQUA)), false);
        return 1;
    }

    // ------------------------------------------------------------------ pids

    private static int pids(final CommandContext<ServerCommandSource> context) {
        final ServerCommandSource source = context.getSource();
        final ServerWorld world = source.getWorld();
        final BlockPos pos = pos(context);

        final PidsLinkStore.Link link = PidsLinkStore.get(world).at(BindTargets.dataPos(world, pos));
        if (link == null) {
            source.sendFeedback(Text.translatable("message.mtrlte.bind.pids_none",
                    posText(pos)).formatted(Formatting.GRAY), false);
        } else {
            source.sendFeedback(Text.translatable("message.mtrlte.bind.pids_link",
                    posText(pos), link.platformName(), String.valueOf(link.platformId()),
                    posText(link.nodePos())).formatted(Formatting.GREEN), false);
        }

        final List<MtrTiles.PidsTile> tiles = BindTargets.pidsAt(world, pos);
        if (tiles.isEmpty()) {
            source.sendFeedback(Text.translatable("message.mtrlte.bind.not_a_pids")
                    .formatted(Formatting.RED), false);
            return 0;
        }
        // 一台 PIDS 两格：两半各自的 platformIds 都打出来（不一致就说明只有一半被写过）。
        for (final MtrTiles.PidsTile entry : tiles) {
            final Set<Long> ids = MtrTiles.pidsPlatformIds(entry.tile());
            final String list = MtrTiles.join(ids);
            final String where = posText(entry.pos());
            source.sendFeedback(Text.translatable("message.mtrlte.bind.pids_live",
                    where, String.valueOf(ids.size()), list.isEmpty() ? "-" : list)
                    .formatted(Formatting.AQUA), false);
        }
        return 1;
    }

    // ------------------------------------------------------------------ 杂项

    private static String readDisplayPage(final Object tile) {
        try {
            return String.valueOf(tile.getClass().getMethod("getDisplayPage").invoke(tile));
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String posText(final BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
}
