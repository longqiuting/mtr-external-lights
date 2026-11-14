// 挂墙发车倒数器的读数方向：四个状态，游戏内可翻，落盘保存。
package dev.js.mtrheadlight.xiamen.dep;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.js.mtrheadlight.LteLog;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.util.math.MatrixStack;

/**
 * 挂墙发车倒数器的<b>读数方向开关</b>（运行期可改、可落盘、改完下一帧就生效）。
 *
 * <h2>为什么需要它</h2>
 * <p>这块牌子的数字是<b>几何</b>画出来的（{@link DepOnWallDigits}），框架里 +X 往哪边走，
 * 数字就往哪边读 —— 没有 {@code TextRenderer} 那层「GUI 文字自带翻转」。方向一旦推错，
 * 屏幕上就是 {@code 91:00}；而前三次返工全是「离线复刻矩阵 → 改一个符号 → 重编发版 →
 * 玩家实机还是反的」这个循环，每轮要等一次构建 + 一次进游戏。
 *
 * <p>所以方向从「编译期常量」变成了<b>运行期状态</b>：玩家在游戏里执行
 * {@code /mtr_lte dep}（切下一个）或 {@code /mtr_lte dep mirror}（点名）就能现场翻，
 * 屏幕立刻变，不用重编、不用重进世界。确认哪一档是对的之后，把它写进
 * {@link #DEFAULT} 即可（或者就让它留在 {@code config/mtrlte-dep-orient.json} 里）。
 *
 * <h2>四个状态</h2>
 * <p>屏幕原点在<b>正中</b>（见 {@link DepOnWallLayout}），所以下面每一种都只是绕原点的一次
 * 缩放，<b>屏幕占的面积一个像素都不动</b>；而且它只作用于<b>数字层</b>那一层矩阵，
 * 底图永远不翻（底图必须和模型元素 2 的贴图严丝合缝）：
 * <pre>
 *   NORMAL     scale( 1,  1, 1)   基准：+X = 玩家右手、+Y = 向下
 *   MIRROR     scale(-1,  1, 1)   左右翻（读数方向左右对调）
 *   FLIP_Y     scale( 1, -1, 1)   上下翻（数字上下颠倒、左右不变）
 *   ROTATE     scale(-1, -1, 1)   翻 180°（上下左右全反）
 * </pre>
 * <p>之所以给出「上下」和「180°」两档：如果实机看到的是<b>上下颠倒</b>，任何左右翻都救不了它
 * （左右翻之后仍然是上下颠倒，用户看到的还是「反的」）—— 这也是前几次「加了镜像反而没差别」
 * 的一个可能解释。四档一起给，玩家按一次就能落到对的组合上。
 *
 * <h2>落盘</h2>
 * <p>写在 {@code config/mtrlte-dep-orient.json}：{@code {"mode":"mirror"}}。
 * 读不到 / 读坏了 / 值不认识 → 回到 {@link #DEFAULT}，并在日志里留一行，不抛异常
 * （这是渲染路径上的东西，绝不能因为一个配置文件让方块不画）。
 */
public final class DepOnWallOrient {

