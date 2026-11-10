package dev.js.mtrheadlight;

import dev.js.mtrheadlight.VehicleLightOverrides.Beam;
import mtr.client.ClientData;
import mtr.client.DynamicTrainModel;
import mtr.client.TrainClientRegistry;
import mtr.client.TrainProperties;
import mtr.data.Siding;
import mtr.data.TrainClient;
import mtr.render.JonModelTrainRenderer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 一个车型的编辑会话：光束列表 + 每一束的「自动识别值」底稿。
 *
 * <h2>为什么编辑的是合并后的整份列表</h2>
 * 数据层的 {@code merge} 是按<b>类型顺序</b>把声明束叠在自动识别束上的
 * （{@link VehicleLightOverrides#resolve}）：一束声明了 {@code pos} 的头灯会顶掉该类型的<b>全部</b>
 * 自动识别头灯（「写一束头灯 = 只有一束头灯」）。如果编辑器只把改过的那几束写回运行时层，
 * 拖动一对头灯里的左边那束就会让右边那束从画面里消失 - 玩家会以为「滑块把灯删了」。
 *
 * <p>所以会话一打开就把 {@code resolve()} 的结果<b>物化</b>成每一束都写满数值的列表，之后每次改动
 * 都整份 {@link VehicleLightOverrides#put(String, List)}。屏幕上看到的就是导出文件里会有的东西，
 * 拖滑块不会连带删掉别的光束。
 *
 * <h2>↺ 的「自动识别值」从哪来</h2>
 * 并行保存一份 {@link #base}：逐束对应，打开会话时取自动识别（{@link VehicleLightOverrides#autoBeams}）
 * 里同类型第 n 束；没有对应的（玩家新增的束）就取它被创建时的数值快照。↺ 只把<b>这一个字段</b>写成
 * 底稿里的值 - 类型 / 颜色 / 位置 / 旋转 / 长度 / 宽度 / 透明度各自独立，不会把整束重置，
 * 也绝不会把滑块归零。
 *
 * <h2>保存语义</h2>
 * 每次改动立刻 {@code put} 进运行时层（世界里立刻预览），<b>不写盘</b>；{@link #exportFile()} 才落盘。
 * {@link #abandon()} 只删本次会话写过的那几个车型的运行时覆盖，资源包层原样保留。
 */
final class BeamSession {

    // --- 数据层的兜底值（与 VehicleLightOverrides 的私有常量一致） -------------------------
    //
    // 只有在「没有自动识别结果」时才用得上：例如车型模型还没被客户端加载（远处 / 未点亮），
    // 或者这一束是玩家新增的。有自动识别时一律用自动识别值。

    /** {@code VehicleLightOverrides.DEFAULT_HEAD_LENGTH}. */
    private static final double HEAD_LENGTH = 16.0D;
    /** {@code VehicleLightOverrides.DEFAULT_TAIL_LENGTH}. */
    private static final double TAIL_LENGTH = 8.0D;
    /** {@code VehicleLightOverrides.DEFAULT_WIDTH}. */
    private static final double WIDTH = 3.0D;
    /** {@code VehicleLightOverrides.DEFAULT_ALPHA}. */
    private static final double ALPHA = 0.16D;
    /** {@code VehicleLights.DEFAULT_HEIGHT}，没有模型时的灯高（格）。 */
    private static final double HEIGHT = 0.55D;

    /** 本次游戏会话里被编辑器 {@code put} 写过的车型 id；「放弃修改」按它精确回滚。 */
    private static final Set<String> EDITED = new LinkedHashSet<>();

    /** 滑块字段。↺ 一次只重置一个。 */
    enum Field {
        TYPE, COLOR, POS_X, POS_Y, POS_Z, ROT_X, ROT_Y, ROT_Z, LENGTH, WIDTH, ALPHA
    }

    /** 车型 id（MTR 的 {@code baseTrainType}）。 */
    final String vehicleId;
    /** 当前编辑中的光束，每一束都写满数值（见类注释）。 */
    final List<Beam> work = new ArrayList<>();
    /** 与 {@link #work} 逐束对应的「自动识别值」底稿，↺ 的来源。 */
    final List<Beam> base = new ArrayList<>();
    /** 光束列表页里被勾选的束（{@link #work} 的下标）。 */
    final Set<Integer> selected = new LinkedHashSet<>();

    private BeamSession(final String vehicleId) {
        this.vehicleId = vehicleId;
    }

    // ---------------------------------------------------------------------------------------------
    // 打开会话 / 车型目录
    // ---------------------------------------------------------------------------------------------

    /** 打开 {@code vehicleId} 的会话：合并后的整份列表 + 逐束自动识别底稿。 */
    static BeamSession open(final String vehicleId) {
        return open(vehicleId, null);
    }

    /**
     * @param layout 显式布局。导入的资源包车型<b>不在 MTR 注册表里</b>，{@link #layoutOf} 查不到它，
     *               所以由 3D 编辑器把从包内模型算出来的布局传进来；null 时照旧按 id 查。
     */
    static BeamSession open(final String vehicleId, final VehicleLights.Layout layout) {
        final BeamSession session = new BeamSession(vehicleId == null ? "" : vehicleId.trim());
        final VehicleLights.Layout resolved = layout != null ? layout : layoutOf(session.vehicleId);
        final List<Beam> auto = VehicleLightOverrides.autoBeams(resolved);
        final List<Beam> effective = VehicleLightOverrides.resolve(session.vehicleId, resolved);
        final Map<String, Integer> seen = new LinkedHashMap<>();
        for (final Beam beam : effective) {
            if (beam == null) {
                continue;
            }
            final Beam matched = nthOfType(auto, typeOf(beam), seen.merge(typeOf(beam), 1, Integer::sum) - 1);
            final Beam detected = matched == null ? null : materialise(matched, null);
            final Beam fallback = matched == null ? snapshot(beam) : detected;
            session.base.add(fallback);
            session.work.add(materialise(beam, fallback));
        }
        return session;
    }

    /** 一次会话里编辑过的车型 id 数量 - 「放弃修改」的作用范围。 */
    static int editedCount() {
        return EDITED.size();
    }

    /**
     * 清掉本次会话的运行时覆盖：只删编辑器写过的车型，资源包层不受影响。
     *
     * @return 被清掉的车型数
     */
    static int abandon() {
        final List<String> ids = new ArrayList<>(EDITED);
        EDITED.clear();
        int removed = 0;
        for (final String id : ids) {
            if (VehicleLightOverrides.remove(id)) {
                removed++;
            }
        }
        return removed;
    }

    /** 世界里出现过的车型：{@code ClientData.TRAINS} 的 trainId 去重，按 id 排序。 */
    static List<VehicleEntry> vehicles() {
        final TreeSet<String> ids = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        try {
            for (final TrainClient train : ClientData.TRAINS) {
                final String id = idOf(train);
                if (id != null && !id.isBlank()) {
                    ids.add(id);
                }
            }
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] beam editor: cannot read TRAINS", t);
        }
        final List<VehicleEntry> out = new ArrayList<>(ids.size());
        for (final String id : ids) {
            out.add(new VehicleEntry(id, summary(layoutOf(id))));
        }
        return out;
    }

    /** 一条车型行：id + 「自动识别：head K · tail M」。 */
    static final class VehicleEntry {
        final String id;
        /** 自动识别的结果，{@code 无} 表示没有模型/没有识别到。 */
        final String auto;

        VehicleEntry(final String id, final String auto) {
            this.id = id;
            this.auto = auto;
        }
    }

    /** {@code head 2 · tail 1}；一束都没有时用 lang 里的「无」。 */
    static String summary(final VehicleLights.Layout layout) {
        final List<Beam> auto = VehicleLightOverrides.autoBeams(layout);
        if (auto.isEmpty()) {
            return null;
        }
        int head = 0;
        int tail = 0;
        for (final Beam beam : auto) {
            if ("tail".equalsIgnoreCase(typeOf(beam))) {
                tail++;
            } else {
                head++;
            }
        }
        return "head " + head + " · tail " + tail;
    }

    /** 侧线当前使用的车型；读不到（老版本 MTR / 没配车）返回 null。 */
    static String presetOf(final Siding siding) {
        if (siding == null) {
            return null;
        }
        try {
            final String id = siding.getTrainId();
            return id == null || id.isBlank() ? null : id;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 某个车型的模型布局（自动识别的输入），没有模型时 null。 */
    static VehicleLights.Layout layoutOf(final String vehicleId) {
        if (vehicleId == null || vehicleId.isEmpty()) {
            return null;
        }
        try {
            for (final TrainClient train : ClientData.TRAINS) {
                final String id = idOf(train);
                if (id == null || !id.equalsIgnoreCase(vehicleId)) {
                    continue;
                }
                final VehicleLights.Layout layout = VehicleLights.layoutFor(train);
                if (layout != null) {
                    return layout;
                }
            }
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] beam editor: no layout for {}", vehicleId, t);
        }
        // 车型只在资源包里、世界里没有这列车时，布局仍能从注册表原型拿到
        // （3D 编辑器直接读资源包车型列表，走的就是这条路）。
        try {
            final TrainProperties properties = TrainClientRegistry.getTrainProperties(vehicleId);
            if (properties != null && properties.renderer instanceof JonModelTrainRenderer jon
                    && jon.model instanceof DynamicTrainModel dynamic) {
                return VehicleLights.layoutFor(dynamic, 0);
            }
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] beam editor: no pack layout for {}", vehicleId, t);
        }
        return null;
    }

    /** 世界里正在使用的车型 id（列表里打「●」，也用于选取默认车型）。 */
    static Set<String> inUseIds() {
        final Set<String> out = new LinkedHashSet<>();
        try {
            for (final TrainClient train : ClientData.TRAINS) {
                final String id = idOf(train);
                if (id != null && !id.isBlank()) {
                    out.add(id);
                }
            }
        } catch (Throwable ignored) {
            // 未进存档
        }
        return out;
    }

    /** 一辆车的车型 id：优先 {@code baseTrainType}，退回 {@code trainId}。 */
    private static String idOf(final TrainClient train) {
        if (train == null) {
            return null;
        }
        try {
            if (train.baseTrainType != null && !train.baseTrainType.isBlank()) {
                return train.baseTrainType;
            }
        } catch (Throwable ignored) {
            // 老版本 MTR 可能没有这个字段，下一个来源。
        }
        try {
            return train.trainId;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 编辑
    // ---------------------------------------------------------------------------------------------

    int size() {
        return this.work.size();
    }

    Beam beam(final int index) {
        return index >= 0 && index < this.work.size() ? this.work.get(index) : null;
    }

    /** 这一束的自动识别底稿；永远非 null。 */
    Beam base(final int index) {
        return index >= 0 && index < this.base.size() ? this.base.get(index) : null;
    }

    /** 把整个列表写进运行时层（世界里立刻生效，不落盘）。 */
    void save() {
        VehicleLightOverrides.put(this.vehicleId, this.work);
        EDITED.add(this.vehicleId);
    }

    /**
     * 末尾追加一束：类型跟着上一束，颜色 / 长度 / 宽度 / 透明度取类型默认值，位置复制上一束
     * （没有上一束时用车型的默认灯高）。新束的 ↺ 底稿就是它自己的初始值。
     *
     * @return 新束的下标
     */
    int add() {
        final Beam previous = this.work.isEmpty() ? null : this.work.get(this.work.size() - 1);
        final String type = previous == null ? "head" : typeOf(previous);
        final Beam created = new Beam(type);
        created.color = defaultColor(type);
        created.pos = previous != null && previous.pos != null
                ? previous.pos.clone() : new double[]{0.0D, HEIGHT, 0.0D};
        created.rot = new double[]{0.0D, 0.0D, 0.0D};
        created.length = previous != null && previous.length != null
                ? previous.length : defaultLength(type);
        created.width = previous != null && previous.width != null
                ? previous.width : WIDTH;
        created.alpha = previous != null && previous.alpha != null
                ? previous.alpha : ALPHA;
        this.work.add(created);
        this.base.add(created.copy());
        this.selected.add(this.work.size() - 1);
        save();
        return this.work.size() - 1;
    }

    /**
     * 在 {@code index} 后面复制一束：整束数值（类型 / 颜色 / 位置 / 旋转 / 长度 / 宽度 / 透明度）
     * 原样复制，只错开 0.25 格的位置，免得两束完全重合看不出来。↺ 底稿跟着复制。
     *
     * @return 新束的下标；{@code index} 非法时返回 -1
     */
    int duplicate(final int index) {
        final Beam source = beam(index);
        if (source == null) {
            return -1;
        }
        final Beam copy = materialise(source, null);
        if (copy.pos != null) {
            copy.pos[1] += 0.25D;
        }
        final int at = Math.min(this.work.size(), index + 1);
        this.work.add(at, copy);
        this.base.add(at, copy.copy());
        this.selected.clear();
        this.selected.add(Integer.valueOf(at));
        save();
        return at;
    }

    /**
     * 删除勾选的束。数据层把「空列表」解释成「没有覆盖」({@link VehicleLightOverrides#put})，
     * 所以最后一束删不掉 - 删空会让自动识别重新出现，而不是真的没有灯。
     *
     * @return false 表示这次删除会把列表清空，没有执行
     */
    boolean deleteSelected() {
        if (this.selected.isEmpty()) {
            return false;
        }
        final List<Integer> indexes = new ArrayList<>(this.selected);
        indexes.sort((a, b) -> Integer.compare(b, a));
        int removable = 0;
        for (final Integer index : indexes) {
            if (index != null && index >= 0 && index < this.work.size()) {
                removable++;
            }
        }
        if (this.work.size() - removable < 1) {
            return false;
        }
        for (final Integer index : indexes) {
            if (index == null || index < 0 || index >= this.work.size()) {
                continue;
            }
            this.work.remove(index.intValue());
            this.base.remove(index.intValue());
        }
        this.selected.clear();
        save();
        return true;
    }

    /**
     * ↺：把 {@code index} 这一束的<b>一个</b>字段写回自动识别底稿的值（不是 0）。
     * 类型变化会让颜色底稿跟着换成新类型的默认色，否则「头灯改尾灯」会带着头灯的暖白。
     */
    void resetField(final int index, final Field field) {
        if (applyReset(index, field)) {
            save();
        }
    }

    /** 整束 ↺：11 个字段一次恢复，只写一次运行时层。 */
    void resetAll(final int index) {
        boolean touched = false;
        for (final Field field : Field.values()) {
            touched |= applyReset(index, field);
        }
        if (touched) {
            save();
        }
    }

    private boolean applyReset(final int index, final Field field) {
        final Beam beam = beam(index);
        final Beam source = base(index);
        if (beam == null) {
            return false;
        }
        switch (field) {
            case TYPE:
                final String type = source == null ? "head" : typeOf(source);
                beam.type = type;
                beam.color = source != null && source.color != null
                        ? source.color : defaultColor(type);
                break;
            case COLOR:
                beam.color = source != null && source.color != null
                        ? source.color : defaultColor(typeOf(beam));
                break;
            case POS_X:
            case POS_Y:
            case POS_Z:
                beam.pos = vector(source == null ? null : source.pos, beam.pos, HEIGHT);
                break;
            case ROT_X:
            case ROT_Y:
            case ROT_Z:
                beam.rot = vector(source == null ? null : source.rot, beam.rot, 0.0D);
                break;
            case LENGTH:
                beam.length = Double.valueOf(source != null && source.length != null
                        ? source.length : defaultLength(typeOf(beam)));
                break;
            case WIDTH:
                beam.width = Double.valueOf(source != null && source.width != null
                        ? source.width : WIDTH);
                break;
            case ALPHA:
                beam.alpha = Double.valueOf(source != null && source.alpha != null
                        ? source.alpha : ALPHA);
                break;
            default:
                return false;
        }
        // 位置 / 旋转是整组复制的（半继承的向量没有意义），所以 ↺ 一个轴会恢复整组。
        return true;
    }

    // ---------------------------------------------------------------------------------------------
    // 导出
    // ---------------------------------------------------------------------------------------------

    /** 导出写盘的位置：{@code config/lte_lights/lte_vehicle_lights.json} - 服务端也读同一个文件。 */
    static java.nio.file.Path exportPath() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                .resolve(VehicleLightPacks.CONFIG_DIR)
                .resolve(VehicleLightJson.FILE_NAME);
    }

    /**
     * 写盘并<b>立刻读回</b>校验：文件能被数据层的解析器原样读回，才算导出成功。
     *
     * @return {@code "车型数 束数"} 或 {@code "失败：<原因>"}
     */
    static String exportFile() {
        final java.nio.file.Path path = exportPath();
        try {
            VehicleLightOverrides.export(path);
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] beam editor: export failed", t);
            return "!" + (t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        try {
            final String json = java.nio.file.Files.readString(path, java.nio.charset.StandardCharsets.UTF_8);
            final VehicleLightJson.Result readBack = VehicleLightJson.parse(json, path.toString());
            if (!readBack.ok()) {
                return "!" + readBack.error;
            }
            int beams = 0;
            for (final VehicleLightOverrides.Vehicle v : readBack.vehicles) {
                beams += v.beams.size();
            }
            return readBack.vehicles.size() + "|" + beams;
        } catch (Throwable t) {
            return "!" + (t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 字段工具
    // ---------------------------------------------------------------------------------------------

    static String typeOf(final Beam beam) {
        return beam == null || beam.type == null || beam.type.isEmpty() ? "head" : beam.type;
    }

    static boolean isHead(final Beam beam) {
        return !"tail".equalsIgnoreCase(typeOf(beam));
    }

    /** 类型默认颜色 - 与 {@code Resolved.of} 用同一组常量。 */
    static int defaultColor(final String type) {
        return "tail".equalsIgnoreCase(type)
                ? HeadlightConfig.DEFAULT_TAIL_BEAM : HeadlightConfig.DEFAULT_HEAD_BEAM;
    }

    static double defaultLength(final String type) {
        return "tail".equalsIgnoreCase(type) ? TAIL_LENGTH : HEAD_LENGTH;
    }

    static double defaultWidth() {
        return WIDTH;
    }

    static double defaultAlpha() {
        return ALPHA;
    }

    /**
     * 把 {@code beam} 复制成每一束都写满数值的束（缺的字段从 {@code base} 取，再缺就用类型默认）。
     * {@link #work} 里只放这种束，屏幕 = 导出内容。
     */
    static Beam materialise(final Beam beam, final Beam base) {
        final Beam out = beam.copy();
        final String type = typeOf(out);
        if (out.color == null) {
            out.color = base != null && base.color != null
                    ? base.color : defaultColor(type);
        }
        if (out.pos == null) {
            out.pos = base != null && base.pos != null
                    ? base.pos.clone() : new double[]{0.0D, HEIGHT, 0.0D};
        }
        if (out.rot == null) {
            out.rot = base != null && base.rot != null
                    ? base.rot.clone() : new double[]{0.0D, 0.0D, 0.0D};
        }
        if (out.length == null) {
            out.length = Double.valueOf(base != null && base.length != null
                    ? base.length : defaultLength(type));
        }
        if (out.width == null) {
            out.width = Double.valueOf(base != null && base.width != null
                    ? base.width : WIDTH);
        }
        if (out.alpha == null) {
            out.alpha = Double.valueOf(base != null && base.alpha != null
                    ? base.alpha : ALPHA);
        }
        return out;
    }

    /** 一束的当前数值快照，用作「没有自动识别对应束」时的 ↺ 底稿。 */
    private static Beam snapshot(final Beam beam) {
        return materialise(beam, null);
    }

    /** {@code #RRGGBB}。 */
    static String hex(final Integer rgb) {
        return String.format(Locale.ROOT, "#%06X", (rgb == null ? 0 : rgb.intValue()) & 0xFFFFFF);
    }

    /** {@code head} / {@code tail} 在界面上的名字。 */
    static String typeName(final String type) {
        return BeamListScreen.tr("tail".equalsIgnoreCase(type) ? "beam.type.tail" : "beam.type.head")
                .getString();
    }

    private static double[] vector(final double[] preferred, final double[] current,
                                   final double heightDefault) {
        if (preferred != null && preferred.length >= 3) {
            return preferred.clone();
        }
        if (current != null && current.length >= 3) {
            return current.clone();
        }
        return new double[]{0.0D, heightDefault, 0.0D};
    }

    private static Beam nthOfType(final List<Beam> list, final String type, final int index) {
        int seen = 0;
        for (final Beam beam : list) {
            if (!type.equalsIgnoreCase(typeOf(beam))) {
                continue;
            }
            if (seen == index) {
                return beam;
            }
            seen++;
        }
        return null;
    }
}
