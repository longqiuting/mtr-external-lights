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
import net.minecraft.resource.InputSupplier;
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
     * 同 {@link #read(String)}，但额外按 {@code aliases} 里的车型 id 找同一份文件。
     *
     * <p>为什么需要别名：MTR 一辆自定义车同时挂着三个 id —— {@code mtr_custom_resources.json}
     * 里的 key（{@code xml6125jevd0c_normal}）、注册 id（{@code mtr_custom_train_<key>}），
     * 以及渲染器报的 {@code baseTrainType}（{@code train_12_2}，由 MTR 自己生成）。编辑器从
     * 注册表打开时手里只有前者，作者按后者命名的文件就找不到 —— 所以调用方把其余 id 一并传进来。
     */
    static List<Beam> read(final String vehicleId, final String... aliases) {
        final List<String> ids = candidateIds(vehicleId, aliases);
        final List<String> names = namesFor(ids);
        final Path exported = locate(exportDir(), names);
        if (exported != null) {
            final List<Beam> beams = readFile(exported, vehicleId);
            if (beams != null) {
                LteLog.LOGGER.info("[lte] beam pack: {} <- {} beams={}", fileName(vehicleId), exported,
                        Integer.valueOf(beams.size()));
                return beams;
            }
        }
        final List<Beam> packed = readFromPacks(ids, names, vehicleId);
        if (packed != null) {
            return packed;
        }
        final Path legacyDir = configDir();
        if (!sameDir(legacyDir, exportDir())) {
            final Path legacy = locate(legacyDir, names);
            if (legacy != null) {
                final List<Beam> old = readFile(legacy, vehicleId);
                if (old != null) {
                    LteLog.LOGGER.info("[lte] beam pack: {} <- {} beams={} (legacy)", fileName(vehicleId),
                            legacy, Integer.valueOf(old.size()));
                    return old;
                }
            }
        }
        logMiss(ids, names);
        return List.of();
    }

    /**
     * 不静默：一个车型在四处都找不到时也要留一行，并且把「包在磁盘上有、但没在资源包列表里
     * 启用」这件事单独说清楚 —— 那是玩家最容易自己修好的一类。
     */
    private static void logMiss(final List<String> ids, final List<String> names) {
        LteLog.LOGGER.info("[lte] beam pack: no file for id={} (tried {}; packs enabled={})",
                ids.isEmpty() ? "?" : ids.get(0), names, Integer.valueOf(enabledProfileCount()));
        warnIfPackPresentButDisabled();
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
     * 已加载资源包里的这个车型：先扫全部包的 {@code assets/lte/beam3D/<id>.json}，再扫
     * {@code assets/<ns>/beam3D/}，最后才是旧路径 {@code assets/<ns>/lte_beams/}。
     *
     * @return null = 所有已加载的包都没有这个文件
     */
    private static List<Beam> readFromPacks(final List<String> ids, final List<String> names,
                                            final String vehicleId) {
        final List<ResourcePack> packs = enabledPacks();
        if (packs.isEmpty()) {
            return null;
        }
        final List<Beam> fresh = scanPacks(packs, ids, names, PACK_DIR, vehicleId);
        if (fresh != null) {
            return fresh;
        }
        return scanPacks(packs, ids, names, LEGACY_PACK_DIR, vehicleId);
    }

    /**
     * 一个目录类别扫一遍，分两轮：
     * <ol>
     *   <li>命名空间 {@code lte}（也就是 {@code assets/lte/beam3D/}）—— 约定的主路径；</li>
     *   <li>包里其它命名空间（{@code assets/<ns>/beam3D/}）。</li>
     * </ol>
     * 第一轮命中就不再进第二轮，所以「{@code assets/lte/} 的写法」永远压过「作者自己的
     * 命名空间」。同一轮里按包顺序低 → 高遍历，后命中的覆盖前面的，与 Minecraft
     * 「栈顶包赢」一致。目录名同时试 {@code beam3D} 和它的小写形态。
     *
     * @return null = 一个包都没有这个文件
     */
    private static List<Beam> scanPacks(final List<ResourcePack> packs, final List<String> ids,
                                        final List<String> names, final String dir,
                                        final String vehicleId) {
        final List<Beam> preferred = scanPacks(packs, ids, names, dir, vehicleId, true);
        if (preferred != null) {
            return preferred;
        }
        return scanPacks(packs, ids, names, dir, vehicleId, false);
    }

    /**
     * @param onlyDefault true = 只看 {@code lte} 命名空间；false = 只看其它命名空间
     */
    private static List<Beam> scanPacks(final List<ResourcePack> packs, final List<String> ids,
                                        final List<String> names, final String dir,
                                        final String vehicleId, final boolean onlyDefault) {
        final String[] dirs = new String[] {dir, dir.toLowerCase(Locale.ROOT)};
        List<Beam> winner = null;
        String from = null;
        String hitId = null;
        for (final ResourcePack pack : packs) {
            for (final String namespace : namespacesOf(pack)) {
                if (onlyDefault != PACK_NAMESPACE.equalsIgnoreCase(namespace)) {
                    continue;
                }
                for (final String candidate : dirs) {
                    for (final String name : names) {
                        final InputSupplier<InputStream> supplier;
                        try {
                            supplier = pack.openRoot("assets", namespace, candidate, name);
                        } catch (Throwable t) {
                            continue;
                        }
                        final String json = readAll(supplier);
                        if (json == null || json.isBlank()) {
                            continue;
                        }
                        winner = parse(json, keyOf(vehicleId),
                                pack.getName() + "!assets/" + namespace + "/" + candidate + "/" + name);
                        from = pack.getName() + ":assets/" + namespace + "/" + candidate + "/" + name;
                        hitId = name;
                    }
                }
            }
        }
        if (winner != null) {
            LteLog.LOGGER.info("[lte] beam pack: {} <- {} beams={} (id={})", fileName(vehicleId), from,
                    Integer.valueOf(winner.size()), hitId);
        }
        return winner;
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

    /** 这个包里值得找的命名空间：{@link #PACK_NAMESPACE}（{@code lte}）排最前，其余按字母序。 */
    private static Set<String> namespacesOf(final ResourcePack pack) {
        final Set<String> out = new LinkedHashSet<>();
        out.add(PACK_NAMESPACE);
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
            // 包不报命名空间（老/自建包）：只试默认的那一个
        }
        return out;
    }

    /** 已加载的资源包，低 → 高优先级（{@code getEnabledProfiles} 的顺序就是栈序）。 */
    private static List<ResourcePack> enabledPacks() {
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

    private static String readAll(final InputSupplier<InputStream> supplier) {
        if (supplier == null) {
            return null;
        }
        try (InputStream in = supplier.get()) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

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
