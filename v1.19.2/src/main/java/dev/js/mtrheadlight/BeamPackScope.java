package dev.js.mtrheadlight;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.js.mtrheadlight.VehicleLightOverrides.Beam;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourceType;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 一个已启用资源包「自己声明了哪些车型」：把包内
 * {@code assets/mtr/mtr_custom_resources.json} 的 {@code custom_trains} 展开成一张 id 表。
 *
 * <h2>它回答的唯一一个问题</h2>
 * 包级共用配置 {@code assets/lte/beam3D/_shared.json} 能不能对这个车型生效？
 * 规则是<b>同包</b>：只有当这个车型是<b>这个包</b>声明的，才用它的 {@code _shared.json}；
 * 否则忽略（否则一个包里写的两束车头灯会跑到别的包、甚至内置车身上去）。
 *
 * <h2>一辆车到底有几个 id（三个都要认）</h2>
 * <pre>
 *   custom_trains 的 key            xml6125jevd0c_normal
 *   MTR 注册 id                     mtr_custom_train_xml6125jevd0c_normal
 *   渲染器报的 baseTrainType        train_12_2
 * </pre>
 * 前两个是字面量，直接比对即可。第三个是 MTR 在
 * {@code CustomResources#reload} 里用
 * {@code String.format("%s_%s_%s", transport_mode, length, width)} 从<b>车型自己的
 * {@code model_properties}</b> 生成出来的（见 {@link #generatedId}），所以在包里读那份
 * json 就能把 {@code train_12_2} 反查回 5 个涂装 key —— 不需要运行时注册表，也不需要启动游戏。
 *
 * <h2>涂装包（只写 texture_id + base_train_type）</h2>
 * 这类条目的运行时 id 就是它自己声明的 {@code base_train_type}（MTR 对 {@code base_}
 * 开头的值还有一次 {@code base_→train_} 的替换），两个形态都记下来。
 *
 * <h2>代价</h2>
 * 每个包只读一次 {@code mtr_custom_resources.json}；{@code model_properties} 只在
 * 「按 key / 注册 id / base_train_type 都没对上」时才读（懒算、按 key 缓存）。
 * 索引随资源包栈一起失效（{@link BeamPackWriter#invalidatePacks()}）。
 */
final class BeamPackScope {

    /** 包级共用配置的文件名（放在 {@code beam3D/} 里，和其他按车文件同目录）。 */
    static final String SHARED_FILE = "_shared.json";

    /** MTR 唯一读取的自定义车描述文件（包内相对路径）。 */
    static final String RESOURCES = "assets/mtr/mtr_custom_resources.json";

    /** 找 {@code _shared.json} 的目录，按优先级。 */
    private static final String[] DIRS = {
            BeamPackWriter.PACK_DIR,
            BeamPackWriter.PACK_DIR.toLowerCase(Locale.ROOT),
            BeamPackWriter.LEGACY_PACK_DIR,
    };

    private static final Object LOCK = new Object();
    /** 当前包栈的索引；null = 还没建 / 已失效。 */
    private static List<BeamPackScope> INDEX;

    /** 一个 {@code custom_trains} 条目。 */
    private static final class Entry {
        final String key;
        /** {@code base_train_type}（涂装包才有），没有就是 null。 */
        final String base;
        /** {@code model_properties} 资源路径（{@code ns:path}），没有就是 null。 */
        final String properties;

        Entry(final String key, final String base, final String properties) {
            this.key = key;
            this.base = base;
            this.properties = properties;
        }
    }

    /** {@link #shared()} 的结果：哪个路径、几束。 */
    static final class Shared {
        final String path;
        final List<Beam> beams;

        Shared(final String path, final List<Beam> beams) {
            this.path = path;
            this.beams = beams;
        }
    }

    final ResourcePack pack;
    /** 日志里的包名（{@code pack.getName()}）。 */
    final String label;

    private final List<Entry> entries = new ArrayList<>();
    /** key → 由 {@code model_properties} 生成的运行时 id；值可为 null（= 算过但没有）。 */
    private final Map<String, String> modelIds = new HashMap<>();
    private Shared shared;
    private boolean sharedTried;

    private BeamPackScope(final ResourcePack pack) {
        this.pack = pack;
        String name;
        try {
            name = pack.getName();
        } catch (Throwable t) {
            name = "pack@" + Integer.toHexString(System.identityHashCode(pack));
        }
        this.label = name == null || name.isBlank() ? "?" : name;
    }

    // ---------------------------------------------------------------------------------------------
    // 索引
    // ---------------------------------------------------------------------------------------------

    /** 当前包栈里每个包的作用域（低 → 高优先级，与 {@code getEnabledProfiles} 同序）。 */
    static List<BeamPackScope> index() {
        synchronized (LOCK) {
            if (INDEX != null) {
                return INDEX;
            }
        }
        final List<BeamPackScope> out = of(BeamPackWriter.enabledPacks());
        synchronized (LOCK) {
            INDEX = out;
        }
        return INDEX;
    }

    /**
     * 一份包列表 → 每个包的作用域（不缓存）。{@link #index()} 是它的缓存版本；
     * 离线检查（{@code v1.20.1/temp/beamcheck}）直接喂一个文件夹包进来，无需启动游戏。
     */
    static List<BeamPackScope> of(final List<ResourcePack> packs) {
        final List<BeamPackScope> out = new ArrayList<>();
        if (packs == null) {
            return Collections.unmodifiableList(out);
        }
        for (final ResourcePack pack : packs) {
            final BeamPackScope scope = new BeamPackScope(pack);
            scope.load();
            if (!scope.entries.isEmpty()) {
                out.add(scope);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** 包栈变了（F3+T / 装卸包）：丢掉索引与解析结果。 */
    static void invalidate() {
        synchronized (LOCK) {
            INDEX = null;
        }
    }

    /** 读这个包的 {@code mtr_custom_resources.json}。读不到 / 没有 custom_trains = 不声明任何车。 */
    void load() {
        final String json = readAsset(this.pack, RESOURCES);
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            final JsonElement rootEl = JsonParser.parseString(json);
            if (rootEl == null || !rootEl.isJsonObject()) {
                return;
            }
            final JsonElement trainsEl = rootEl.getAsJsonObject().get("custom_trains");
            if (trainsEl == null || !trainsEl.isJsonObject()) {
                return;
            }
            for (final Map.Entry<String, JsonElement> e : trainsEl.getAsJsonObject().entrySet()) {
                final String key = e.getKey();
                if (key == null || key.isBlank() || e.getValue() == null
                        || !e.getValue().isJsonObject()) {
                    continue;
                }
                final JsonObject train = e.getValue().getAsJsonObject();
                this.entries.add(new Entry(key, str(train, "base_train_type"),
                        str(train, "model_properties")));
            }
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] beam pack: cannot read custom_trains of {}", this.label, t);
        }
    }

    /** 这个包里声明的车型数（诊断用）。 */
    int size() {
        return this.entries.size();
    }

    // ---------------------------------------------------------------------------------------------
    // 这个 id 属于哪些 key
    // ---------------------------------------------------------------------------------------------

    /**
     * {@code id} 在本包里对应的 {@code custom_trains} key，按声明顺序；空 = 不归本包。
     *
     * <p>两轮：先比字面量（key / 注册 id / base_train_type），都没有才去读
     * {@code model_properties} 反算 MTR 生成的运行时 id。
     */
    synchronized List<String> keysFor(final String id) {
        if (id == null || id.isBlank() || this.entries.isEmpty()) {
            return Collections.emptyList();
        }
        final String lower = id.trim().toLowerCase(Locale.ROOT);
        final List<String> fast = new ArrayList<>();
        for (final Entry entry : this.entries) {
            if (matches(entry, lower)) {
                fast.add(entry.key);
            }
        }
        if (!fast.isEmpty()) {
            return fast;
        }
        final List<String> slow = new ArrayList<>();
        for (final Entry entry : this.entries) {
            final String generated = modelIdOf(entry.key);
            if (generated != null && generated.equals(lower)) {
                slow.add(entry.key);
            }
        }
        return slow;
    }

    private static boolean matches(final Entry entry, final String lower) {
        if (entry.key.toLowerCase(Locale.ROOT).equals(lower)) {
            return true;
        }
        if ((BeamPackWriter.ID_PREFIX + entry.key).toLowerCase(Locale.ROOT).equals(lower)) {
            return true;
        }
        return entry.base != null && entry.base.toLowerCase(Locale.ROOT).equals(lower);
    }

    /** {@code key} 声明的运行时 id：{@code base_train_type}，否则是 {@code model_properties} 生成的。 */
    synchronized String runtimeIdOf(final String key) {
        for (final Entry entry : this.entries) {
            if (entry.key.equals(key)) {
                if (entry.base != null && !entry.base.isBlank()) {
                    return entry.base;
                }
                break;
            }
        }
        return modelIdOf(key);
    }

    /**
     * MTR 给这个车型生成的运行时 id：{@code String.format("%s_%s_%s", transport_mode, length, width)}
     * （{@code CustomResources#reload} 里就是这么写的，{@code TRAIN/12/2} → {@code train_12_2}）。
     * 没有 {@code model_properties} 或读不到时 null。
     */
    synchronized String modelIdOf(final String key) {
        if (this.modelIds.containsKey(key)) {
            return this.modelIds.get(key);
        }
        String id = null;
        for (final Entry entry : this.entries) {
            if (!entry.key.equals(key)) {
                continue;
            }
            if (entry.properties != null && !entry.properties.isBlank()) {
                id = generatedId(readAsset(this.pack, assetPath(entry.properties)));
            }
            break;
        }
        this.modelIds.put(key, id);
        return id;
    }

    static String generatedId(final String propertiesJson) {
        if (propertiesJson == null || propertiesJson.isBlank()) {
            return null;
        }
        try {
            final JsonElement rootEl = JsonParser.parseString(propertiesJson);
            if (rootEl == null || !rootEl.isJsonObject()) {
                return null;
            }
            final JsonObject props = rootEl.getAsJsonObject();
            final String mode = str(props, "transport_mode");
            final JsonElement length = props.get("length");
            final JsonElement width = props.get("width");
            if (mode == null || mode.isBlank() || length == null || width == null) {
                return null;
            }
            return (mode.toLowerCase(Locale.ROOT) + "_" + length.getAsInt() + "_" + width.getAsInt())
                    .toLowerCase(Locale.ROOT);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 包级共用配置
    // ---------------------------------------------------------------------------------------------

    /**
     * 本包的 {@code _shared.json}（只读一次）。找的顺序与其他按车文件一致：
     * {@code assets/lte/beam3D/} → {@code assets/<ns>/beam3D/} → {@code assets/<ns>/lte_beams/}。
     * 没有就 null。
     */
    synchronized Shared shared() {
        if (this.sharedTried) {
            return this.shared;
        }
        this.sharedTried = true;
        final Set<String> namespaces = new LinkedHashSet<>();
        namespaces.add(BeamPackWriter.PACK_NAMESPACE);
        try {
            for (final String namespace : this.pack.getNamespaces(ResourceType.CLIENT_RESOURCES)) {
                if (namespace != null && !namespace.isBlank()
                        && !BeamPackWriter.PACK_NAMESPACE.equalsIgnoreCase(namespace)) {
                    namespaces.add(namespace);
                }
            }
        } catch (Throwable ignored) {
            // 包不报命名空间：只试默认的那个
        }
        for (final String dir : DIRS) {
            for (final String namespace : namespaces) {
                final String path = "assets/" + namespace + "/" + dir + "/" + SHARED_FILE;
                final String json = readAsset(this.pack, path);
                if (json == null || json.isBlank()) {
                    continue;
                }
                final List<Beam> beams = BeamPackWriter.parse(json, SHARED_FILE, this.label + "!" + path);
                if (beams.isEmpty()) {
                    // 文件在、但一束都没有：合法（= 这个包的车不要光柱），同样算命中，别再往下找。
                    LteLog.LOGGER.info("[lte] beam pack: {} declares 0 beams", this.label + "!" + path);
                }
                this.shared = new Shared(this.label + "!" + path, beams);
                return this.shared;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------
    // 读包内文件
    // ---------------------------------------------------------------------------------------------

    /** {@code assets/<ns>/<path>}；{@code ns:path} 没有扩展名时补 {@code .json}。 */
    static String assetPath(final String resource) {
        if (resource == null || resource.isBlank()) {
            return null;
        }
        final int colon = resource.indexOf(':');
        final String namespace = colon < 0 ? "mtr" : resource.substring(0, colon);
        String path = colon < 0 ? resource : resource.substring(colon + 1);
        if (namespace.isBlank() || path.isBlank()) {
            return null;
        }
        final int slash = path.lastIndexOf('/');
        if (path.lastIndexOf('.') <= slash) {
            path = path + ".json";
        }
        return "assets/" + namespace + "/" + path;
    }

    /**
     * 读包内一个相对路径的内容（{@code assets/.../x.json}）。
     *
     * <p>按<b>路径段</b>走 {@link ResourcePack#openRoot}：{@code beam3D} 里的大写 D 不是合法
     * Identifier，走 {@code getResource} 永远读不到（见 {@link BeamPackWriter}）。
     *
     * @return 内容；文件不存在 / 读不了时 null
     */
    static String readAsset(final ResourcePack pack, final String relative) {
        if (pack == null || relative == null || relative.isBlank()) {
            return null;
        }
        // 1.19.2 的 ResourcePack#openRoot 收的是**一整条相对路径**（不是 1.19.4 的
        // String... 分段），所以这里只把反斜杠和开头的斜杠规整掉，不再 split。
        final String path = relative.replace('\\', '/').replaceFirst("^/+", "");
        try (InputStream in = pack.openRoot(path)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String str(final JsonObject json, final String key) {
        final JsonElement element = json == null ? null : json.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    @Override
    public String toString() {
        return this.label + " (" + this.entries.size() + " train(s))";
    }
}
