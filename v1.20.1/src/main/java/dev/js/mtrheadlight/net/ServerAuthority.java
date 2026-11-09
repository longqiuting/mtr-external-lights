package dev.js.mtrheadlight.net;

import dev.js.mtrheadlight.LteLog;

/**
 * 「谁说了算」的全局判断 —— <b>两端共用，不引用客户端类</b>。
 *
 * <p>碰撞后果（扣血 / 撞飞 / 死亡消息）只能结算一次。约定：<b>服务端有本模组时，服务端权威，
 * 客户端只做表现</b>（推出、车顶载人、受击动画都还是客户端的，因为它们决定手感）。
 *
 * <pre>
 * localServer    : 本进程里 {@code main} 入口跑起来了（单人 / 局域网主机 / dedicated server）
 * remoteServer   : 连服后服务端回了 {@code mtrlte:hello}（对端装了 LTE）
 * authoritative()= localServer || remoteServer
 * </pre>
 *
 * <p>纯客户端连一个没装 LTE 的服务器：两个标志都是 false，客户端照老行为下「表现性」伤害，
 * 也就是和以前完全一样 —— 这也是「不装也能正常连服」的定义。
 *
 * <p>字段是 {@code volatile}：{@code localServer} 由初始化线程写、tick 线程读；{@code remoteServer}
 * 由网络线程写、tick 线程读。
 */
public final class ServerAuthority {

    /** 本进程就是 dedicated server（没有客户端；伤害只有服务端在算）。 */
    private static volatile boolean dedicated;

    /** 对端服务端回了 hello，且它自己说「伤害由我结算」。 */
    private static volatile boolean remoteAuthoritative;

    /** 服务端的伤害开关（只用于和本地配置比对后打 warn）。 */
    private static volatile boolean remoteDamage;

    /** 服务端的高速档分界（km/h），只用于 warn。 */
    private static volatile int remoteHighKmh;

    /** 只 warn 一次：连一次服只该有一条不一致提示。 */
    private static boolean warned;

    private ServerAuthority() {
    }

    public static void markDedicated() {
        dedicated = true;
    }

    public static boolean dedicated() {
        return dedicated;
    }

    /** 对端服务端在算伤害（收到过握手，且它说自己是权威）。 */
    public static boolean remoteAuthoritative() {
        return remoteAuthoritative;
    }

    /** 收到服务端应答（{@code client.execute} 里调用）。 */
    public static void acceptRemote(final LteChannel.ServerHello hello) {
        if (hello == null || hello.version != LteChannel.VERSION) {
            return;
        }
        remoteDamage = hello.damage;
        remoteHighKmh = hello.highDamageMaxKmh;
        remoteAuthoritative = hello.damage;
    }

    /** 断开连接 / 换服：忘掉上一个服务端。 */
    public static void clearRemote() {
        remoteAuthoritative = false;
        remoteDamage = false;
        remoteHighKmh = 0;
        warned = false;
    }

    /** 客户端要不要放弃自己下伤害（服务端在算就是 true）。 */
    public static boolean authoritative() {
        return dedicated || remoteAuthoritative;
    }

    /** 服务端是否开着撞击伤害（没握手时按 true 处理，不影响判断）。 */
    public static boolean remoteDamageEnabled() {
        return remoteDamage;
    }

    /**
     * 客户端配置与服务端不一致时记一行 warn。只对<b>伤害相关</b>开关做（其余字段本来就允许
     * 各调各的：光、雨、渲染都是纯客户端口味）。
     *
     * @param localDamage 本地 {@code trainCollisionDamage}
     * @param localHighKmh 本地 {@code trainCollisionHighDamageMaxKmh}
     */
    public static void warnIfMismatch(final boolean localDamage, final int localHighKmh) {
        if (warned || !remoteAuthoritative) {
            return;
        }
        warned = true;
        if (localDamage != remoteDamage) {
            LteLog.LOGGER.warn(
                    "[lte] server-side collision is authoritative: damage={} on the server, "
                            + "damage={} in this client's config - using the server's setting",
                    Boolean.valueOf(remoteDamage), Boolean.valueOf(localDamage));
        } else if (localHighKmh != remoteHighKmh && remoteHighKmh > 0) {
            LteLog.LOGGER.warn(
                    "[lte] collision damage tier boundary differs: high={}km/h on the server, "
                            + "high={}km/h in this client's config - the server decides",
                    Integer.valueOf(remoteHighKmh), Integer.valueOf(localHighKmh));
        }
    }
}
