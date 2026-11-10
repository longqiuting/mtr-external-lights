package dev.js.mtrheadlight.signal;

import mtr.data.Rail;
import mtr.data.RailwayData;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 绑定表的同步通道：{@code mtrlte:signal_link}（S2C，只下发，客户端从不回写）。
 *
 * <p>为什么服务端权威：绑定要落盘、要校验节点上真有一段轨道，客户端说的都不算。客户端只拿一份
 * 只读镜像给 {@code RenderSignalBaseMixin} 用。
 *
 * <p>三种包（第一字节）：
 * <ul>
 *   <li>{@code 0} SYNC —— 进服时整维度下发：dim, n, [signal, node]×n；</li>
 *   <li>{@code 1} PUT —— 新增/覆盖一条：dim, signal, node；</li>
 *   <li>{@code 2} REMOVE —— 解绑：dim, signal。</li>
 *   <li>{@code 3} LAMP_SYNC —— 进服时整维度下发「灯 → 灯」：dim, n, [lamp, source]×n；</li>
 *   <li>{@code 4} LAMP_PUT —— 灯 → 灯 新增/覆盖：dim, lamp, source；</li>
 *   <li>{@code 5} LAMP_REMOVE —— 灯 → 灯 解绑：dim, lamp。</li>
 * </ul>
 *
 * <p>「灯 → 灯」故意用新的包类型而不是往 {@code SYNC} 尾巴上追加：旧版客户端收到未知类型会直接
 * 忽略（{@code default -> {}}），整张节点表照常同步，不会因为多出来的几个字节把整包读崩。
 */
public final class SignalLinkNet {

    public static final Identifier ID = new Identifier("mtrlte", "signal_link");

    private static final byte KIND_SYNC = 0;
    private static final byte KIND_PUT = 1;
    private static final byte KIND_REMOVE = 2;
    private static final byte KIND_LAMP_SYNC = 3;
    private static final byte KIND_LAMP_PUT = 4;
    private static final byte KIND_LAMP_REMOVE = 5;

    /** {@code RailwayData#getInstance(World)}，见 {@link #railwayData(World)}。 */
    private static Method getInstance;
    private static boolean getInstanceProbed;

    private SignalLinkNet() {
    }

    // ---------------------------------------------------------------- 写

    public static PacketByteBuf sync(final String dim, final Map<BlockPos, BlockPos> links) {
        final PacketByteBuf buf = PacketByteBufs.create();
        buf.writeByte(KIND_SYNC);
        buf.writeString(dim);
        buf.writeVarInt(links.size());
        for (final Map.Entry<BlockPos, BlockPos> entry : links.entrySet()) {
            buf.writeBlockPos(entry.getKey());
            buf.writeBlockPos(entry.getValue());
        }
        return buf;
    }

    public static PacketByteBuf put(final String dim, final BlockPos signal, final BlockPos node) {
        final PacketByteBuf buf = PacketByteBufs.create();
        buf.writeByte(KIND_PUT);
        buf.writeString(dim);
        buf.writeBlockPos(signal);
        buf.writeBlockPos(node);
        return buf;
    }

    public static PacketByteBuf remove(final String dim, final BlockPos signal) {
        final PacketByteBuf buf = PacketByteBufs.create();
        buf.writeByte(KIND_REMOVE);
        buf.writeString(dim);
        buf.writeBlockPos(signal);
        return buf;
    }

    public static PacketByteBuf lampSync(final String dim, final Map<BlockPos, BlockPos> links) {
        final PacketByteBuf buf = PacketByteBufs.create();
        buf.writeByte(KIND_LAMP_SYNC);
        buf.writeString(dim);
        buf.writeVarInt(links.size());
        for (final Map.Entry<BlockPos, BlockPos> entry : links.entrySet()) {
            buf.writeBlockPos(entry.getKey());
            buf.writeBlockPos(entry.getValue());
        }
        return buf;
    }

    public static PacketByteBuf lampPut(final String dim, final BlockPos lamp, final BlockPos source) {
        final PacketByteBuf buf = PacketByteBufs.create();
        buf.writeByte(KIND_LAMP_PUT);
        buf.writeString(dim);
        buf.writeBlockPos(lamp);
        buf.writeBlockPos(source);
        return buf;
    }

    public static PacketByteBuf lampRemove(final String dim, final BlockPos lamp) {
        final PacketByteBuf buf = PacketByteBufs.create();
        buf.writeByte(KIND_LAMP_REMOVE);
        buf.writeString(dim);
        buf.writeBlockPos(lamp);
        return buf;
    }

    // ---------------------------------------------------------------- 发