    /** 配置文件名（在 {@code config/} 下）。 */
    private static final String FILE_NAME = "mtrlte-dep-orient.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * 默认档位。
     *
     * <p>{@link Mode#MIRROR}：2026-11-12 实机反馈 —— Y-180 那版基向量（{@code NORMAL}）的读数方向
     * 「终于是正的了，但左右镜像」，所以默认再左右翻一次。{@code NORMAL} 保留，只当参照。
     */
    public static final Mode DEFAULT = Mode.MIRROR;

    /**
     * 配置文件的版本号。
     *
     * <p>改动 {@link #DEFAULT} 时把它 +1：老配置里存的是上一版的结论（比如上一版默认
     * {@code normal}，玩家在游戏里存过一次 {@code normal}），不认版本的话新默认永远不生效 ——
     * 这正是「改了默认、实机却没变」的坑。版本对不上就当没有配置文件。
     */
    private static final int CONFIG_VERSION = 2;

    /** 四个档位，{@code scaleX / scaleY} 就是绕屏幕原点的那两个符号。 */
    public enum Mode {
        NORMAL("normal", "基准（+X = 玩家右手）", 1, 1),
        MIRROR("mirror", "左右翻", -1, 1),
        FLIP_Y("flip", "上下翻", 1, -1),
        ROTATE("rotate", "翻 180°（上下左右都翻）", -1, -1);

        private final String id;
        private final String label;
        private final float scaleX;
        private final float scaleY;

        Mode(final String id, final String label, final int scaleX, final int scaleY) {
            this.id = id;
            this.label = label;
            this.scaleX = scaleX;
            this.scaleY = scaleY;
        }

        /** 指令里用的名字（{@code /mtr_lte dep mirror}）。 */
        public String id() {
            return id;
        }

        /** 聊天栏里的中文说明。 */
        public String label() {
            return label;
        }

        /** 应用到矩阵栈：只在需要翻转时才乘一个 scale，省掉恒等变换。 */
        void apply(final MatrixStack matrices) {
            if (scaleX < 0 || scaleY < 0) {
                matrices.scale(scaleX, scaleY, 1.0F);
            }
        }

        /** 切下一个（{@code NORMAL → MIRROR → FLIP_Y → ROTATE → NORMAL}）。 */
        public Mode next() {
            final Mode[] all = values();
            return all[(ordinal() + 1) % all.length];
        }

        /** 名字 → 档位；不认识的返回 {@code null}（调用方决定是报错还是回落）。 */
        public static Mode byId(final String raw) {
            if (raw == null) {
                return null;
            }
            final String key = raw.trim().toLowerCase(java.util.Locale.ROOT);
            for (final Mode mode : values()) {
                if (mode.id.equals(key) || mode.name().toLowerCase(java.util.Locale.ROOT).equals(key)) {
                    return mode;
                }
            }
            // 顺手认几个常见写法，省得玩家照着日志里的中文说明瞎猜。
            return switch (key) {
                case "none", "off", "base", "normal", "default" -> NORMAL;
                case "x", "h", "horizontal", "mirror_x" -> MIRROR;
                case "y", "v", "vertical", "flip_y", "flipy" -> FLIP_Y;
                case "180", "rot180", "rotate180", "both" -> ROTATE;
                default -> null;
            };
        }
    }

    /** 当前档位；{@code null} = 还没从磁盘读过。 */
    private static Mode current;

    private DepOnWallOrient() {
    }

    /** 当前档位（第一次访问时读盘）。 */
    public static Mode mode() {
        if (current == null) {
            current = read();
        }
        return current;
    }

    /** {@code true} = 至少要翻一个轴（给状态行用）。 */
    public static boolean flipped() {
        return mode() != Mode.NORMAL;
    }

    /** 换一个档位并立刻落盘（下一帧就生效，渲染器每帧都读 {@link #mode()}）。 */
    public static void set(final Mode mode) {
        if (mode == null) {
            return;
        }
        current = mode;
        write(mode);
        LteLog.LOGGER.info("[lte] dep orientation = {}", mode.id());
    }

    /** 切到下一个档位。 */
    public static Mode cycle() {
        final Mode next = mode().next();
        set(next);
        return next;
    }

    // ------------------------------------------------------------------ 落盘

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    private static Mode read() {
        final Path path = path();
        if (!Files.exists(path)) {
            return DEFAULT;
        }
        try {
            final String json = Files.readString(path, StandardCharsets.UTF_8);
            final JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            final int version = object.has("version") ? object.get("version").getAsInt() : 0;
            if (version < CONFIG_VERSION) {
                // 上一版留下的结论，和这次改的默认档不再是一回事 —— 一律按新默认走。
                LteLog.LOGGER.info("[lte] dep orientation: {} is v{} (need v{}), using default {}",
                        path, version, CONFIG_VERSION, DEFAULT.id());
                return DEFAULT;
            }
            final Mode mode = object.has("mode")
                    ? Mode.byId(object.get("mode").getAsString())
                    : null;
            if (mode == null) {
                LteLog.LOGGER.warn("[lte] dep orientation: {} has no usable \"mode\", using {}",
                        path, DEFAULT.id());
                return DEFAULT;
            }
            LteLog.LOGGER.info("[lte] dep orientation loaded: {}", mode.id());
            return mode;
        } catch (IOException | RuntimeException e) {
            // 渲染路径上的东西：读坏了只能回落，绝不能把方块整个画不出来。
            LteLog.LOGGER.warn("[lte] dep orientation: could not read {}", path, e);
            return DEFAULT;
        }
    }

    private static void write(final Mode mode) {
        final Path path = path();
        try {
            Files.createDirectories(path.getParent());
            final JsonObject object = new JsonObject();
            object.addProperty("version", CONFIG_VERSION);
            object.addProperty("mode", mode.id());
            object.addProperty("_comment", "mtrlte:dep_on_wall 的读数方向；游戏内用 /mtr_lte dep 切换");
            Files.writeString(path, GSON.toJson(object), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 写不进去只是「下次进来回到默认」，不影响这一局。
            LteLog.LOGGER.warn("[lte] dep orientation: could not write {}", path, e);
        }
    }

    /** 给 {@code /mtr_lte dep status} 的一行状态（含四档顺序提示）。 */
    public static String statusLine() {
        final Mode mode = mode();
        return "LTE：倒数器方向 = " + mode.id() + "（" + mode.label() + "）"
                + " — 下一档：" + mode.next().id()
                + "；切换：/mtr_lte dep（依次 normal → mirror → flip → rotate）";
    }
}
