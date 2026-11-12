package dev.js.mtrheadlight.security;

import dev.js.mtrheadlight.LteLog;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 液体安检机的配置通道：{@code mtrlte:liquid_scanner_cfg}。
 *
 * <p>一个通道两个方向，第一字节区分：
 * <ul>
 *   <li>{@link #KIND_OPEN} <b>S2C</b> —— 刷子点方块后，服务端把「这台机器现在没收哪些液体」
 *       推给点它的那个玩家，客户端收到就开界面（{@code pos, configured, n, [id]×n}）；</li>
 *   <li>{@link #KIND_SAVE} <b>C2S</b> —— 界面点「保存」，把整份名单回写（{@code pos, n, [id]×n}）。</li>
 * </ul>
 *
 * <h2>为什么开界面要服务端点头</h2>
 * 配置是服务端权威（要落盘、要防作弊），客户端不能自己编一份名单出来当「当前配置」。
 * 所以刷子右键 → 服务端读方块实体 → 打包下发 → 客户端才开界面。
 * 单人游戏里「服务端」和「客户端」是同一个进程，这条链路照样成立，不需要额外分支。
 *
 * <h2>写回时的校验</h2>
 * {@link #applySave} 按顺序挡：玩家还在不在、区块加载了没、离方块多远（{@value #MAX_DISTANCE} 格）、
 * 方块实体还对不对、id 在 {@code Registries.POTION} 里存不存在。
 * 客户端发来的东西一条都不直接信 —— 名单本身只是 id，但「谁能改哪台机器」必须服务端说了算。
 *
 * <h2>旧客户端兼容</h2>
 * 通道是新增的，没装 LTE 的客户端 {@link ServerPlayNetworking#canSend} 为 {@code false}，
 * 刷子右键退回「什么都不发生」，方块本身、检测逻辑、灯都不受影响。
 */
public final class LiquidScannerNet {

    /** 通道 id。 */
    public static final Identifier ID = new Identifier("mtrlte", "liquid_scanner_cfg");

    /** S2C：开界面（pos + 当前配置）。 */
    public static final byte KIND_OPEN = 0;
    /** C2S：保存配置（pos + 整份名单）。 */
    private static final byte KIND_SAVE = 1;

    /** 玩家离方块多远之内还能改配置（格，平方比较）。 */
    private static final int MAX_DISTANCE = 64;
    /** 名单条数上限：正常游玩装再多药水模组也到不了，纯粹给坏包兜个底。 */
    private static final int MAX_ENTRIES = 512;

    private static boolean registered;

    private LiquidScannerNet() {
    }

    /** 服务端接收器，{@link SecurityObjects#register()} 里调一次（两端都会跑，客户端进程里注册也无害）。 */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        try {
            ServerPlayNetworking.registerGlobalReceiver(ID, (server, player, handler, buf, sender) -> {
                final BlockPos pos;
                final List<Identifier> ids = new ArrayList<>();
                try {
                    if (buf.readByte() != KIND_SAVE) {
                        return; // 客户端不该发别的类型，忽略。
                    }
                    pos = buf.readBlockPos();
                    final int n = Math.min(buf.readVarInt(), MAX_ENTRIES);
                    for (int i = 0; i < n; i++) {
                        final Identifier id = Identifier.tryParse(buf.readString());
                        if (id != null) {
                            ids.add(id);
                        }
                    }
                } catch (Throwable bad) {
                    return; // 坏包：当没收到，绝不半截写配置。
                }
                server.execute(() -> applySave(player, pos, ids));
            });
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] liquid scanner config channel unavailable", t);
        }
    }

    // ---------------------------------------------------------------- S2C

    /**
     * 把一个玩家的「开界面」请求的响应发出去。
     * <p>{@code entity} 为 {@code null}（理论上不该发生：方块实体由本方块自己创建）时按「没配过」下发，
     * 界面会自己按有害效果预选，玩家保存后又会有真正的方块实体。
     */
    public static void sendOpen(final ServerPlayerEntity player, final BlockPos pos,
                                final LiquidScannerBlockEntity entity) {
        if (player == null || !ServerPlayNetworking.canSend(player, ID)) {
            return;
        }
        final Set<Identifier> ids = entity == null ? new LinkedHashSet<>() : entity.confiscate();
        final PacketByteBuf buf = PacketByteBufs.create();
        buf.writeByte(KIND_OPEN);
        buf.writeBlockPos(pos);
        // 「配过没有」必须一起发：客户端据此决定是「回显名单」还是「按有害效果预选」。
        buf.writeBoolean(entity != null && entity.isConfigured());
        buf.writeVarInt(ids.size());
        for (final Identifier id : ids) {
            buf.writeString(id.toString());
        }
        ServerPlayNetworking.send(player, ID, buf);
    }

    // ---------------------------------------------------------------- 写

    /** 校验并落盘。{@code ids} 已经在网络线程里读成对象，这里只做纯服务端的事。 */
    private static void applySave(final ServerPlayerEntity player, final BlockPos pos,
                                  final List<Identifier> ids) {
        if (player == null || pos == null) {
            return;
        }
        final World world = player.getWorld();
        if (world == null || !world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
            return;
        }
        if (player.getBlockPos().getSquaredDistance(pos) > (double) MAX_DISTANCE * MAX_DISTANCE) {
            return;
        }
        if (!(world.getBlockState(pos).getBlock() instanceof LiquidScannerBlock)) {
            return;
        }
        LiquidScannerBlockEntity entity = world.getBlockEntity(pos) instanceof LiquidScannerBlockEntity be
                ? be : null;
        if (entity == null) {
            // 老存档：这台机器是这个版本之前放的，那时方块还不是 BlockEntityProvider，没有实体。
            // 第一次保存时补一个，免得「界面能开、保存了却什么都没发生」。
            entity = new LiquidScannerBlockEntity(pos, world.getBlockState(pos));
            world.addBlockEntity(entity);
        }
        // 只留注册表里真有的药水：模组被卸载后存档里的旧 id 会落到这里。
        final Set<Identifier> valid = new LinkedHashSet<>();
        for (final Identifier id : ids) {
            if (Registries.POTION.containsId(id)) {
                valid.add(id);
            }
        }
        entity.setConfiscate(valid);
        final int dropped = ids.size() - valid.size();
        if (dropped > 0) {
            player.sendMessage(Text.translatable("message.mtrlte.liquid_scanner.savedPartial",
                    valid.size(), dropped), false);
        } else {
            player.sendMessage(Text.translatable("message.mtrlte.liquid_scanner.saved", valid.size()), true);
        }
    }

    /** 打包 C2S「保存」（客户端调，见 {@code SecurityClient#sendSave}）。 */
    public static PacketByteBuf savePacket(final BlockPos pos, final Collection<Identifier> ids) {
        final PacketByteBuf buf = PacketByteBufs.create();
        buf.writeByte(KIND_SAVE);
        buf.writeBlockPos(pos);
        buf.writeVarInt(ids.size());
        for (final Identifier id : ids) {
            buf.writeString(id.toString());
        }
        return buf;
    }
}
