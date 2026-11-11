package dev.js.mtrheadlight;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.js.mtrheadlight.VehicleLightOverrides.Beam;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.resource.ResourcePackProfile;
import net.minecraft.resource.ResourceType;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 3D 编辑器「导出」写的那一份，一车一文件：{@code <桌面>/<key>.json}。
 *
 * <h2>车型 id = MTR 自定义车的 key</h2>
 * {@code mtr_custom_resources.json} 里 {@code custom_trains.<key>} 的 <b>key</b> 才是车型 id：
 * 它同时是 {@code assets/mtr/<key>/} 的目录名，也是导出的文件名。MTR 运行时把注册 id 写成
 * {@code mtr_custom_train_<key>}，编辑器内部拿到的常常是后者 —— 所以落盘前一律用
 * {@link #keyOf} 剥掉前缀。显示名（{@code name} 字段）只用于界面，不参与文件名。
 *
 * <h2>为什么不是 {@code lte_vehicle_lights.json}</h2>
 * 老格式是「一个文件装所有车型」，适合手写和发布，但 3D 编辑器每次只编一个车型，
 * 一车一文件才能做到「点导出只覆盖这个车型、不碰别的」。落到资源包里时它就是
 * {@code assets/lte/beam3D/<key>.json}，一个车型一个文件，diff 也干净。
 *
 * <h2>读的优先级</h2>
 * <ol>
 *   <li>{@code <桌面>/<id>.json} —— 编辑器上次导出的工作副本（桌面取不到时就是
 *       {@code config/lte_beams/}）；</li>
 *   <li>已加载的资源包，<b>新路径</b> {@code assets/lte/beam3D/<id>.json}
 *       （约定的主路径，永远先看它）；</li>
 *   <li>已加载的资源包，{@code assets/<ns>/beam3D/<id>.json}（作者自己的命名空间）；</li>
 *   <li>已加载的资源包，<b>旧路径</b> {@code assets/<ns>/lte_beams/<id>.json}（兼容）；</li>
 *   <li>{@code config/lte_beams/<id>.json} —— 老版本导出留下的文件（兼容）。</li>
 * </ol>
 * 新路径永远压过旧路径：先扫完所有包的 {@code beam3D/}，再看 {@code lte_beams/}。
 * 每一层都同时接受 {@code <id>.json}、{@code mtr_custom_train_<id>.json} 和小写形态，
 * 文件名比较一律<b>忽略大小写</b>；{@link #read(String, String...)} 还能额外传别名 id
 * （同一个车型的 key / 注册 id / {@code baseTrainType} 都对得上）。
 *
 * <h2>运行时也读这一份</h2>
 * {@code VehicleLightOverrides#setPackBeamReader} 注入的就是 {@link #read(String)}：
 * 渲染端第一次看到某个车型 id 时按这里的优先级找文件，找到就当成该车型的光束表来画
 * （见 {@code ProtoBeamCone#emitDeclared}）。编辑器与运行时因此读的是同一份文件、同一个解析器。
 *
 * <h2>为什么直接读包根而不是 {@code Identifier}</h2>
 * 资源管理器只接受 {@code [a-z0-9/._-]} 的路径，{@code beam3D} 里的 {@code D} 直接不是合法
 * Identifier —— {@code new Identifier("lte", "beam3D/x.json")} 会抛异常，
 * {@code getAllResources} 也永远看不到它。所以这里用 {@link ResourcePack#openRoot}
 * 按<b>路径段</b>读，绕开 Identifier 的字符合法性检查，作者写的目录名原样生效。
 *
 * <h2>字段（与运行时 {@link BeamGeom} 的输入一一对应）</h2>
 * <pre>
 *   type    head / tail / 资源包自定义名字
 *   color   #RRGGBB                  → BeamGeom.rgb
 *   pos     [lateral, up, along]     → BeamGeom.origin 的来源（车体局部模型空间，格）
 *   rot     [yaw, pitch, roll] 度    → BeamGeom.axis 的来源
 *   length  格                       → BeamGeom.length
 *   width   格（光锥直径）
 *   alpha   0..1（光柱峰值透明度）
 * </pre>
 * {@code BeamGeom} 自己只有派生的 {@code origin/axis/length/rgb}，这四项全部由上面的
 * {@code pos/rot/length/color} 算出来，所以文件里存的是「作者能改的数」，而不是派生结果。
 * 这样同一份文件既能在编辑器里重新打开，也能被数据层原样读回。
 */
final class BeamPackWriter {

    /** 桌面导出的旧落点 / 兼容读取目录：{@code config/lte_beams}。 */
    static final String CONFIG_DIR = "lte_beams";

    /** 资源包里的新目录：{@code assets/lte/beam3D/}。 */
    static final String PACK_DIR = "beam3D";

    /** 新目录的默认命名空间：{@code assets/lte/...}。 */
    static final String PACK_NAMESPACE = "lte";

    /** 资源包里的旧目录（只读兼容）：{@code assets/<ns>/lte_beams/}。 */
    static final String LEGACY_PACK_DIR = CONFIG_DIR;

    /** 找按车文件的目录，按优先级：{@code beam3D} 压过旧的 {@code lte_beams}。 */
    private static final String[] BEAM_DIRS = {PACK_DIR, LEGACY_PACK_DIR};

    /** MTR 给自定义车注册 id 时加的前缀（{@code mtr_custom_train_<key>}）。 */
    static final String ID_PREFIX = "mtr_custom_train_";

    private static final int FORMAT_VERSION = 1;

    /** 「磁盘上有包但没启用」这条检查每个会话只做一次（要列目录、开 zip，不能每帧做）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean DISABLED_PACK_CHECK =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** 已启用的包栈只建一次（每次建都要打开全部 zip），资源包重载时由客户端丢弃。 */
    private static final Object PACK_LOCK = new Object();
    private static List<ResourcePack> PACK_CACHE;

    private static final String[] COMMENT = {
        "LTE 3D beam editor export - one vehicle per file.",
        "The file name is the custom_trains key (no mtr_custom_train_ prefix).",
        "Place it at assets/lte/beam3D/<key>.json inside a resource pack",
        "(the older assets/<namespace>/lte_beams/<key>.json is still read).",
        "Beam fields map 1:1 to the runtime BeamGeom inputs:",
        "  color -> rgb, pos -> origin (car-local model space), rot -> axis,",
        "  length/width in blocks, alpha 0..1.",
        "pos is [lateral, up, along] in the model's own axes (a model whose length axis is X",
        "uses pos[0] for the along component). rot is degrees [yaw, pitch, roll]."
    };

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private BeamPackWriter() {
    }

    // ---------------------------------------------------------------------------------------------
    // 车型 id → key → 文件名
    // ---------------------------------------------------------------------------------------------

    /**
     * 车型 id → 资源包 key：剥掉 {@code mtr_custom_train_}（大小写不敏感）。
     * 返回的就是 {@code mtr_custom_resources.json} 里 {@code custom_trains} 的键，
     * 也是 {@code assets/mtr/<key>/} 的目录名和本模组导出的文件名。
     */
    static String keyOf(final String vehicleId) {
        String raw = vehicleId == null ? "" : vehicleId.trim();
        if (raw.regionMatches(true, 0, ID_PREFIX, 0, ID_PREFIX.length())) {
            raw = raw.substring(ID_PREFIX.length());
        }
        return raw;
    }

    /**
     * key → 文件名。key 里不会出现的通配符 / 路径分隔符统一换成 {@code _}。
     */
    static String fileName(final String vehicleId) {
        final String key = keyOf(vehicleId);
        final String safe = key.replaceAll("[^A-Za-z0-9._-]", "_");
        return (safe.isEmpty() ? "unknown" : safe) + ".json";
    }

    /**
     * 同一个车型可能出现的所有文件名，按优先级：{@code <key>.json}、
     * 原样 id（可能是 {@code mtr_custom_train_<key>}）、小写形态。
     *
     * <p>为什么要三个：作者手写的包里既可能用 key，也可能照注册 id 命名；而 MTR 的 key
     * 常是小写、注册 id 却可能带大写 —— 这一层就是「大小写不敏感」的落地点。
     */
    static List<String> fileNames(final String vehicleId) {
        final Set<String> out = new LinkedHashSet<>();
        final String key = keyOf(vehicleId);
        final String rawId = vehicleId == null ? "" : vehicleId.trim();
        for (final String base : new String[] {key, rawId, ID_PREFIX + key}) {
            if (base == null || base.isEmpty()) {
                continue;
            }
            for (final String variant : new String[] {base, base.toLowerCase(Locale.ROOT)}) {
                final String safe = variant.replaceAll("[^A-Za-z0-9._-]", "_");
                if (!safe.isEmpty()) {
                    out.add(safe + ".json");
                }
            }
        }
        if (out.isEmpty()) {
            out.add("unknown.json");
        }
        return new ArrayList<>(out);
    }

    // ---------------------------------------------------------------------------------------------
    // 导出位置
    // ---------------------------------------------------------------------------------------------

    /** {@code config/lte_beams}：老导出的落点，也是桌面不可用时的退路。 */
    static Path configDir() {
        return FabricLoader.getInstance().getConfigDir().resolve(CONFIG_DIR);
    }

    /**
     * 导出目录 = 桌面：{@code System.getProperty("user.home") + "\\Desktop"}。
     *
     * <p>桌面不存在 / 不可写（服务器、纯命令行环境）时退回 {@link #configDir()}，
     * 所以「导出」永远不会因为找不到桌面而失败。
     */
    static Path exportDir() {
        try {
            final String home = System.getProperty("user.home");
            if (home != null && !home.isBlank()) {
                final Path desktop = Paths.get(home + "\\Desktop");
                if (Files.isDirectory(desktop) && Files.isWritable(desktop)) {
                    return desktop;
                }
            }
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] beam editor: desktop unavailable, falling back to config", t);
        }
        return configDir();
    }

    /** {@link #exportDir()} 的别名；界面上的「导出目录」按钮就开这里。 */
    static Path dir() {
        return exportDir();
    }

    /** 导出落点（桌面，或退回 config）：{@code <dir>/<key>.json}。 */
    static Path pathFor(final String vehicleId) {
        return exportDir().resolve(fileName(vehicleId));
    }

    /** 写一个车型的文件，返回落盘路径。调用方负责把异常变成界面提示。 */
    static Path write(final String vehicleId, final List<Beam> beams) throws Exception {
        final Path path = pathFor(vehicleId);
        Files.createDirectories(path.getParent());
        // 文件内容里的 id 也必须是 key（不是 mtr_custom_train_<key>）。
        Files.writeString(path, toJson(keyOf(vehicleId), beams), StandardCharsets.UTF_8);
        return path;
    }

    /** 纯序列化（不碰磁盘），导出逻辑和离线测试共用。 */
    static String toJson(final String vehicleId, final List<Beam> beams) {
        final JsonObject root = new JsonObject();
        root.addProperty("version", FORMAT_VERSION);
        final JsonArray comment = new JsonArray();
        for (final String line : COMMENT) {
            comment.add(line);
        }
        root.add("_comment", comment);
        root.addProperty("id", vehicleId == null ? "" : vehicleId);
        final JsonArray array = new JsonArray();
        for (final Beam beam : beams) {
            if (beam != null) {
                // 与 lte_vehicle_lights.json 同一个序列化器：字段名和取整规则完全一致。
                array.add(VehicleLightJson.beamJson(beam));
            }
        }
        root.add("beams", array);
        return compactNumbers(GSON.toJson(root));
    }

    /** 与 {@link VehicleLightJson} 相同的可读性处理：纯数字数组压回一行，其余保持缩进。 */
    private static final java.util.regex.Pattern NUM_ARRAY = java.util.regex.Pattern.compile(
            "\\[\\s*\\n((?:\\s*-?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?,?\\s*\\n)+)\\s*\\]");

    private static String compactNumbers(final String pretty) {
        final java.util.regex.Matcher m = NUM_ARRAY.matcher(pretty);
        final StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String body = m.group(1).replaceAll("[\\s\\n]", "");
            body = body.replaceAll(",+$", "");
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement("[" + body + "]"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ---------------------------------------------------------------------------------------------
    // 读回
    // ---------------------------------------------------------------------------------------------

    /**
     * 读回这个车型：桌面导出 → 资源包新路径 → 资源包旧路径 → config 旧文件。
     *
     * <p>「文件存在但一条光束都没有」是合法结果（= 关掉覆盖），所以这里区分
     * 「没有这个文件」（继续往下找）和「文件里有 0 束」。
     *
     * @return 光束列表；四处都没有时返回空列表
     */
    static List<Beam> read(final String vehicleId) {
        return read(vehicleId, new String[0]);
    }

    /**
     * read() 的一个候选层：一组文件名（层内按顺序试），最多只在这些包里找。
     *
     * <p>关于别名：MTR 一辆自定义车同时挂着三个 id —— {@code mtr_custom_resources.json} 里的 key
     * （{@code xml6125jevd0c_normal}）、注册 id（{@code mtr_custom_train_<key>}），以及渲染器
     * 报的 {@code baseTrainType}（{@code train_12_2}，由 MTR 用 {@code model_properties} 生成）。
     * 前两个由 {@link BeamPackScope} 从包里读出来，第三个同样能反算回去，所以调用方不必再自己
     * 猜别名；额外的 {@code aliases} 只是给外部调用留的后门。
     */
    static final class Candidate {
        /** {@code key} 或 {@code base} —— 日志里的 source。 */
        final String source;
        final List<String> names;
        /** 只在这些包里找（空 = 全部已启用包）。 */
        final List<ResourcePack> packs;

        Candidate(final String source, final List<String> names, final List<ResourcePack> packs) {
            this.source = source;
            this.names = names == null ? List.of() : names;
            this.packs = packs == null ? List.of() : packs;
        }
    }

    /** 一个候选在资源包里的命中。 */
    static final class Hit {
        final String path;
        final List<Beam> beams;

        Hit(final String path, final List<Beam> beams) {
            this.path = path;
            this.beams = beams;
        }
    }

    /**
     * {@link #read} 的搜索计划：先问「这辆车是谁家的」（{@link BeamPackScope}），
     * 再按 key → baseTrainType 排文件名。
     */
    static final class Plan {
        final String id;
        /** 这辆车在包里对应的 {@code custom_trains} key（声明它的包才知道）。 */
        final List<String> keys;
        /** 这些 key 由哪些包声明 —— {@code _shared.json} 的同包判定用它。 */
        final Set<ResourcePack> owners = new LinkedHashSet<>();
        final List<Candidate> candidates;
        final List<String> tried;

        private Plan(final String id, final List<String> keys, final List<Candidate> candidates,
                     final List<String> tried) {
            this.id = id;
            this.keys = keys;
            this.candidates = candidates;
            this.tried = tried;
        }

        static Plan of(final String vehicleId, final String... aliases) {
            final String id = vehicleId == null ? "" : vehicleId.trim();
            return of(id.isEmpty() ? List.<BeamPackScope>of() : BeamPackScope.index(), id, aliases);
        }

        /** {@code scopes} 显式传入：离线检查用（没有 MinecraftClient 就没有「已启用包」）。 */
        static Plan of(final List<BeamPackScope> scopes, final String vehicleId,
                       final String... aliases) {
            final String id = vehicleId == null ? "" : vehicleId.trim();
            // 这辆车的所有字面 id 形态：key / 注册 id / 调用方给的别名。
            final LinkedHashSet<String> forms = new LinkedHashSet<>();
            if (!id.isEmpty()) {
                forms.add(keyOf(id));
                forms.add(id);
                forms.add(ID_PREFIX + keyOf(id));
            }
            if (aliases != null) {
                for (final String alias : aliases) {
                    if (alias != null && !alias.isBlank()) {
                        forms.add(keyOf(alias));
                        forms.add(alias.trim());
                    }
                }
            }

            // 谁认领这辆车：显式 key / 注册 id / base_train_type，再不行就用 model_properties
            // 反算 MTR 生成的运行时 id（train_12_2 这种）。
            final Map<String, List<ResourcePack>> declared = new LinkedHashMap<>();
            for (final String form : forms) {
                for (final BeamPackScope scope : scopes) {
                    for (final String key : scope.keysFor(form)) {
                        final List<ResourcePack> packs =
                                declared.computeIfAbsent(key, k -> new ArrayList<>());
                        if (!packs.contains(scope.pack)) {
                            packs.add(scope.pack);
                        }
                    }
                }
            }

            final Plan plan = new Plan(id, new ArrayList<>(declared.keySet()),
                    new ArrayList<>(), new ArrayList<>());
            for (final List<ResourcePack> packs : declared.values()) {
                plan.owners.addAll(packs);
            }
            final LinkedHashSet<String> seen = new LinkedHashSet<>();

            // 1) key 名：只在这个 key 的<b>声明包</b>里找（同包原则）
            for (final Map.Entry<String, List<ResourcePack>> entry : declared.entrySet()) {
                plan.add(seen, "key", namesFor(List.of(entry.getKey())), entry.getValue());
            }
            // 2) 原样 id / 注册 id / 别名：全包找（兼容「文件按 baseTrainType 命名」的老写法）
            final boolean idIsKey = plan.keys.contains(id) || plan.keys.contains(keyOf(id))
                    || plan.keys.contains(ID_PREFIX + keyOf(id));
            final List<String> raw = new ArrayList<>();
            for (final String form : forms) {
                if (!plan.keys.contains(form) && !plan.keys.contains(keyOf(form))) {
                    raw.add(form);
                }
            }
            plan.add(seen, idIsKey ? "key" : "base", namesFor(raw), List.of());
            // 3) 声明里写的 base_train_type / 生成的运行时 id（涂装包常常只写这一份）
            for (final String key : plan.keys) {
                for (final BeamPackScope scope : scopes) {
                    final String runtime = scope.runtimeIdOf(key);
                    if (runtime != null && !forms.contains(runtime)) {
                        plan.add(seen, "base", namesFor(List.of(runtime)), List.of());
                    }
                }
            }
            if (plan.tried.size() > 12) {
                plan.tried.subList(12, plan.tried.size()).clear();
                plan.tried.add("...");
            }
            return plan;
        }

        private void add(final Set<String> seen, final String source, final List<String> names,
                         final List<ResourcePack> packs) {
            if (names == null || names.isEmpty()) {
                return;
            }
            final String signature = String.join(",", names).toLowerCase(Locale.ROOT);
            if (!seen.add(signature)) {
                return;      // 同一组文件名只试一次（key == 原样 id 时很常见）
            }
            this.candidates.add(new Candidate(source, names, packs));
            for (final String name : names) {
                if (!this.tried.contains(name)) {
                    this.tried.add(name);
                }
            }
        }

        boolean isEmpty() {
            return this.candidates.isEmpty();
        }
    }

    /**
     * 读回这个车型。优先级（编辑器与运行时同一份，见 {@code docs/beam-pack-format.md}）：
     * <ol>
     *   <li>{@code <key>.json} —— 桌面工作副本，以及<b>声明了这个 key 的包</b>里的同名文件；</li>
     *   <li>{@code <baseTrainType>.json} —— 渲染器报的那个 id（MTR 自己生成的，如
     *       {@code train_12_2}），也接受 {@code mtr_custom_train_<key>} 与大小写形态；</li>
     *   <li>{@code _shared.json} —— <b>包级共用</b>，只对声明了这辆车的那个包生效；</li>
     *   <li>{@code config/lte_beams/<key>.json} —— 老版本导出的落点（兼容）。</li>
     * </ol>
     *
     * <p>「文件存在但一条光束都没有」是合法结果（= 关掉覆盖），所以这里区分
     * 「没有这个文件」（继续往下找）和「文件里有 0 束」。
     *
     * @return 光束列表；四处都没有时返回空列表
     */
    static List<Beam> read(final String vehicleId, final String... aliases) {
        final Plan plan = Plan.of(vehicleId, aliases);
        if (plan.isEmpty()) {
            if (!plan.id.isEmpty()) {
                logMiss(plan);
            }
            return List.of();
        }

        // 0) 桌面工作副本：编辑器刚导出的那份，压过包里的一切（key 名在前、base 名在后）。
        final Map<String, Path> desk = dirIndex(exportDir());
        for (final Candidate candidate : plan.candidates) {
            final Path path = findIn(desk, candidate.names);
            if (path == null) {
                continue;
            }
            final List<Beam> beams = readFile(path, plan.id);
            if (beams != null) {
                logHit(plan.id, path.toString(), beams.size(), candidate.source);
                return beams;
            }
        }

        // 1) 资源包里的按车文件
        for (final Candidate candidate : plan.candidates) {
            final Hit hit = scanPacks(plan.id, candidate);
            if (hit != null) {
                logHit(plan.id, hit.path, hit.beams.size(), candidate.source);
                return hit.beams;
            }
        }

        // 2) 包级共用：只认声明了这辆车的包（同包原则，见 BeamPackScope）
        final BeamPackScope.Shared shared = sharedFor(plan);
        if (shared != null && !shared.beams.isEmpty()) {
            // 共用那份是缓存里的同一个列表：交出去的是副本，调用方改了也污染不到下一个车型。
            final List<Beam> copy = new ArrayList<>(shared.beams.size());
            for (final Beam beam : shared.beams) {
                copy.add(beam.copy());
            }
            logHit(plan.id, shared.path, copy.size(), "shared");
            return copy;
        }

        // 3) config/lte_beams：老版本导出的落点（桌面不可用时它就是工作副本，别读两遍）
        if (!sameDir(configDir(), exportDir())) {
            final Map<String, Path> legacy = dirIndex(configDir());
            for (final Candidate candidate : plan.candidates) {
                final Path path = findIn(legacy, candidate.names);
                if (path == null) {
                    continue;
                }
                final List<Beam> beams = readFile(path, plan.id);
                if (beams != null) {
                    logHit(plan.id, path.toString(), beams.size(), "config");
                    return beams;
                }
            }
        }

        logMiss(plan);
        return List.of();
    }

    /**
     * {@code _shared.json}：只在<b>声明了这辆车</b>的包里找，后加载的包赢。
     *
     * <p>没有任何包认领这辆车（内置车、或只写了桌面文件）时不生效 —— 否则一个包里的
     * 「两束车头灯」会跑到别人的车上。
     */
    private static BeamPackScope.Shared sharedFor(final Plan plan) {
        if (plan.owners.isEmpty()) {
            return null;
        }
        BeamPackScope.Shared best = null;
        for (final BeamPackScope scope : BeamPackScope.index()) {
            if (!plan.owners.contains(scope.pack)) {
                continue;
            }
            final BeamPackScope.Shared shared = scope.shared();
            if (shared != null) {
                best = shared;
            }
        }
        return best;
    }

    /**
     * 不静默：一个车型四处都找不到时也要留一行（每个车型一次），并且把「包在磁盘上有、
     * 但没在资源包列表里启用」这件事单独说清楚 —— 那是玩家最容易自己修好的一类。
     */
    private static void logMiss(final Plan plan) {
        final String key = plan.id == null ? "" : plan.id.toLowerCase(Locale.ROOT);
        synchronized (LOG_LOCK) {
            if (!MISSES.add(key)) {
                return;
            }
            if (MISSES.size() > 512) {
                MISSES.clear();
            }
        }
        LteLog.LOGGER.info("[lte] beam pack: miss id={} tried={}", plan.id, plan.tried);
        warnIfPackPresentButDisabled();
    }

    /** 命中：每个车型只报一次（换了文件 / 换了车型才再报），绝不每帧。 */
    private static void logHit(final String id, final String path, final int beams,
                               final String source) {
        final String key = id == null ? "" : id.toLowerCase(Locale.ROOT);
        final String signature = path + "|" + beams + "|" + source;
        synchronized (LOG_LOCK) {
            if (signature.equals(HITS.get(key))) {
                return;
            }
            if (HITS.size() > 512) {
                HITS.clear();
            }
            HITS.put(key, signature);
        }
        LteLog.LOGGER.info("[lte] beam pack: {} id={} beams={} source={}", path, id,
                Integer.valueOf(beams), source);
    }

    /** 已启用的包数（只数，不建包 —— 建包是为了读内容，这里只要个数）。 */
    private static int enabledProfileCount() {
        try {
            final MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.getResourcePackManager() == null) {
                return 0;
            }
            return client.getResourcePackManager().getEnabledProfiles().size();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 同一辆车所有可能被作者当成文件名的 id，按优先级去重（忽略大小写）。 */
    static List<String> candidateIds(final String vehicleId, final String... aliases) {
        final Set<String> lowered = new LinkedHashSet<>();
        final List<String> out = new ArrayList<>();
        final List<String> raw = new ArrayList<>();
        if (vehicleId != null && !vehicleId.isBlank()) {
            raw.add(vehicleId.trim());
        }
        if (aliases != null) {
            for (final String alias : aliases) {
                if (alias != null && !alias.isBlank()) {
                    raw.add(alias.trim());
                }
            }
        }
        for (final String id : raw) {
            for (final String form : new String[] {keyOf(id), id, ID_PREFIX + keyOf(id)}) {
                if (form != null && !form.isBlank() && lowered.add(form.toLowerCase(Locale.ROOT))) {
                    out.add(form);
                }
            }
        }
        return out;
    }

    /** 这些 id 对应的全部文件名（含大小写形态），去重后按优先级排列。 */
    static List<String> namesFor(final List<String> ids) {
        final Set<String> lowered = new LinkedHashSet<>();
        final List<String> out = new ArrayList<>();
        for (final String id : ids) {
            for (final String name : fileNames(id)) {
                if (lowered.add(name.toLowerCase(Locale.ROOT))) {
                    out.add(name);
                }
            }
        }
        return out;
    }

    private static boolean sameDir(final Path a, final Path b) {
        try {
            return a != null && b != null
                    && a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 在目录里找第一个存在的候选文件：先按名字直接 resolve（Windows 上大小写不敏感，
     * 一步命中），再列目录按忽略大小写比一遍（Linux / 作者大小写写错的导出）。
     */
    static Path locate(final Path dir, final List<String> names) {
        if (dir == null || names == null || names.isEmpty() || !Files.isDirectory(dir)) {
            return null;
        }
        for (final String name : names) {
            final Path direct = dir.resolve(name);
            if (Files.isRegularFile(direct)) {
                return direct;
            }
        }
        final Set<String> lowered = new LinkedHashSet<>();
        for (final String name : names) {
            lowered.add(name.toLowerCase(Locale.ROOT));
        }
        try (Stream<Path> list = Files.list(dir)) {
            return list.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName() != null
                            && lowered.contains(p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .findFirst()
                    .orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 读一份具体的文件。
     *
     * @return null = 文件不存在 / 读不了；否则是解析结果（可能是空列表）
     */
    private static List<Beam> readFile(final Path path, final String vehicleId) {
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        try {
            return parse(Files.readString(path, StandardCharsets.UTF_8), keyOf(vehicleId), path.toString());
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] beam editor: cannot read {}", path, t);
            return null;
        }
    }

    /**
     * 在资源包里找一个候选（一组文件名）。
     *
     * <p>四层顺序，一层命中就返回：
     * <ol>
     *   <li>目录：{@code beam3D}（及小写形态）压过旧的 {@code lte_beams}；</li>
     *   <li>命名空间：{@code assets/lte/} 压过作者自己的命名空间；</li>
     *   <li>包栈：栈顶压栈底（与 Minecraft「后加载的包赢」一致）；</li>
     *   <li>文件名：{@code <key>.json} 压过 {@code mtr_custom_train_<key>.json}、大小写形态。</li>
     * </ol>
     *
     * <p>key 层的候选只会在<b>声明了这个 key 的包</b>里找（{@link Candidate#packs}），
     * 所以包里 A 的 key 不会去 B 里配对 —— 这既快，也避免「拿别人的文件」。
     *
     * @return null = 这些包里都没有这个文件
     */
    private static Hit scanPacks(final String vehicleId, final Candidate candidate) {
        final List<ResourcePack> packs = candidate.packs.isEmpty() ? enabledPacks() : candidate.packs;
        if (packs.isEmpty() || candidate.names.isEmpty()) {
            return null;
        }
        for (final String dir : BEAM_DIRS) {
            for (final boolean onlyDefault : new boolean[] {true, false}) {
                for (int i = packs.size() - 1; i >= 0; i--) {
                    final ResourcePack pack = packs.get(i);
                    for (final String namespace : namespacesOf(pack, onlyDefault)) {
                        for (final String dirName : new String[] {dir, dir.toLowerCase(Locale.ROOT)}) {
                            for (final String name : candidate.names) {
                                final String relative =
                                        "assets/" + namespace + "/" + dirName + "/" + name;
                                final String json = BeamPackScope.readAsset(pack, relative);
                                if (json == null || json.isBlank()) {
                                    continue;
                                }
                                final String path = pack.getName() + "!" + relative;
                                return new Hit(path, parse(json, keyOf(vehicleId), path));
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    /** 目录里「小写文件名 → 路径」。一次列目录，多个候选共用（不再每个名字列一遍）。 */
    private static Map<String, Path> dirIndex(final Path dir) {
        final Map<String, Path> out = new LinkedHashMap<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> list = Files.list(dir)) {
            list.filter(Files::isRegularFile).forEach(path -> {
                if (path.getFileName() != null) {
                    out.put(path.getFileName().toString().toLowerCase(Locale.ROOT), path);
                }
            });
        } catch (Throwable t) {
            // 目录不可读 = 没有文件
        }
        return out;
    }

    /** {@link #dirIndex} 里的第一个命中（文件名比较忽略大小写）。 */
    private static Path findIn(final Map<String, Path> index, final List<String> names) {
        if (index.isEmpty() || names == null) {
            return null;
        }
        for (final String name : names) {
            final Path path = index.get(name.toLowerCase(Locale.ROOT));
            if (path != null) {
                return path;
            }
        }
        return null;
    }

    /**
     * 「包在磁盘上有、但没启用」：扫 {@code <game>/resourcepacks/}（两层）里带
     * {@code assets/<ns>/beam3D/<id>.json} 的文件夹包和 zip，和已启用的包名对一遍，
     * 没启用的逐个 warn。这一层是玩家最常踩的坑（PCL 里勾了包、游戏里没勾，或者反过来），
     * 不提示的话界面只会显示「没找到文件」，看起来像模组坏了。
     */
    private static void warnIfPackPresentButDisabled() {
        if (!DISABLED_PACK_CHECK.compareAndSet(false, true)) {
            return;
        }
        // 已启用的包名先在调用线程（渲染 / 编辑器线程）取好，后台线程只碰文件系统。
        final Set<String> enabled = new LinkedHashSet<>();
        try {
            final MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.getResourcePackManager() != null) {
                for (final ResourcePackProfile profile : client.getResourcePackManager().getEnabledProfiles()) {
                    enabled.add(profile.getName());
                }
            }
        } catch (Throwable ignored) {
            // 拿不到就按「一个都没启用」处理：宁可多报一条，也不静默
        }
        // 这条检查要开几十上百个 zip 只为看一眼条目表：丢到后台线程，别卡渲染帧。
        final Thread thread = new Thread(() -> scanDisabledPacks(enabled), "lte-beam-pack-scan");
        thread.setDaemon(true);
        try {
            thread.start();
        } catch (Throwable t) {
            scanDisabledPacks(enabled);
        }
    }

    private static void scanDisabledPacks(final Set<String> enabled) {
        try {
            final Path root = FabricLoader.getInstance().getGameDir().resolve("resourcepacks");
            if (!Files.isDirectory(root)) {
                return;
            }
            final List<Path> packs = new ArrayList<>();
            try (Stream<Path> list = Files.list(root)) {
                list.forEach(p -> {
                    if (Files.isDirectory(p) || isZip(p)) {
                        packs.add(p);
                    }
                });
            }
            for (final Path pack : packs) {
                final String label = "file/" + pack.getFileName();
                if (enabled.contains(label)) {
                    continue;
                }
                if (containsBeamFile(pack)) {
                    LteLog.LOGGER.warn("[lte] beam pack: {} contains beam3D files but is NOT enabled"
                            + " in the resource pack list (启用它，或把文件放进已启用的包)", label);
                }
            }
        } catch (Throwable t) {
            LteLog.LOGGER.debug("[lte] beam pack: cannot check disabled packs", t);
        }
    }

    private static boolean isZip(final Path path) {
        final String name = path.getFileName() == null ? ""
                : path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".zip");
    }

    /** 文件夹包看文件系统，zip 看条目表；两种都只认 {@code assets/<ns>/beam3D/*.json}。 */
    private static boolean containsBeamFile(final Path pack) {
        try {
            if (Files.isDirectory(pack)) {
                final Path assets = pack.resolve("assets");
                if (!Files.isDirectory(assets)) {
                    return false;
                }
                try (Stream<Path> namespaces = Files.list(assets)) {
                    return namespaces.anyMatch(ns -> hasBeamDir(ns.resolve(PACK_DIR))
                            || hasBeamDir(ns.resolve(PACK_DIR.toLowerCase(Locale.ROOT)))
                            || hasBeamDir(ns.resolve(LEGACY_PACK_DIR)));
                }
            }
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(pack.toFile())) {
                return zip.stream().anyMatch(e -> {
                    final String name = e.getName().toLowerCase(Locale.ROOT);
                    return name.startsWith("assets/")
                            && (name.contains("/beam3d/") || name.contains("/lte_beams/"))
                            && name.endsWith(".json");
                });
            }
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean hasBeamDir(final Path dir) {
        try {
            if (!Files.isDirectory(dir)) {
                return false;
            }
            try (Stream<Path> list = Files.list(dir)) {
                return list.anyMatch(p -> Files.isRegularFile(p)
                        && p.getFileName() != null
                        && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"));
            }
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 这个包里值得找的命名空间。
     *
     * @param onlyDefault true = 只返回 {@link #PACK_NAMESPACE}（{@code lte}，约定的主路径）；
     *                    false = 只返回其余命名空间（作者自己的），按字母序
     */
    private static Set<String> namespacesOf(final ResourcePack pack, final boolean onlyDefault) {
        final Set<String> out = new LinkedHashSet<>();
        if (onlyDefault) {
            out.add(PACK_NAMESPACE);
            return out;
        }
        try {
            final List<String> rest = new ArrayList<>();
            for (final String namespace : pack.getNamespaces(ResourceType.CLIENT_RESOURCES)) {
                if (namespace != null && !namespace.isBlank()
                        && !PACK_NAMESPACE.equalsIgnoreCase(namespace)) {
                    rest.add(namespace.toLowerCase(Locale.ROOT));
                }
            }
            rest.sort(String.CASE_INSENSITIVE_ORDER);
            out.addAll(rest);
        } catch (Throwable ignored) {
            // 包不报命名空间（老/自建包）：没有别的命名空间可试
        }
        return out;
    }

    /** 已加载的资源包，低 → 高优先级（{@code getEnabledProfiles} 的顺序就是栈序）。 */
    static List<ResourcePack> enabledPacks() {
        synchronized (PACK_LOCK) {
            if (PACK_CACHE != null) {
                return PACK_CACHE;
            }
            final List<ResourcePack> out = new ArrayList<>();
            try {
                final MinecraftClient client = MinecraftClient.getInstance();
                if (client == null) {
                    return out;
                }
                final ResourcePackManager manager = client.getResourcePackManager();
                if (manager == null) {
                    return out;
                }
                for (final ResourcePackProfile profile : manager.getEnabledProfiles()) {
                    try {
                        out.add(profile.createResourcePack());
                    } catch (Throwable t) {
                        LteLog.LOGGER.warn("[lte] beam editor: cannot open pack {}", profile.getName());
                    }
                }
            } catch (Throwable t) {
                LteLog.LOGGER.warn("[lte] beam editor: pack scan unavailable", t);
            }
            PACK_CACHE = out;
            return out;
        }
    }

    /**
     * 丢掉缓存的包栈（资源包列表变了：F3+T、装/卸包）。不缓存的话运行端第一次问某个车型 id
     * 就要把【全部】已启用包各打开一次 —— 这是几十上百个 zip，落在渲染线程上就是一帧卡死。
     */
    static void invalidatePacks() {
        final List<ResourcePack> old;
        synchronized (PACK_LOCK) {
            old = PACK_CACHE;
            PACK_CACHE = null;
        }
        // 包栈变了：车型归属（custom_trains 索引）与「每个车型只报一次」的记录一起作废，
        // 否则换了包之后 `_shared.json` 还按旧包的作用域生效。
        BeamPackScope.invalidate();
        synchronized (LOG_LOCK) {
            HITS.clear();
            MISSES.clear();
        }
        if (old == null) {
            return;
        }
        for (final ResourcePack pack : old) {
            try {
                pack.close();
            } catch (Throwable ignored) {
                // 关不掉也只是一次文件句柄，不影响读数
            }
        }
    }

    // 说明：包内文件统一走 BeamPackScope.readAsset（同一条路径段读取），这里只留包栈缓存。

    /** {@link #logHit} / {@link #logMiss} 的去重表：每个车型只报一次，绝不每帧。 */
    private static final Object LOG_LOCK = new Object();
    private static final Map<String, String> HITS = new LinkedHashMap<>(64, 0.75F, true);
    private static final Set<String> MISSES = new LinkedHashSet<>();

    /**
     * 解析一份文件（同一格式，来源可以是桌面 / config、也可以是资源包里的
     * {@code assets/lte/beam3D/<key>.json} 或旧的 {@code assets/<ns>/lte_beams/<key>.json}）。
     * 复用 {@link VehicleLightJson} 的解析器做校验，因此非法字段会被丢弃而不是让界面崩掉。
     *
     * @param json     文件内容
     * @param vehicleId 车型 key（写回包装用）
     * @param source   出错时打印的来源（文件 / zip!条目）
     */
    static List<Beam> parse(final String json, final String vehicleId, final String source) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            final JsonElement rootEl = JsonParser.parseString(json);
            if (rootEl == null || !rootEl.isJsonObject()) {
                return List.of();
            }
            final JsonObject root = rootEl.getAsJsonObject();
            final JsonElement beamsEl = root.get("beams");
            if (beamsEl == null || !beamsEl.isJsonArray()) {
                return List.of();
            }
            // 包成老格式再走老解析器：一份校验逻辑，不养第二套。
            final JsonObject wrapper = new JsonObject();
            wrapper.addProperty("version", VehicleLightJson.FORMAT_VERSION);
            final JsonArray vehicles = new JsonArray();
            final JsonObject vehicle = new JsonObject();
            vehicle.addProperty("id", vehicleId == null ? "" : vehicleId);
            vehicle.add("beams", beamsEl.deepCopy());
            vehicles.add(vehicle);
            wrapper.add("vehicles", vehicles);
            final VehicleLightJson.Result result = VehicleLightJson.parse(
                    GSON.toJson(wrapper), source);
            if (!result.ok() || result.vehicles.isEmpty()) {
                return List.of();
            }
            return new ArrayList<>(result.vehicles.get(0).beams);
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] beam editor: cannot parse {}", source, t);
            return List.of();
        }
    }
}
