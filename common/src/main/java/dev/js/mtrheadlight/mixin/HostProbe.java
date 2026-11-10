package dev.js.mtrheadlight.mixin;

/**
 * Detects which rail host mod (if any) is installed.
 *
 * <p>ANTE and NTE expose the same classes under different packages, and both are optional. Every
 * check is a class-presence probe wrapped in {@code Throwable} so a half-loaded or missing host can
 * never take the client down; the result is cached because it cannot change while the game runs.
 */
public final class HostProbe {
    /** ANTE (1.20.1 line). */
    private static final String ANTE_CHUNK =
            "fabric.cn.zbx1425.mtrsteamloco.render.rail.MeshBuildingRailChunk";
    /** NTE (1.18.2/1.19.x line). */
    private static final String NTE_CHUNK =
            "cn.zbx1425.mtrsteamloco.render.rail.MeshBuildingRailChunk";
    /** MTR 自己的客户端车型注册表（3.x 全系；同名分支如 YMTR 也在这个包里）。 */
    private static final String MTR_REGISTRY = "mtr.client.TrainClientRegistry";
    /** MTR 的服务端列车类（{@code TrainServer#simulateTrain} 是列车登记的注入点）。 */
    private static final String MTR_TRAIN_SERVER = "mtr.data.TrainServer";
    /** MTR 的 PIDS / 到站投影机渲染器（{@code getSchedules} 是「站外投影机」的注入目标）。 */
    private static final String MTR_PIDS_RENDERER = "mtr.render.RenderPIDS";
    /** MTR 的屏蔽门门扇渲染器（贴图 id 命名空间切换的注入目标）。 */
    private static final String MTR_PSD_DOOR_RENDERER = "mtr.render.RenderPSDAPGDoor";
    /** MTR 的站台 / 侧线基类（{@code isCloseToSavedRail} 是「到站数据范围」的注入目标）。 */
    private static final String MTR_SAVED_RAIL = "mtr.data.SavedRailBase";

    private static final boolean ANTE = present(ANTE_CHUNK);
    private static final boolean NTE = present(NTE_CHUNK);
    private static final boolean MTR = present(MTR_REGISTRY);
    private static final boolean MTR_SERVER = present(MTR_TRAIN_SERVER);
    private static final boolean MTR_PIDS = present(MTR_PIDS_RENDERER);
    private static final boolean MTR_PSD_DOOR = present(MTR_PSD_DOOR_RENDERER);
    private static final boolean MTR_SAVED_RAIL_BASE = present(MTR_SAVED_RAIL);

    private HostProbe() {
    }

    public static boolean ante() {
        return ANTE;
    }

    public static boolean nte() {
        return NTE;
    }

    /** {@code mtr.client.TrainClientRegistry} 是否存在（内置车型过滤的注入目标）。 */
    public static boolean mtrTrainRegistry() {
        return MTR;
    }

    /** {@code mtr.render.RenderPIDS} 是否存在（站外投影机顶渲染的注入目标）。 */
    public static boolean mtrPidsRenderer() {
        return MTR_PIDS;
    }

    /**
     * {@code mtr.render.RenderPSDAPGDoor} 是否存在 —— 屏蔽门门扇贴图命名空间切换的注入目标。
     *
     * <p>没有它就注入不了（门照样能用，只是贴图跟着资源包走），所以单独探测而不是复用
     * {@link #mtrTrainRegistry()}：两者的类可以分别存在 / 缺失。
     */
    public static boolean mtrPsdDoorRenderer() {
        return MTR_PSD_DOOR;
    }

    /**
     * {@code mtr.data.TrainServer} 是否存在 —— 服务端列车碰撞的注入目标。
     *
     * <p>用<b>服务端</b>类而不是 {@link #mtrTrainRegistry()} 的客户端注册表做探测：dedicated server
     * 上根本没有 {@code mtr.client.*}，拿它判断会把「装了 MTR 的服务端」误判成「没装 MTR」。
     */
    public static boolean mtrServerTrain() {
        return MTR_SERVER;
    }

    /**
     * {@code mtr.data.SavedRailBase} 是否存在 —— 「到站数据范围」放宽的注入目标。
     *
     * <p>用基类而不是 {@code mtr.data.Platform}：{@code isCloseToSavedRail} 声明在基类上，
     * 侧线（{@code Siding}）也会走到同一个方法（索引里没有它们的站台 id，自然是 no-op）。
     */
    public static boolean mtrSavedRail() {
        return MTR_SAVED_RAIL_BASE;
    }

    private static boolean present(final String className) {
        try {
            // Resource lookup, NOT Class.forName: loading a host class this early would freeze it
            // before every other mod's mixins get a chance to apply, which can break unrelated mods
            // (EntityCulling's block-entity interface injection is exactly such a victim).
            final String path = className.replace('.', '/') + ".class";
            final ClassLoader own = HostProbe.class.getClassLoader();
            if (own != null && own.getResource(path) != null) {
                return true;
            }
            final ClassLoader ctx = Thread.currentThread().getContextClassLoader();
            return ctx != null && ctx.getResource(path) != null;
        } catch (Throwable t) {
            // Missing host, or a loader that cannot see it: treat as absent.
            return false;
        }
    }
}