    /** 进服：整维度下发一次（两张表各一包）。 */
    public static void sendAll(final ServerPlayerEntity player, final String dim,
                               final Map<BlockPos, BlockPos> links,
                               final Map<BlockPos, BlockPos> lampLinks) {
        if (player == null || !ServerPlayNetworking.canSend(player, ID)) {
            return;
        }
        ServerPlayNetworking.send(player, ID, sync(dim, links));
        ServerPlayNetworking.send(player, ID, lampSync(dim, lampLinks));
    }

    /**
     * 一条绑定变了：发给所有装了 LTE 的玩家。
     *
     * <p>故意不按维度筛：一条 PUT 只有二十几个字节，玩家跨维度传送时不用再补一轮全量同步，
     * 而客户端本来就按维度分桶存。
     */
    public static void broadcastPut(final MinecraftServer server, final String dim,
                                    final BlockPos signal, final BlockPos node) {
        if (server == null) {
            return;
        }
        for (final ServerPlayerEntity player : PlayerLookup.all(server)) {
            if (ServerPlayNetworking.canSend(player, ID)) {
                ServerPlayNetworking.send(player, ID, put(dim, signal, node));
            }
        }
    }

    public static void broadcastRemove(final MinecraftServer server, final String dim, final BlockPos signal) {
        if (server == null) {
            return;
        }
        for (final ServerPlayerEntity player : PlayerLookup.all(server)) {
            if (ServerPlayNetworking.canSend(player, ID)) {
                ServerPlayNetworking.send(player, ID, remove(dim, signal));
            }
        }
    }

    public static void broadcastLampPut(final MinecraftServer server, final String dim,
                                        final BlockPos lamp, final BlockPos source) {
        if (server == null) {
            return;
        }
        for (final ServerPlayerEntity player : PlayerLookup.all(server)) {
            if (ServerPlayNetworking.canSend(player, ID)) {
                ServerPlayNetworking.send(player, ID, lampPut(dim, lamp, source));
            }
        }
    }

    public static void broadcastLampRemove(final MinecraftServer server, final String dim,
                                           final BlockPos lamp) {
        if (server == null) {
            return;
        }
        for (final ServerPlayerEntity player : PlayerLookup.all(server)) {
            if (ServerPlayNetworking.canSend(player, ID)) {
                ServerPlayNetworking.send(player, ID, lampRemove(dim, lamp));
            }
        }
    }

    // ---------------------------------------------------------------- MTR 数据

    /** 维度 id（与客户端 {@code World#getRegistryKey().getValue()} 一致）。 */
    public static String dimensionOf(final World world) {
        return world.getRegistryKey().getValue().toString();
    }

    /**
     * {@code RailwayData#getInstance(World)}。
     *
     * <p><b>为什么反射</b>：MTR 的 jar 没有 remap，这个方法的形参在它字节码里是 intermediary 名
     * （{@code class_1937}），而本工程用 named 映射编译，直接调用编译不过（同
     * {@code ServerCollision#ridingAny} 的处理方式）。运行期两者是同一个类，
     * {@code getMethod("getInstance", World.class)} 找得到；找不到（MTR 换版本）就当没有数据。
     */
    private static RailwayData railwayData(final World world) {
        if (!getInstanceProbed) {
            getInstanceProbed = true;
            try {
                getInstance = RailwayData.class.getMethod("getInstance", World.class);
            } catch (Throwable t) {
                getInstance = null;
            }
        }
        if (getInstance == null) {
            return null;
        }
        try {
            final Object data = getInstance.invoke(null, world);
            return data instanceof RailwayData ? (RailwayData) data : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 该节点上接了几段轨道（没有轨道的孤立节点绑了也没意义）。 */
    public static boolean hasRail(final World world, final BlockPos node) {
        return !railsAt(world, node).isEmpty();
    }

    /**
     * 节点上所有「以它为起点」的轨道。
     *
     * <p>{@code getRailsMap()} 的键是节点方块坐标（也是 {@code ClientData.RAILS} 的键），
     * 值是「另一端节点 → Rail」。{@code Rail#facingStart} 就是节点这一侧的出线方向。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static List<Rail> railsAt(final World world, final BlockPos node) {
        try {
            final RailwayData data = railwayData(world);
            if (data == null) {
                return Collections.emptyList();
            }
            final Map rails = data.getRailsMap();
            if (rails == null) {
                return Collections.emptyList();
            }
            final Map atNode = (Map) rails.get(node);
            if (atNode == null || atNode.isEmpty()) {
                return Collections.emptyList();
            }
            final List<Rail> out = new ArrayList<>(atNode.size());
            for (final Object value : atNode.values()) {
                if (value instanceof Rail) {
                    out.add((Rail) value);
                }
            }
            return out;
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }
}
