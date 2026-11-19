// 时钟配置通道：刷子开界面（S2C）/ 保存配置（C2S）/ 试听报时音（C2S）。
package dev.js.mtrheadlight.clock;

import dev.js.mtrheadlight.LteLog;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 时钟的配置通道 {@code mtrlte:clock_cfg}，一个通道四个包袱，第一字节区分：
 *
 * <ul>
 *   <li>{@link #KIND_OPEN} <b>S2C</b> —— 服务端把这块钟的配置整份推给点它的玩家，
 *       客户端收到就开 {@link ClockConfigScreen}；</li>
 *   <li>{@link #KIND_SAVE} <b>C2S</b> —— 界面点「完成」，把整份配置写回来；</li>
 *   <li>{@link #KIND_PREVIEW} <b>C2S</b> —— 「试听」按钮，服务端在这块钟的位置播一下报时音；</li>
 *   <li>{@link #KIND_SYNC} <b>S2C</b> —— 配置改完了：把新配置推给「看得见这块钟」的客户端，
 *       客户端直接写进它自己那份方块实体（{@link ClockBlockEntity#applyClientConfig}）。</li>
 * </ul>
 *
 * <h2>为什么开界面要服务端点头</h2>
 * <p>配置是服务端权威（要落盘、要防作弊），客户端不能自己编一份出来当「当前配置」。
 *
 * <h2>{@link #KIND_SYNC} 为什么单独存在</h2>
 * <p>原版路径（{@code world.updateListeners} → {@code BlockEntityUpdateS2CPacket}）也修好了
 * （见 {@link ClockBlockEntity#toUpdatePacket()}），但那条路要等区块 flush、还依赖「这一格排进了
 * block update 队列」。改配置是玩家点了「完成」就该立刻看见的事，所以再直接推一份：不等、不受
 * flush 影响，客户端拿到就是新样子。
 *
 * <h2>写回时校验什么（按顺序）</h2>
 * <ol>
 *   <li>玩家 / 坐标不为空；</li>
 *   <li>区块已加载；</li>
 *   <li>离方块不超过 {@value #MAX_DISTANCE} 格（刷子够得着的距离，防远处改别人家的钟）；</li>
 *   <li>那一格真的还是 {@link ClockBlock}，方块实体真的是 {@link ClockBlockEntity}；</li>
 *   <li>{@link ClockConfig#clamp()}：长度 0.1–50、颜色 24 位、音效 id 形状、偏移 ≤ 一天。</li>
 * </ol>
 *
 * <p>没装 LTE 的客户端 {@code canSend} 为 false，刷子右键什么都不发生 —— 时钟本身照常走。
 */
public final class ClockNet {

    /** 通道 id。 */
    public static final Identifier ID = new Identifier("mtrlte", "clock_cfg");

    /** S2C：开界面（pos + 整份配置）。 */
    public static final byte KIND_OPEN = 0;
    /** C2S：保存配置（pos + 整份配置）。 */
    public static final byte KIND_SAVE = 1;
    /** C2S：试听报时音（pos）。 */
    public static final byte KIND_PREVIEW = 2;
    /** S2C：配置变更（pos + 整份配置）—— 客户端直接写进自己那份方块实体。 */
    public static final byte KIND_SYNC = 3;

    /** 玩家离方块多远之内还能改配置（格，平方比较）。 */
    private static final int MAX_DISTANCE = 64;

    private static boolean registered;

    private ClockNet() {
    }

    /** 服务端接收器，{@link ClockObjects#register()} 之外由 {@code LteServer} 的模块再调一次也无害。 */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        try {
            ServerPlayNetworking.registerGlobalReceiver(ID, (server, player, handler, buf, sender) -> {
                final byte kind;
                final BlockPos pos;
                final ClockConfig cfg;
                try {
                    kind = buf.readByte();
                    pos = buf.readBlockPos();
                    cfg = kind == KIND_SAVE ? ClockConfig.readBuf(buf) : null;
                } catch (Throwable bad) {
                    return; // 坏包：当没收到，绝不半截写配置。
                }
                server.execute(() -> {
                    if (kind == KIND_SAVE) {
                        applySave(player, pos, cfg);
                    } else if (kind == KIND_PREVIEW) {
                        applyPreview(player, pos);
                    }
                });
            });
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] clock config channel unavailable", t);
        }
    }

    // ---------------------------------------------------------------- S2C

    /** 把这块钟的配置推给玩家（{@code entity == null} 时下发一份默认配置）。 */
    public static void sendOpen(final ServerPlayerEntity player, final BlockPos pos,
                                final ClockBlockEntity entity) {
        if (player == null || !ServerPlayNetworking.canSend(player, ID)) {
            return;
        }
        final ClockConfig cfg = entity == null ? new ClockConfig() : entity.config();
        final PacketByteBuf buf = PacketByteBufs.create();
        buf.writeByte(KIND_OPEN);
        buf.writeBlockPos(pos);
        cfg.writeBuf(buf);
        ServerPlayNetworking.send(player, ID, buf);
    }

    /**
     * 配置改完：把新配置推给看得见这块钟的客户端（服务端调，{@link ClockBlockEntity#setConfig} 里）。
     *
     * <p>{@code world} 必须是服务端世界；客户端调到就直接返回（否则会自己给自己发包）。
     */
    public static void sendConfig(final World world, final BlockPos pos, final ClockConfig cfg) {
        if (!(world instanceof ServerWorld serverWorld) || pos == null || cfg == null) {
            return;
        }
        // tracking() 覆盖「正在跟这一格」的所有玩家 —— 改配置的人自己一定在里面。
        for (final ServerPlayerEntity player : PlayerLookup.tracking(serverWorld, pos)) {
            if (ServerPlayNetworking.canSend(player, ID)) {
                ServerPlayNetworking.send(player, ID, syncPacket(pos, cfg));
            }
        }
    }

    /** 打包 S2C「配置变更」（pos + 整份配置）。 */
    public static PacketByteBuf syncPacket(final BlockPos pos, final ClockConfig cfg) {
        final PacketByteBuf buf = PacketByteBufs.create();
        buf.writeByte(KIND_SYNC);
        buf.writeBlockPos(pos);
        cfg.writeBuf(buf);
        return buf;
    }

    // ---------------------------------------------------------------- 写

    /** 打包 C2S「保存」（客户端调）。 */
    public static PacketByteBuf savePacket(final BlockPos pos, final ClockConfig cfg) {
        final PacketByteBuf buf = PacketByteBufs.create();
        buf.writeByte(KIND_SAVE);
        buf.writeBlockPos(pos);
        cfg.writeBuf(buf);
        return buf;
    }

    /** 打包 C2S「试听」（客户端调）。 */
    public static PacketByteBuf previewPacket(final BlockPos pos) {
        final PacketByteBuf buf = PacketByteBufs.create();
        buf.writeByte(KIND_PREVIEW);
        buf.writeBlockPos(pos);
        return buf;
    }

    /** 服务端：校验 + 落盘。 */
    private static void applySave(final ServerPlayerEntity player, final BlockPos pos,
                                  final ClockConfig cfg) {
        final ClockBlockEntity entity = resolve(player, pos);
        if (entity == null || cfg == null) {
            return;
        }
        cfg.clamp();
        // 同一个 BE 实例：resolve() 拿的就是 world.getBlockEntity(pos)（服务端那一份），
        // setConfig 再负责「落盘 + 让客户端也拿到」。
        entity.setConfig(cfg);
        LteLog.LOGGER.debug("[lte] clock cfg saved at {}: face=#{} hourLen={} tick={}",
                pos, Integer.toHexString(cfg.faceColor), Float.valueOf(cfg.hourLength),
                cfg.tickStyle.asString());
    }

    /** 服务端：在这块钟的位置播一下报时音（试听）。 */
    private static void applyPreview(final ServerPlayerEntity player, final BlockPos pos) {
        final ClockBlockEntity entity = resolve(player, pos);
        if (entity == null) {
            return;
        }
        entity.playPreview();
    }

    /** 公共校验：距离 / 区块 / 方块 / 方块实体，任何一条不过都返回 {@code null}。 */
    private static ClockBlockEntity resolve(final ServerPlayerEntity player, final BlockPos pos) {
        if (player == null || pos == null) {
            return null;
        }
        final World world = player.getWorld();
        if (world == null || !world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
            return null;
        }
        if (player.getBlockPos().getSquaredDistance(pos) > (double) MAX_DISTANCE * MAX_DISTANCE) {
            return null;
        }
        if (!(world.getBlockState(pos).getBlock() instanceof ClockBlock)) {
            return null;
        }
        if (world.getBlockEntity(pos) instanceof ClockBlockEntity be) {
            return be;
        }
        // 理论上不该走到这里（方块自己创建方块实体）；真没有就补一个，
        // 免得「界面能开、保存了却什么都没发生」。
        final ClockBlockEntity created = new ClockBlockEntity(pos, world.getBlockState(pos));
        world.addBlockEntity(created);
        return created;
    }
}
