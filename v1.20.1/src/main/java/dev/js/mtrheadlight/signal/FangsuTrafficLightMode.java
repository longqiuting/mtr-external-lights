package dev.js.mtrheadlight.signal;

import dev.js.mtrheadlight.LteLog;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 把方速（fangsu）交通灯的显示模式切到「MTR 信号联动」（{@code dispType="mtr"}）。
 *
 * <h2>为什么需要这一步（1.3.2 的 mtr 档是个空档）</h2>
 * <p>{@code BlockEntityTrafficLight.whenRendering()} 里只有一处分支（CFR 反编译
 * {@code mods/[方速MTR扩展] [MTR3]fangsu-mc1.20.1-FABRIC-1.3.2-build20261102090023.jar}）：
 * <pre>
 * 119: public void whenRendering() {
 * 120:   if (markedError || !modelLoaded || content == null) return;
 * 123:   if ("program".equals(this.dispType)) this.drive();      // &lt;-- 唯一的灯色驱动
 * 126:   ... for (subModelDmhs) { if (!alwaysOn &amp;&amp; (lightState.get(label)==null||!on)) continue;
 * 133:       ctx.drawModel(dmh, null); }
 * }
 * </pre>
 * 也就是说 {@code dispType="mtr"} 的作用<b>只是把积木程序关掉</b>：没有任何代码往
 * {@code lightState} 里写值，{@code lightState.get(label)} 保持 {@code false}，三个灯珠全灭。
 * 所以「切到 mtr」这一步本身必须由我们补上驱动（见 {@link FangsuSignalSync}）。
 *
 * <h2>怎么切（不产生编译期 com.fangsu 依赖）</h2>
 * <p>方速自己的配置界面走的是 {@code getConfigs()} 里那个 {@code EnumConfig} 的 setter：
 * <pre>
 * configs.add(new EnumConfig(title, spec, labels, () -> max(0, List.of("program","mtr").indexOf(dispType)), idx -> {
 *     this.dispType = List.of("program","mtr").get(idx);   // 字段
 *     extra.put("dispType", this.dispType);                // extraConfigs（写进 NBT）
 *     this.setConfigChanged();                             // markDirty + rebuildProgram()
 * }).setSaveOnChange(true));
 * </pre>
 * {@code ConfigEntry} 把那个 lambda 存在 {@code protected final Consumer<T> setter} 里，
 * 并且有公开的 {@code save(Object be)}。所以这里的第一选择是<b>把那条 setter 拿出来
 * {@code accept(1)}</b> —— 和玩家在配置界面点一下按钮走的是同一条代码路径（含
 * {@code extraConfigs} 落盘与 model 重载），不是我们另写一套赋值。
 *
 * <p>拿不到 setter（方速改了 {@code getConfigs()} 的结构、或拿到的不是 {@code EnumConfig}）时
 * 降级为直接反射写 {@code dispType} 字段 + {@code extraConfigs.put} + {@code markDirty()}，
 * 两条路都<b>回读字段确认</b>，不成功就返回 {@link Result#FAIL} 让玩家自己切。
 *
 * <h2>为什么不需要客户端同步</h2>
 * <p>方速的 {@code dispType} 字段<b>从来不由 NBT 恢复</b>：{@code method_11007}（writeNbt）把
 * {@code extraConfigs} 写进 {@code extraConfig} 复合标签，{@code method_11014}（readNbt）读回来
 * 也只塞进 map，没有一行再赋给 {@code dispType}（全 jar {@code grep dispType} 只命中
 * {@code BlockEntityTrafficLight} 的字段声明、{@code getConfigs()} 的 lambda 和
 * {@code whenRendering()} 的判断）。所以客户端那一边由
 * {@link FangsuSignalSync} 每帧强制写字段，{@link #pushToClients} 只是顺手把
 * {@code extraConfig} 推给客户端（配置界面显示正确），绑定的显示效果不依赖它。
 */
public final class FangsuTrafficLightMode {

    /** {@code toMtr} 的结果。 */
    public enum Result {
        /** 原来是 program，已经切到 mtr。 */
        OK,
        /** 本来就在 mtr，什么都没做。 */
        ALREADY,
        /** 点到的不是方速交通灯（或它的方块实体读不到）。 */
        NOT_FANGSU,
        /** 是交通灯，但切不过去 —— 调用方应当提示玩家手动切换。 */
        FAIL
    }

    private static final String KEY_DISP_TYPE = "dispType";
    private static final String MODE_MTR = "mtr";
    private static final String ENUM_CONFIG = "com.fangsu.extraConfig.EnumConfig";

    /**
     * {@code getConfigs()} 里 {@code List.of("program","mtr")} 的 mtr 下标。写死是安全的：上面那段
     * lambda 把「indexOf」和「get(idx)」都绑在同一个 {@code List.of(KEY_PROGRAM, "mtr")} 上，
     * 顺序就是声明顺序；而且下面每次都回读 {@code dispType} 字段确认，改了下标只会变成 FAIL。
     */
    private static final int MODE_MTR_INDEX = 1;

    private FangsuTrafficLightMode() {
    }

    // ------------------------------------------------------------------ 对外

    /**
     * 把 {@code pos} 上的方速交通灯切到 mtr 档。<b>只能在服务端线程调用</b>（写字段 + markDirty）。
     *
     * <p>失败一律返回 {@link Result#FAIL} / {@link Result#NOT_FANGSU}，不抛异常：调用方（
     * {@code SignalLinkItem}）要在「切不过去」时仍然继续走绑定流程。
     */
    public static Result toMtr(final World world, final BlockPos pos) {
        if (world == null || pos == null || world.isClient) {
            return Result.FAIL;
        }
        final Object be;
        try {
            be = world.getBlockEntity(pos);
        } catch (Throwable t) {
            return Result.FAIL;
        }
        if (be == null || modeField(be.getClass()) == null) {
            return Result.NOT_FANGSU;
        }

        final String old = readMode(be);
        if (MODE_MTR.equals(old)) {
            return Result.ALREADY;
        }

        if (setViaOwnConfig(be) || writeFieldsDirectly(be)) {
            if (MODE_MTR.equals(readMode(be))) {
                pushToClients(world, pos);
                LteLog.LOGGER.info("[lte] fangsu: mode={}->mtr ok", old == null ? "?" : old);
                return Result.OK;
            }
        }
        LteLog.LOGGER.warn("[lte] fangsu: mode={}->mtr fail", old == null ? "?" : old);
        return Result.FAIL;
    }

    /** 只读当前模式（给日志/提示用）；拿不到返回 {@code null}。 */
    public static String currentMode(final World world, final BlockPos pos) {
        try {
            if (world == null || pos == null) {
                return null;
            }
            final Object be = world.getBlockEntity(pos);
            return be == null ? null : readMode(be);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 第一条路：方速自己的 setter

    /**
     * 走方速配置界面那条路：{@code getConfigs()} → 第一个 {@code EnumConfig} → 它的
     * {@code ConfigEntry#setter}（{@code Consumer<Integer>}）→ {@code accept(1)}。
     */
    private static boolean setViaOwnConfig(final Object be) {
        try {
            final Method getConfigs = be.getClass().getMethod("getConfigs");
            final Object raw = getConfigs.invoke(be);
            if (!(raw instanceof List<?> configs)) {
                return false;
            }
            for (final Object entry : configs) {
                if (entry == null || !ENUM_CONFIG.equals(entry.getClass().getName())) {
                    continue;
                }
                final Field setter = field(entry.getClass(), "setter");
                if (setter == null) {
                    return false;
                }
                final Object consumer = setter.get(entry);
                if (consumer instanceof Consumer<?> c) {
                    acceptRaw(c, MODE_MTR_INDEX);
                    return true;
                }
                return false;
            }
        } catch (Throwable t) {
            LteLog.LOGGER.debug("[lte] fangsu: own config setter unavailable ({})", t.toString());
        }
        return false;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void acceptRaw(final Consumer<?> consumer, final int value) {
        ((Consumer) consumer).accept(Integer.valueOf(value));
    }

    // ------------------------------------------------------------------ 第二条路：直接写字段

    private static boolean writeFieldsDirectly(final Object be) {
        try {
            final Field mode = modeField(be.getClass());
            if (mode == null) {
                return false;
            }
            mode.set(be, MODE_MTR);
            final Field extra = field(be.getClass(), "extraConfigs");
            if (extra != null && extra.get(be) instanceof Map<?, ?> map) {
                ((Map<String, String>) map).put(KEY_DISP_TYPE, MODE_MTR);
            }
            if (be instanceof BlockEntity blockEntity) {
                blockEntity.markDirty();
            }
            // rebuildProgram() 是 private，有就调，没有就算了（mtr 档不读 program）。
            try {
                final Method rebuild = be.getClass().getDeclaredMethod("rebuildProgram");
                rebuild.setAccessible(true);
                rebuild.invoke(be);
            } catch (Throwable ignored) {
                // 私有方法改名/删掉都不影响 mtr 档的显示。
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 把 {@code extraConfig}（含 {@code dispType}）推给追踪这个区块的客户端。
     *
     * <p>flags 用 3（{@code NOTIFY_NEIGHBORS|NOTIFY_LISTENERS}），和方速自己的
     * {@code readC2S} → {@code method_8413}（{@code World#updateListeners}）完全一致。
     * 这一步纯粹是让客户端的配置界面显示正确，显示效果由 {@link FangsuSignalSync} 驱动，
     * 所以失败也只吞掉。
     */
    private static void pushToClients(final World world, final BlockPos pos) {
        try {
            final BlockState state = world.getBlockState(pos);
            world.updateListeners(pos, state, state, 3);
        } catch (Throwable ignored) {
            // 同步失败不影响服务端状态（NBT 里已经是 mtr）。
        }
    }

    // ------------------------------------------------------------------ 反射工具

    private static String readMode(final Object be) {
        try {
            final Field mode = modeField(be.getClass());
            if (mode == null) {
                return null;
            }
            return mode.get(be) instanceof String s ? s : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@code private String dispType}（{@code BlockEntityTrafficLight}），按声明类缓存。 */
    private static Field modeField(final Class<?> type) {
        return field(type, KEY_DISP_TYPE);
    }

    /**
     * 沿继承链找字段（{@code dispType} 在 {@code BlockEntityTrafficLight}，{@code extraConfigs}
     * 在 {@code FunctionalObjBlockEntity}，{@code setter} 在 {@code ConfigEntry}）并
     * {@code setAccessible}。类不在就是 {@code null}。
     */
    private static Field field(final Class<?> type, final String name) {
        if (type == null || name == null) {
            return null;
        }
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                final Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // 往上找
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }
}
