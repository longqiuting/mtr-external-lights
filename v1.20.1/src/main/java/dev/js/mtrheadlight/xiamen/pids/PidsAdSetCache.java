// 客户端镜像：全局广告集的只读缓存（渲染器每帧读它）。
package dev.js.mtrheadlight.xiamen.pids;

import java.util.List;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * 全局广告集在客户端的那一份镜像，照 {@code ClockGlobalCache} 的写法：只有一个
 * {@code volatile PidsAdSet}，渲染线程随读随用，写完即整体替换（不会读到半份）。
 *
 * <h2>什么时候更新</h2>
 * <ul>
 *   <li>进服 / 服务端每次保存成功（{@link PidsAdSetNet} 的 S2C 快照）；</li>
 *   <li>本机在仪表板里点了一下（{@link #save}：先乐观写本地 → 界面立刻有反馈，
 *       再发包；服务端拒绝时会推一份快照回来把它改回去）；</li>
 *   <li>断线清空（换服务器不会带着上一台的广告集）。</li>
 * </ul>
 *
 * <h2>为什么渲染不要直接读设置文件</h2>
 * <p>广告集是<b>服务端</b>的策略（所有玩家看到同一套），不是本机偏好；客户端只认快照。
 * 老的「单张全局覆盖」（{@link PidsXiamenSettings#adOverride()}）仍然是本机文件，
 * 两者在 {@link PidsXiamenSettings#applyAdOverride(List)} 里按「广告集优先」合并。
 */
public final class PidsAdSetCache {

    /** 还没收到快照时 = null，按「关着 + 空集」处理。 */
    private static volatile PidsAdSet current;

    /** {@link #activeAds()} 的缓存：同一份 set 只解析一次（渲染路径每帧每块屏都会问）。 */
    private static volatile PidsAdSet resolvedFor;
    private static volatile List<PidsXiamenAds.Ad> resolved;

    private PidsAdSetCache() {
    }

    /** 当前镜像的副本（没有就是一个关着的空集）。 */
    public static PidsAdSet get() {
        final PidsAdSet set = current;
        return set == null ? new PidsAdSet() : set.copy();
    }

    /** 收到服务端快照。 */
    public static void set(final PidsAdSet set) {
        current = set == null ? null : set.copy();
    }

    /** 断线 / 换服：清空。 */
    public static void clear() {
        current = null;
    }

    /**
     * 改一份并提交：先写本地（界面立刻看到），再发 C2S；服务端没有这条通道
     * （例如连的是没装本模组的服）就只留在本地，至少单机观感一致。
     */
    public static void save(final PidsAdSet set) {
        final PidsAdSet copy = set == null ? new PidsAdSet() : set.copy();
        current = copy;
        try {
            if (ClientPlayNetworking.canSend(PidsAdSetNet.ID)) {
                ClientPlayNetworking.send(PidsAdSetNet.ID, PidsAdSetNet.savePacket(copy));
            }
        } catch (final Throwable ignored) {
            // 发包失败：本地这份已经生效，下一次快照会覆盖。
        }
    }

    /**
     * 这一帧所有屏该播的那套广告；{@code null} = 没有生效的广告集（关着 / 空 / 图都不在了）。
     *
     * <p>渲染路径每帧每块屏都会问一次，所以按 set 实例缓存解析结果（{@link #set} 会整体换实例，
     * 界面拿到的永远是副本，不会有人就地改掉被缓存的那一份）。换资源包以后由
     * {@link #invalidate()} 清缓存。
     */
    public static List<PidsXiamenAds.Ad> activeAds() {
        final PidsAdSet set = current;
        if (set == null || !set.enabled()) {
            return null;
        }
        if (set == resolvedFor) {
            return resolved;
        }
        List<PidsXiamenAds.Ad> out;
        try {
            out = set.resolve();
        } catch (final Throwable ignored) {
            out = null;
        }
        resolvedFor = set;
        resolved = out;
        return out;
    }

    /** 资源重载：广告图 / 广告表都换了，解析结果要重来（{@code PidsXiamenClient} 的重载监听器调）。 */
    public static void invalidate() {
        resolvedFor = null;
        resolved = null;
    }
}
