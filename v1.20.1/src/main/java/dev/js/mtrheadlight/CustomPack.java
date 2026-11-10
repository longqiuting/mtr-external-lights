package dev.js.mtrheadlight;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.js.mtrheadlight.VehicleLightOverrides.Beam;
import mtr.client.DoorAnimationType;
import mtr.client.DynamicTrainModel;
import mtr.client.TrainClientRegistry;
import mtr.client.TrainProperties;
import mtr.model.ModelTrainBase;
import mtr.render.JonModelTrainRenderer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 一个「没被游戏加载」的 MTR 资源包：临时读它的 {@code mtr_custom_resources.json} 与模型，
 * 让 3D 编辑器能直接编辑里面的车型。
 *
 * <h2>搜索路径（与 MTR 一致）</h2>
 * MTR 只认 {@code mtr:mtr_custom_resources.json}，也就是包内
 * <b>{@code assets/mtr/mtr_custom_resources.json}</b>（{@code CustomResources} 的字符串常量里
 * 只有这一个路径）。本类先按这个规范路径读；读不到再在包里扫任意
 * {@code &#42;/mtr_custom_resources.json}，兼容把文件放在包根 / 自定义命名空间的老包。
 *
 * <h2>模型</h2>
 * 条目里的 {@code model} / {@code model_properties} 是<b>资源路径字符串</b>
 * （如 {@code mypack:models/train/sp1900.json}），映射成包内
 * {@code assets/<ns>/models/train/sp1900.json}。拿到 json 后直接
 * {@code new DynamicTrainModel(modelJson, propsJson, STANDARD)} —— 这个构造器是 public 的，
 * 所以<b>不需要把包注册进 MTR</b> 就能在编辑器里画出车。
 * 贴图同理：{@code texture_id + ".png"} 从包里读出来，用
 * {@link NativeImageBackedTexture} 挂到 {@link TextureManager} 上（合成 id
 * {@code mtrlte:import/<hash>}），渲染时直接用这个 id。
 *
 * <h2>导出</h2>
 * 文件夹包：直接写进 <b>{@code <包>/assets/<ns>/lte_beams/<车型id>.json}</b>（自动建目录）。
 * zip：<b>不改 zip</b>（MTR/Minecraft 都不支持原地改），改写到
 * {@code config/lte_beams/}，并告诉用户解压后放进包里的哪个相对路径。
 */
final class CustomPack {

    /** MTR 唯一读取的自定义车描述文件。 */
    static final String CANONICAL = "assets/mtr/mtr_custom_resources.json";
    /** 扫描用户放包的地方：{@code <game>/resourcepacks} 与 {@code config/lte_packs}。 */
    private static final String PACKS_DIR = "lte_packs";
    /** 包里找一个资源时需要的前缀。 */
    private static final String BEAM_DIR = "lte_beams";

    private static int ids;

    private final Path root;
    private final boolean zip;
    /** 包里自定义车 json 的实际相对路径（{@link #CANONICAL} 或扫出来的那个）。 */
    private final String resources;
    /** 名字（界面里显示用）。 */
    final String label;
    /** 包自带语言表（{@code assets/<ns>/lang/*.json} 合并后）：未加载的包只能从这里解析 name。 */
    private final Map<String, String> lang = new LinkedHashMap<>();
    private final List<Train> trains = new ArrayList<>();
    private String problem = "";

    private CustomPack(final Path root, final boolean zip, final String resources) {
        this.root = root;
        this.zip = zip;
        this.resources = resources;
        this.label = root.getFileName() == null ? root.toString()
                : root.getFileName().toString();
    }

    // ---------------------------------------------------------------------------------------------
    // 打开
    // ---------------------------------------------------------------------------------------------

    /** 打开一个包（zip 或文件夹）。失败时抛异常，消息能直接显示在界面上。 */
    static CustomPack open(final Path path) throws Exception {
        if (path == null) {
            throw new IllegalArgumentException("path");
        }
        final Path absolute = path.toAbsolutePath().normalize();
        final boolean isZip = Files.isRegularFile(absolute)
                && absolute.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip");
        if (!isZip && !Files.isDirectory(absolute)) {
            throw new IOException("找不到资源包：" + absolute);
        }
        final String resources = findResources(absolute, isZip);
        if (resources == null) {
            throw new IOException("这个包里没有 mtr_custom_resources.json（已找 " + CANONICAL + " 与任意层的同名文件）");
        }
        final CustomPack pack = new CustomPack(absolute, isZip, resources);
        pack.load();
        if (pack.trains.isEmpty()) {
            throw new IOException("mtr_custom_resources.json 里没有 custom_trains 条目");
        }
        return pack;
    }

    /** 包根 → 自定义车 json 的相对路径；找不到返回 null。 */
    private static String findResources(final Path root, final boolean zip) {
        if (!zip && Files.isRegularFile(root.resolve(CANONICAL))) {
            return CANONICAL;
        }
        if (zip) {
            try (ZipFile file = new ZipFile(root.toFile())) {
                if (file.getEntry(CANONICAL) != null) {
                    return CANONICAL;
                }
                ZipEntry best = null;
                for (final ZipEntry entry : java.util.Collections.list(file.entries())) {
                    final String name = entry.getName();
                    if (entry.isDirectory() || !name.endsWith("/mtr_custom_resources.json")) {
                        continue;
                    }
                    if (best == null || name.length() < best.getName().length()) {
                        best = entry;
                    }
                }
                return best == null ? null : best.getName();
            } catch (Throwable t) {
                return null;
            }
        }
        try (Stream<Path> walk = Files.walk(root, 4)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName() != null
                            && "mtr_custom_resources.json".equals(p.getFileName().toString()))
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .sorted((a, b) -> Integer.compare(a.length(), b.length()))
                    .findFirst().orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 读 json、把 custom_trains 展开成 {@link Train}。 */
    private void load() throws Exception {
        loadLang();
        final String json = readText(this.resources);
        if (json == null) {
            throw new IOException("读不到 " + this.resources);
        }
        final JsonElement rootEl = JsonParser.parseString(json);
        if (rootEl == null || !rootEl.isJsonObject()) {
            throw new IOException(this.resources + " 不是 JSON 对象");
        }
        final JsonElement trainsEl = rootEl.getAsJsonObject().get("custom_trains");
        if (trainsEl == null || !trainsEl.isJsonObject()) {
            return;
        }
        for (final Map.Entry<String, JsonElement> entry : trainsEl.getAsJsonObject().entrySet()) {
            final String key = entry.getKey();
            if (key == null || key.isBlank() || entry.getValue() == null
                    || !entry.getValue().isJsonObject()) {
                continue;
            }
            final JsonObject train = entry.getValue().getAsJsonObject();
            this.trains.add(new Train(this, key, train));
        }
        this.trains.sort((a, b) -> a.id.compareToIgnoreCase(b.id));
    }

    // ---------------------------------------------------------------------------------------------
    // 读文件
    // ---------------------------------------------------------------------------------------------

    byte[] readBytes(final String relative) {
        if (relative == null || relative.isBlank()) {
            return null;
        }
        final String clean = relative.replace('\\', '/').replaceFirst("^/+", "");
        if (this.zip) {
            try (ZipFile file = new ZipFile(this.root.toFile())) {
                ZipEntry entry = file.getEntry(clean);
                if (entry == null) {
                    entry = file.getEntry("/" + clean);
                }
                if (entry == null) {
                    return null;
                }
                try (InputStream in = file.getInputStream(entry)) {
                    return in.readAllBytes();
                }
            } catch (Throwable t) {
                LteLog.LOGGER.warn("[lte] import: cannot read {} from {}", clean, this.root, t);
                return null;
            }
        }
        try {
            final Path path = this.root.resolve(clean).normalize();
            if (!path.startsWith(this.root) || !Files.isRegularFile(path)) {
                return null;
            }
            return Files.readAllBytes(path);
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] import: cannot read {} from {}", clean, this.root, t);
            return null;
        }
    }

    String readText(final String relative) {
        final byte[] bytes = readBytes(relative);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    /** 资源路径（{@code ns:path}）→ 包内文件路径 {@code assets/<ns>/<path>}；非法时返回 null。 */
    static String toAssetPath(final String resource) {
        if (resource == null || resource.isBlank()) {
            return null;
        }
        final int colon = resource.indexOf(':');
        final String namespace = colon < 0 ? "mtr" : resource.substring(0, colon);
        final String path = colon < 0 ? resource : resource.substring(colon + 1);
        if (namespace.isBlank() || path.isBlank()) {
            return null;
        }
        return "assets/" + namespace + "/" + path;
    }

    /** 资源路径的命名空间（{@code mypack:textures/...} → {@code mypack}）。 */
    static String namespaceOf(final String resource) {
        if (resource == null || resource.isBlank()) {
            return null;
        }
        final int colon = resource.indexOf(':');
        return colon <= 0 ? null : resource.substring(0, colon);
    }

    // ---------------------------------------------------------------------------------------------
    // 车型
    // ---------------------------------------------------------------------------------------------

    List<Train> trains() {
        return this.trains;
    }

    Train train(final String id) {
        if (id == null) {
            return null;
        }
        for (final Train train : this.trains) {
            if (train.id.equalsIgnoreCase(id)) {
                return train;
            }
        }
        return null;
    }

    String problem() {
        return this.problem;
    }

    /**
     * 扫包里的 {@code assets/<ns>/lang/{zh_cn,en_us}.json} 并合并成一张表。
     * 用途：包里 {@code name} 写的是语言键时，<b>未加载</b>的包在游戏语言表里查不到，
     * 只能从包自己的中文/英文语言文件里解析（先 zh_cn 后 en_us，和玩家看到的顺序一致）。
     */
    private void loadLang() {
        final java.util.LinkedHashSet<String> namespaces = new java.util.LinkedHashSet<>();
        namespaces.add("mtr");
        try {
            if (this.zip) {
                try (ZipFile file = new ZipFile(this.root.toFile())) {
                    for (final ZipEntry entry : java.util.Collections.list(file.entries())) {
                        final String name = entry.getName();
                        if (entry.isDirectory() || !name.startsWith("assets/") || !name.endsWith(".json")) {
                            continue;
                        }
                        final int at = name.indexOf("/lang/");
                        if (at > 7) {
                            namespaces.add(name.substring(7, at));
                        }
                    }
                }
            } else {
                try (Stream<Path> walk = Files.walk(this.root, 5)) {
                    walk.filter(Files::isRegularFile).forEach(path -> {
                        final String name = this.root.relativize(path).toString().replace('\\', '/');
                        if (!name.startsWith("assets/") || !name.endsWith(".json")) {
                            return;
                        }
                        final int at = name.indexOf("/lang/");
                        if (at > 7) {
                            namespaces.add(name.substring(7, at));
                        }
                    });
                }
            }
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] import: cannot scan lang files in {}", this.root, t);
        }
        for (final String namespace : namespaces) {
            readLang(namespace, "zh_cn");
            readLang(namespace, "en_us");
        }
    }

    private void readLang(final String namespace, final String locale) {
        final String json = readText("assets/" + namespace + "/lang/" + locale + ".json");
        if (json == null) {
            return;
        }
        try {
            final JsonElement rootEl = JsonParser.parseString(json);
            if (rootEl == null || !rootEl.isJsonObject()) {
                return;
            }
            for (final Map.Entry<String, JsonElement> entry : rootEl.getAsJsonObject().entrySet()) {
                final JsonElement value = entry.getValue();
                if (value != null && value.isJsonPrimitive()) {
                    this.lang.putIfAbsent(entry.getKey(), value.getAsString());
                }
            }
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] import: cannot parse lang {}/{}", namespace, locale);
        }
    }

    /**
     * 包内 {@code name} → 显示名：先查包自带语言表（游戏没加载这个包，查不到），
     * 再退回游戏语言表 / 原文。和 {@link TrainNames#of} 是同一套规则。
     */
    String resolveName(final String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        final String fromPack = this.lang.get(raw);
        if (fromPack != null && !fromPack.isBlank()) {
            return fromPack;
        }
        return TrainNames.resolveRaw(raw);
    }

    /** 释放包里所有动态贴图（换包 / 关编辑器时）。zip 句柄是每次按需打开的，没有要关的。 */
    void release() {
        for (final Train train : this.trains) {
            train.release();
        }
    }

    /**
     * 包里的一个车型。<b>id 与 MTR 注册时一致</b>：{@code mtr_custom_train_<key>}，
     * 这样导入的车型和「已加载的同名车」在列表 / 导出文件名上天然对齐。
     */
    static final class Train {
        final CustomPack pack;
        /** json 里的 key。 */
        final String jsonKey;
        /** MTR 注册 id。 */
        final String id;
        /** json 里的 name（已按语言表解析）；没有 name 时等于 key，与 MTR 的默认值一致。 */
        final String name;
        /** json 的 {@code door_animation_type}（影响模型里门的摆放，光束编辑用不到，但保持一致）。 */
        final DoorAnimationType doorAnimationType;
        final String baseTrainType;
        final String textureId;
        final String model;
        final String modelProperties;
        /** 导出到 {@code assets/<namespace>/lte_beams/} 用的命名空间。 */
        final String namespace;

        private ModelTrainBase model_;
        private DynamicTrainModel dynamic;
        private boolean modelTried;
        private Identifier texture;
        private boolean textureTried;
        private String error = "";

        Train(final CustomPack pack, final String jsonKey, final JsonObject json) {
            this.pack = pack;
            this.jsonKey = jsonKey;
            this.id = TrainNames.ID_PREFIX + jsonKey;
            this.name = pack.resolveName(str(json, "name", jsonKey));
            this.doorAnimationType = doorAnimation(str(json, "door_animation_type", ""));
            this.baseTrainType = str(json, "base_train_type", "");
            this.textureId = str(json, "texture_id", "");
            this.model = str(json, "model", "");
            this.modelProperties = str(json, "model_properties", "");
            this.namespace = preferNamespace(namespaceOf(this.textureId),
                    namespaceOf(this.model), namespaceOf(this.modelProperties));
        }

        /**
         * 导出用的命名空间：优先贴图 / 模型的命名空间，跳过 {@code minecraft}
         * （那是原版资源，不该往里塞自定义文件），都没有就用 {@code mtr}。
         */
        private static String preferNamespace(final String... candidates) {
            for (final String candidate : candidates) {
                if (candidate != null && !candidate.isBlank()
                        && !"minecraft".equalsIgnoreCase(candidate)) {
                    return candidate;
                }
            }
            return "mtr";
        }

        /** {@code door_animation_type}：MTR 用 EnumHelper 解析，这里等价地按枚举名取，失败退回 STANDARD。 */
        private static DoorAnimationType doorAnimation(final String raw) {
            if (raw != null && !raw.isBlank()) {
                try {
                    return DoorAnimationType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
                } catch (Throwable ignored) {
                    // 包写了不认识的名字：不影响光束编辑
                }
            }
            return DoorAnimationType.STANDARD;
        }

        private static String str(final JsonObject json, final String key, final String fallback) {
            final JsonElement element = json.get(key);
            return element != null && element.isJsonPrimitive() ? element.getAsString() : fallback;
        }

        /** 包里这个车型文件的相对路径：有扩展名就原样用，没有才补 {@code .json}。 */
        private static String withExtension(final String path, final String defaultExtension) {
            if (path == null) {
                return null;
            }
            final int slash = path.lastIndexOf('/');
            final int dot = path.lastIndexOf('.');
            if (dot > slash) {
                return path;
            }
            return path + defaultExtension;
        }

        /** 模型 json（{@code .bbmodel} 或 {@code .json}，MTR 两种都直接喂给 DynamicTrainModel）。 */
        String modelPath() {
            return withExtension(toAssetPath(this.model), ".json");
        }

        String propertiesPath() {
            return withExtension(toAssetPath(this.modelProperties), ".json");
        }

        /** 贴图 png 在包内的相对路径。 */
        String texturePath() {
            return withExtension(toAssetPath(this.textureId), ".png");
        }

        /**
         * 解析好的模型（渲染用）；拿不到时 null（画包围盒）。
         *
         * <p>两种包：
         * <ul>
         *   <li>整车包：{@code model} + {@code model_properties} 都在 → 现场构造
         *       {@link DynamicTrainModel}（MTR 自己的条件也是这两个字段都在才建模）。</li>
         *   <li>涂装包：只写 {@code texture_id} + {@code base_train_type}（实测
         *       "British Rail Class 80x" 就是这种）→ 复用<b>基础车型</b>的模型，
         *       贴图仍用包里那张 —— 和 MTR 注册时 `new JonModelTrainRenderer(baseModel, 本包texture)` 一致。</li>
         * </ul>
         */
        ModelTrainBase model() {
            resolve();
            return this.model_;
        }

        /** 布局用（{@link VehicleLights#layoutFor} 只吃 DynamicTrainModel）；不是动态模型时 null。 */
        DynamicTrainModel dynamic() {
            resolve();
            return this.dynamic;
        }

        private void resolve() {
            if (this.modelTried) {
                return;
            }
            this.modelTried = true;
            if (this.model.isBlank() || this.modelProperties.isBlank()) {
                resolveBaseModel();
                return;
            }
            try {
                final String modelJson = this.pack.readText(modelPath());
                final String propsJson = this.pack.readText(propertiesPath());
                if (modelJson == null || propsJson == null) {
                    this.error = "model json missing (" + modelPath() + ")";
                    resolveBaseModel();
                    return;
                }
                final DynamicTrainModel built = new DynamicTrainModel(
                        JsonParser.parseString(modelJson).getAsJsonObject(),
                        JsonParser.parseString(propsJson).getAsJsonObject(),
                        this.doorAnimationType);
                this.model_ = built;
                this.dynamic = built;
            } catch (Throwable t) {
                this.error = t.getClass().getSimpleName() + ": " + t.getMessage();
                LteLog.LOGGER.warn("[lte] import: cannot build model for {}", this.id, t);
                resolveBaseModel();
            }
        }

        /** 涂装包：模型来自 {@code base_train_type}（已加载的基础车型）。 */
        private void resolveBaseModel() {
            if (this.baseTrainType == null || this.baseTrainType.isBlank()) {
                if (this.error.isEmpty()) {
                    this.error = "no model/model_properties and no base_train_type";
                }
                return;
            }
            try {
                final TrainProperties base = TrainClientRegistry.getTrainProperties(this.baseTrainType);
                if (base != null && base.renderer instanceof JonModelTrainRenderer jon
                        && jon.model != null) {
                    this.model_ = jon.model;
                    this.dynamic = jon.model instanceof DynamicTrainModel model ? model : null;
                    LteLog.LOGGER.info("[lte] import: {} uses the base model of {}",
                            this.id, this.baseTrainType);
                } else {
                    this.error = "base_train_type " + this.baseTrainType + " 没有可用模型";
                }
            } catch (Throwable t) {
                this.error = "base model: " + t.getClass().getSimpleName();
            }
        }

        /** 把包里的 png 挂到贴图管理器上；失败返回 null（预览退回透明贴图）。 */
        Identifier texture() {
            if (this.textureTried) {
                return this.texture;
            }
            this.textureTried = true;
            final String path = texturePath();
            if (path == null) {
                return null;
            }
            final byte[] png = this.pack.readBytes(path);
            if (png == null || png.length == 0) {
                this.error = this.error.isEmpty() ? "texture missing (" + path + ")" : this.error;
                return null;
            }
            try {
                final MinecraftClient client = MinecraftClient.getInstance();
                final TextureManager manager = client == null ? null : client.getTextureManager();
                if (manager == null) {
                    return null;
                }
                final Identifier id = new Identifier("mtrlte",
                        "import/" + (ids++) + "_" + Integer.toHexString(path.hashCode()));
                final NativeImage image = NativeImage.read(new ByteArrayInputStream(png));
                manager.registerTexture(id, new NativeImageBackedTexture(image));
                this.texture = id;
            } catch (Throwable t) {
                this.error = this.error.isEmpty() ? "texture: " + t : this.error;
                LteLog.LOGGER.warn("[lte] import: cannot load texture {} for {}", path, this.id, t);
            }
            return this.texture;
        }

        /** 释放动态贴图（关界面 / 换包时）。 */
        void release() {
            if (this.texture == null) {
                return;
            }
            try {
                final MinecraftClient client = MinecraftClient.getInstance();
                if (client != null) {
                    client.getTextureManager().destroyTexture(this.texture);
                }
            } catch (Throwable ignored) {
                // 贴图管理器已经关了
            }
            this.texture = null;
            this.textureTried = false;
        }

        String error() {
            return this.error;
        }

        /** 导出目标（包里）的相对路径：{@code assets/<ns>/lte_beams/<id>.json}。 */
        String beamPath() {
            return "assets/" + this.namespace + "/" + BEAM_DIR + "/" + BeamPackWriter.fileName(this.id);
        }

        /** 「车厂里看到的」一行：名字（+ 内部 id 便于对指令）。 */
        String label() {
            return this.name.equalsIgnoreCase(this.id) ? this.name
                    : this.name + " · " + this.id;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 导出 / 读回
    // ---------------------------------------------------------------------------------------------

    /** 导出结果：{@code insidePack=true} 表示已经写进包里（文件夹包）。 */
    static final class Export {
        final Path path;
        final boolean insidePack;
        /** zip 时给出「解压后放进哪里」的相对路径。 */
        final String packRelative;
        final int beams;

        Export(final Path path, final boolean insidePack, final String packRelative, final int beams) {
            this.path = path;
            this.insidePack = insidePack;
            this.packRelative = packRelative;
            this.beams = beams;
        }
    }

    /**
     * 写这个车型的 {@code lte_beams} 文件。文件夹包直接写进包；zip 写到 config 并返回相对路径。
     */
    Export writeBeams(final Train train, final List<Beam> beams) throws Exception {
        final String json = BeamPackWriter.toJson(train.id, beams);
        if (!this.zip) {
            final Path path = this.root.resolve(train.beamPath()).normalize();
            Files.createDirectories(path.getParent());
            Files.writeString(path, json, StandardCharsets.UTF_8);
            return new Export(path, true, train.beamPath(), beams.size());
        }
        final Path path = BeamPackWriter.write(train.id, beams);
        return new Export(path, false, train.beamPath(), beams.size());
    }

    /** 包里这个车型已有的光束（没有就空列表）。 */
    List<Beam> readBeams(final Train train) {
        final String json = readText(train.beamPath());
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return BeamPackWriter.parse(json, train.id, this.root + "!" + train.beamPath());
    }

    // ---------------------------------------------------------------------------------------------
    // 候选包
    // ---------------------------------------------------------------------------------------------

    /**
     * 用户「手边」的候选包：{@code <game>/resourcepacks/} 与 {@code config/lte_packs/}
     * 里所有带 {@code mtr_custom_resources.json} 的 zip / 文件夹（各扫两层，兼容
     * {@code resourcepacks/sub/foo.zip} 这种归档习惯）。
     */
    static List<Path> candidates() {
        final List<Path> out = new ArrayList<>();
        final Map<String, Path> seen = new LinkedHashMap<>();
        try {
            final FabricLoader loader = FabricLoader.getInstance();
            scan(loader.getGameDir().resolve("resourcepacks"), out, seen, 2);
            scan(loader.getConfigDir().resolve(PACKS_DIR), out, seen, 2);
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] import: cannot scan for packs", t);
        }
        return out;
    }

    private static void scan(final Path dir, final List<Path> out, final Map<String, Path> seen,
                             final int depth) {
        if (dir == null || depth < 0 || !Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> list = Files.list(dir)) {
            list.sorted().forEach(path -> {
                final String name = path.getFileName() == null ? ""
                        : path.getFileName().toString().toLowerCase(Locale.ROOT);
                if (Files.isDirectory(path)) {
                    // 文件夹包：自己就是包，或者再往里找一层
                    if (Files.isRegularFile(path.resolve(CANONICAL)) || hasNested(path)) {
                        add(out, seen, path);
                    } else {
                        scan(path, out, seen, depth - 1);
                    }
                } else if (name.endsWith(".zip") && zipHasResources(path)) {
                    add(out, seen, path);
                }
            });
        } catch (Throwable ignored) {
            // 目录不可读
        }
    }

    private static void add(final List<Path> out, final Map<String, Path> seen, final Path path) {
        final String key = path.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
        if (seen.put(key, path) == null) {
            out.add(path);
        }
    }

    private static boolean hasNested(final Path dir) {
        try (Stream<Path> walk = Files.walk(dir, 4)) {
            return walk.anyMatch(p -> Files.isRegularFile(p) && p.getFileName() != null
                    && "mtr_custom_resources.json".equals(p.getFileName().toString()));
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean zipHasResources(final Path zip) {
        try (ZipFile file = new ZipFile(zip.toFile())) {
            for (final ZipEntry entry : java.util.Collections.list(file.entries())) {
                if (!entry.isDirectory() && entry.getName().endsWith("mtr_custom_resources.json")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 不是合法 zip
        }
        return false;
    }

    /** 描述一行日志用。 */
    @Override
    public String toString() {
        return "CustomPack{" + this.root + (this.zip ? " (zip)" : " (dir)")
                + ", trains=" + this.trains.size() + "}";
    }
}
