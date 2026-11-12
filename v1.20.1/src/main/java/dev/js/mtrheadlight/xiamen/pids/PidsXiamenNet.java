// 刷子界面用的两个包：S2C「打开界面」+ C2S「换广告」。
package dev.js.mtrheadlight.xiamen.pids;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import dev.js.mtrheadlight.LteLog;

/**
 * 厦门 PIDS 的界面通道：{@code mtrlte:pids_xiamen}。
 *
 * <h2>为什么要自己发两个包</h2>
 * <ul>
 *   <li><b>站台 / 页码</b>走 MTR 自己的 {@code PacketTrainDataGuiClient.sendPIDSConfigC2S}
 *       （服务端 {@code receivePIDSMessageC2S} 会写回方块实体并同步）—— 一行胶水都不用，
 *       而且和 MTR 原版 PIDS 界面完全同一个数据通路；</li>
 *   <li><b>广告序号</b>是本模组自己的字段（{@code xiamen_advertisement_index}），MTR 的包不认它，
 *       所以单独一个 C2S；</li>
 *   <li><b>打开界面</b>必须<b>服务端发起</b>：MTR 的 PIDS 数据（{@code platformIds} / {@code displayPage}）
 *       是服务端数据，客户端只有一份可能过期的副本。刷子按下时服务端先 {@code syncData()} 两半，
 *       再让这个玩家开界面 —— 界面一打开读到的就是刚同步的值。
 *       这和 MTR {@code BlockPIDSBaseHorizontal#onUse → openPIDSConfigScreenS2C} 的时序一致。</li>
 * </ul>
 *
 * <h2>包格式（第一字节 = 类型）</h2>
 * <pre>
 *   0 OPEN       (S2C)  dataPos:BlockPos, partnerPos:BlockPos
 *   1 AD         (C2S)  dataPos:BlockPos, adIndex:varInt
 *   2 CATEGORIES (C2S)  dataPos:BlockPos, count:varInt, id:long × count
 * </pre>
 *
 * <p>收包一律 try/catch：坏包只让这一次交互失效，不能把玩家踢下线。
 */
public final class PidsXiamenNet {

    /** 通道 id。 */
    public static final Identifier ID = new Identifier(PidsXiamenObjects.NAMESPACE, "pids_xiamen");

    private static final byte KIND_OPEN = 0;
    private static final byte KIND_AD = 1;
    private static final byte KIND_CATEGORIES = 2;

    private static boolean registeredServer;

    private PidsXiamenNet() {
    }

    // ---------------------------------------------------------------- 服务端

