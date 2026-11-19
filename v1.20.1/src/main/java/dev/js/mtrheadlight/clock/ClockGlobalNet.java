// 全局时钟配置通道：快照（S2C）/ 保存（C2S）/ 试听（C2S）。
package dev.js.mtrheadlight.clock;

import dev.js.mtrheadlight.LteLog;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 全局时钟配置的通道 {@code mtrlte:clock_global}，第一字节区分：
 *
 * <ul>
 *   <li>{@link #KIND_SNAPSHOT} <b>S2C</b> —— 服务端把「是否启用 + 整份配置」推下来，客户端写进
 *       {@link ClockGlobalCache}；</li>
 *   <li>{@link #KIND_SAVE} <b>C2S</b> —— 仪表板里点「完成」，整份写回来（要求权限等级 ≥ 2）；</li>
 *   <li>{@link #KIND_PREVIEW} <b>C2S</b> —— 「试听」：在<b>玩家自己的位置</b>播一下全局配置的报时音
 *       （全局没有方块可站，只能这样听）。</li>
 * </ul>
 *
 * <h2>谁在什么时候收快照</h2>
 * <ol>
 *   <li>玩家进服（{@link ServerPlayConnectionEvents.JOIN}，见 {@link #register()}）；</li>
 *   <li>配置变更后广播给所有人（{@link #applySave}）。</li>
 * </ol>
 *
 * <h2>权限</h2>
 * <p>改全局 = 改所有人的钟，所以只认<b>权限等级 ≥ {@value #MIN_PERMISSION}</b>（OP / 命令方块级别
 * 2）。服务端校验，不满足就回一句聊天提示，配置一个字都不动。读（快照）不限权限 —— 每个人都得
 * 看得见全局样式的钟。
 */
public final class ClockGlobalNet {

    /** 通道 id。 */
    public static final Identifier ID = new Identifier("mtrlte", "clock_global");

    /** S2C：快照（enabled + 整份配置）。 */
    public static final byte KIND_SNAPSHOT = 0;
    /** C2S：保存（enabled + 整份配置）。 */
    public static final byte KIND_SAVE = 1;
    /** C2S：试听（无负载）。 */
    public static final byte KIND_PREVIEW = 2;

    /** 改全局配置需要的最低权限等级（2 = OP / 命令方块允许的等级）。 */
    public static final int MIN_PERMISSION = 2;

    private static boolean registered;

    private ClockGlobalNet() {
    }

    /** 服务端接收器 + 进服快照。由 {@link ClockObjects#register()} 调一次。 */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        try {
            ServerPlayNetworking.registerGlobalReceiver(ID, (server, player, handler, buf, sender) -> {
                final byte kind;
                final ClockGlobalConfig cfg;
                try {
                    kind = buf.readByte();
                    cfg = kind == KIND_SAVE ? ClockGlobalConfig.readBuf(buf) : null;
                } catch (Throwable bad) {
                    return; // 坏包：当没收到。
                }
                server.execute(() -> {
                    if (kind == KIND_SAVE) {
                        applySave(server, player, cfg);
                    } else if (kind == KIND_PREVIEW) {
                        applyPreview(server, player);
                    }
                });
            });
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] clock global channel unavailable", t);
        }

        try {
            ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
                try {
                    send(handler.player, server);
                } catch (Throwable ignored) {
                    // 快照发不出去只是全局样式暂时不生效，进服照常。
                }
            });
        } catch (Throwable t) {
            LteLog.LOGGER.debug("[lte] could not hook the clock global join sync", t);
        }
    }

    // ---------------------------------------------------------------- S2C

    /** 打包一份快照。 */
    public static PacketByteBuf snapshotPacket(final ClockGlobalConfig cfg) {
        final PacketByteBuf buf = PacketByteBufs.create();
        buf.writeByte(KIND_SNAPSHOT);
        cfg.writeBuf(buf);
        return buf;
    }

    /** 推一份快照给一个玩家。 */
    public static void send(final ServerPlayerEntity player, final MinecraftServer server) {
        if (player == null || !ServerPlayNetworking.canSend(player, ID)) {
            return;
        }
        ServerPlayNetworking.send(player, ID, snapshotPacket(ClockGlobalState.get(server).get()));
    }

    /** 配置变了：推给所有装了 LTE 的玩家（无关维度 —— 全局配置就一份）。 */
    public static void broadcast(final MinecraftServer server, final ClockGlobalConfig cfg) {
        if (server == null || cfg == null) {
            return;
        }
        final ClockGlobalConfig copy = cfg.copy();
        for (final ServerPlayerEntity player : PlayerLookup.all(server)) {
            if (ServerPlayNetworking.canSend(player, ID)) {
                ServerPlayNetworking.send(player, ID, snapshotPacket(copy));
            }
        }
    }

    // ---------------------------------------------------------------- 写

    /** 打包 C2S「保存」（客户端调）。 */
    public static PacketByteBuf savePacket(final ClockGlobalConfig cfg) {
        final PacketByteBuf buf = PacketByteBufs.create();
        buf.writeByte(KIND_SAVE);
        cfg.writeBuf(buf);
        return buf;
    }

    /** 打包 C2S「试听」（客户端调）。 */
    public static PacketByteBuf previewPacket() {
        final PacketByteBuf buf = PacketByteBufs.create();
        buf.writeByte(KIND_PREVIEW);
        return buf;
    }

    /**
     * 服务端：校验权限 + clamp + 落盘 + 广播。
     *
     * <p>顺序不能反：先判权限再碰存档 —— 不满足权限时连 clamp 都不做，配置保持原样。
     */
    private static void applySave(final MinecraftServer server, final ServerPlayerEntity player,
                                  final ClockGlobalConfig cfg) {
        if (server == null || player == null || cfg == null) {
            return;
        }
        if (!player.hasPermissionLevel(MIN_PERMISSION)) {
            player.sendMessage(Text.translatable("screen.mtrlte.clock.global.denied"), false);
            LteLog.LOGGER.info("[lte] clock global save refused for {}", player.getEntityName());
            return;
        }
        cfg.clamp();
        ClockGlobalState.get(server).set(cfg);
        broadcast(server, cfg);
        LteLog.LOGGER.debug("[lte] clock global config saved: enabled={} face=#{}",
                Boolean.valueOf(cfg.enabled), Integer.toHexString(cfg.config.faceColor));
    }

    /** 服务端：在玩家自己的位置播一下全局配置里的报时音。 */
    private static void applyPreview(final MinecraftServer server, final ServerPlayerEntity player) {
        if (server == null || player == null) {
            return;
        }
        final ClockGlobalConfig cfg = ClockGlobalState.get(server).get();
        player.getWorld().playSound(null, player.getBlockPos(),
                ClockBlockEntity.soundOf(cfg.config), SoundCategory.BLOCKS, 1.0F, 1.0F);
    }
}
