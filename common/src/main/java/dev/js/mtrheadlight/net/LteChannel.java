package dev.js.mtrheadlight.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

/**
 * 客户端 ↔ 服务端的一根通道：{@code mtrlte:hello}。<b>纯数据，无环境依赖</b>。
 *
 * <h2>为什么需要它</h2>
 * 列车碰撞的后果（扣血 / 撞飞 / 死亡消息）必须只结算一次。规则是「<b>服务端有本模组时，服务端权威，
 * 客户端只做表现</b>」：
 * <ul>
 *   <li>单人（整合服务器）：{@code main} 入口在同一进程里跑起来了，客户端直接知道，不需要包；</li>
 *   <li>多人：客户端不知道对端装没装，所以连服后发一次 {@code hello}，装了 LTE 的服务端回
 *       {@link ServerHello}（含伤害相关开关）。收到之前客户端按老行为（本地只做受击表现），
 *       收到之后不再自己下伤害。</li>
 * </ul>
 *
 * <p>服务端没装 LTE 时没人回这个包，客户端一切照旧 —— 纯客户端模式完全不受影响。
 *
 * <p>协议：请求 = {@code version:int}；应答 = {@code version:int, trainCollision:bool,
 * trainCollisionDamage:bool, trainCollisionMobs:bool, highDamageMaxKmh:int,
 * pushOnlyMaxKmh:int, authoritativeDamage:bool}。字段只增不改，{@code version} 不匹配时客户端
 * 忽略应答（当作没有握手，回到纯客户端模式）。
 */
public final class LteChannel {

    /** 通道 id。 */
    public static final Identifier ID = new Identifier("mtrlte", "hello");

    /** 协议版本。加字段就 +1，旧客户端会忽略新应答。 */
    public static final int VERSION = 1;

    private LteChannel() {
    }

    /** 客户端 → 服务端：只报版本。 */
    public static void writeHello(final PacketByteBuf buf) {
        buf.writeInt(VERSION);
    }

    /** 读 {@link #writeHello} 写出的内容；读不到就返回 -1（旧 / 异常包）。 */
    public static int readHelloVersion(final PacketByteBuf buf) {
        try {
            return buf.readableBytes() >= Integer.BYTES ? buf.readInt() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 服务端 → 客户端：服务端是否在算、按什么算。
     *
     * <p>{@code authoritativeDamage} 是<b>最终结论</b>（服务端既装了模组、又开着列车碰撞伤害），
     * 客户端只据此决定「要不要自己下伤害」；其余字段只用于「和本地配置不一致时打一行 warn」。
     */
    public static final class ServerHello {
        public final int version;
        public final boolean trainCollision;
        public final boolean damage;
        public final boolean mobs;
        public final int highDamageMaxKmh;
        public final int pushOnlyMaxKmh;

        public ServerHello(final int version, final boolean trainCollision, final boolean damage,
                           final boolean mobs, final int highDamageMaxKmh,
                           final int pushOnlyMaxKmh) {
            this.version = version;
            this.trainCollision = trainCollision;
            this.damage = damage;
            this.mobs = mobs;
            this.highDamageMaxKmh = highDamageMaxKmh;
            this.pushOnlyMaxKmh = pushOnlyMaxKmh;
        }

        public void write(final PacketByteBuf buf) {
            buf.writeInt(version);
            buf.writeBoolean(trainCollision);
            buf.writeBoolean(damage);
            buf.writeBoolean(mobs);
            buf.writeInt(highDamageMaxKmh);
            buf.writeInt(pushOnlyMaxKmh);
        }

        /** 读不出来就返回 {@code null}（坏包 / 版本不同的服务端）。 */
        public static ServerHello read(final PacketByteBuf buf) {
            try {
                if (buf.readableBytes() < Integer.BYTES * 3 + 3) {
                    return null;
                }
                return new ServerHello(buf.readInt(), buf.readBoolean(), buf.readBoolean(),
                        buf.readBoolean(), buf.readInt(), buf.readInt());
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