    /** {@code main} 入口（{@code LteServer}）调用一次。 */
    public static void registerServer() {
        if (registeredServer) {
            return;
        }
        registeredServer = true;
        try {
            ServerPlayNetworking.registerGlobalReceiver(ID, (server, player, handler, buf, sender) -> {
                try {
                    final byte kind = buf.readByte();
                    if (kind != KIND_AD && kind != KIND_CATEGORIES) {
                        return;
                    }
                    final BlockPos pos = buf.readBlockPos();
                    if (kind == KIND_AD) {
                        final int index = buf.readVarInt();
                        // 网络线程读包、主线程写方块实体。
                        server.execute(() -> applyAdIndex(player, pos, index));
                        return;
                    }
                    final int count = buf.readVarInt();
                    final long[] ids = new long[Math.max(0, Math.min(count, 256))];
                    for (int i = 0; i < ids.length; i++) {
                        ids[i] = buf.readLong();
                    }
                    server.execute(() -> applyCategories(player, pos, ids));
                } catch (Throwable t) {
                    LteLog.LOGGER.warn("[lte] xiamen pids: bad config packet", t);
                }
            });
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] xiamen pids: could not register the server receiver", t);
        }
    }

    /**
     * 写广告序号并同步给所有看得到的人。
     *
     * <p>坐标信任客户端、但<b>不</b>照抄：只认「这个坐标上真是我们的 PIDS」，
     * 否则一个坏包就能改掉别的方块实体。
     */
    private static void applyAdIndex(final ServerPlayerEntity player, final BlockPos pos, final int index) {
        final PidsXiamenBlockEntity pids = reachable(player, pos);
        if (pids == null) {
            return;
        }
        try {
            pids.setAdvertisementIndex(index);
            pids.syncData();
        } catch (Throwable t) {
            LteLog.LOGGER.debug("[lte] xiamen pids: could not apply the advertisement index", t);
        }
    }

    /** 写「勾了哪些广告分类」并同步（天津的 {@code PACKET_UPDATE_PIDS_ADS_CONFIG} 同一件事）。 */
    private static void applyCategories(final ServerPlayerEntity player, final BlockPos pos, final long[] ids) {
        final PidsXiamenBlockEntity pids = reachable(player, pos);
        if (pids == null) {
            return;
        }
        try {
            pids.setAdCategories(ids);
            pids.syncData();
        } catch (Throwable t) {
            LteLog.LOGGER.debug("[lte] xiamen pids: could not apply the advertisement categories", t);
        }
    }

    /**
     * 玩家伸手够得着的那块屏（{@code null} = 不认这个包）。
     *
     * <p>坐标信任客户端、但<b>不</b>照抄：只认「这个坐标上真是我们的 PIDS」，
     * 否则一个坏包就能改掉别的方块实体；距离校验 8 格（和 MTR 的交互距离同一量级）。
     */
    private static PidsXiamenBlockEntity reachable(final ServerPlayerEntity player, final BlockPos pos) {
        try {
            if (player == null || player.isRemoved()) {
                return null;
            }
            final World world = player.getServerWorld();
            if (world == null) {
                return null;
            }
            final BlockPos dataPos = PidsXiamenBlock.dataPos(world, pos);
            if (!(world.getBlockEntity(dataPos) instanceof PidsXiamenBlockEntity pids)) {
                return null;
            }
            if (player.getBlockPos().getSquaredDistance(dataPos) > 64.0D) {
                return null;
            }
            return pids;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 服务端：同步两半的方块实体，然后让这个玩家打开界面。 */
    public static void openScreen(final ServerPlayerEntity player, final World world, final BlockPos pos) {
        if (player == null || world == null) {
            return;
        }
        try {
            final BlockPos partnerPos = partner(world, pos);
            // 先把数据推给客户端：界面构造时读到的就是最新值（MTR 原版同样是先 sync 再开界面）。
            for (final BlockPos each : new BlockPos[]{pos, partnerPos}) {
                if (each != null && world.getBlockEntity(each) instanceof PidsXiamenBlockEntity pids) {
                    pids.syncData();
                }
            }
            final BlockPos dataPos = PidsXiamenBlock.dataPos(world, pos);
            if (!ServerPlayNetworking.canSend(player, ID)) {
                return;
            }
            final PacketByteBuf buf = PacketByteBufs.create();
            buf.writeByte(KIND_OPEN);
            buf.writeBlockPos(dataPos);
            buf.writeBlockPos(partnerPos == null ? dataPos : partnerPos);
            ServerPlayNetworking.send(player, ID, buf);
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] xiamen pids: could not open the config screen", t);
        }
    }

    /** 另一半（另一格屏）；只有一格时返回它自己。 */
    private static BlockPos partner(final World world, final BlockPos pos) {
        final net.minecraft.util.math.Direction facing = mtr.block.IBlock.getStatePropertySafe(
                world, pos, net.minecraft.block.HorizontalFacingBlock.FACING);
        if (facing == null) {
            return pos;
        }
        final BlockPos other = pos.offset(facing);
        return world.getBlockState(other).getBlock() instanceof PidsXiamenBlock ? other : pos;
    }

    // ---------------------------------------------------------------- 客户端

    /** 客户端：读一个 OPEN 包（{@link #openScreen} 写的那一套）。 */
    static OpenPacket readOpen(final PacketByteBuf buf) {
        return new OpenPacket(buf.readBlockPos(), buf.readBlockPos());
    }

    /** OPEN 包的内容。 */
    record OpenPacket(BlockPos dataPos, BlockPos partnerPos) {
    }

    /** 客户端 → 服务端：换广告。 */
    public static PacketByteBuf ad(final BlockPos pos, final int index) {
        final PacketByteBuf buf = PacketByteBufs.create();
        buf.writeByte(KIND_AD);
        buf.writeBlockPos(pos);
        buf.writeVarInt(Math.max(0, index));
        return buf;
    }

    /** 客户端 → 服务端：勾了哪些广告分类（空 = 回到默认分类）。 */
    public static PacketByteBuf categories(final BlockPos pos, final long[] ids) {
        final PacketByteBuf buf = PacketByteBufs.create();
        buf.writeByte(KIND_CATEGORIES);
        buf.writeBlockPos(pos);
        final int count = ids == null ? 0 : ids.length;
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            buf.writeLong(ids[i]);
        }
        return buf;
    }

    /** 读包类型（客户端先判类型再决定要不要读后面的字段）。 */
    static byte readKind(final PacketByteBuf buf) {
        return buf.readByte();
    }

    static boolean isOpen(final byte kind) {
        return kind == KIND_OPEN;
    }
}
