package dev.js.mtrheadlight;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.js.mtrheadlight.VehicleLightOverrides.Beam;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code config/lte_beams/<车型 id>.json} —— 3D 编辑器「导出」写的那一份，一车一文件。
 *
 * <h2>为什么不是 {@code lte_vehicle_lights.json}</h2>
 * 老格式是「一个文件装所有车型」，适合手写和发布，但 3D 编辑器每次只编一个车型，
 * 一车一文件才能做到「点导出只覆盖这个车型、不碰别的」。落到资源包里时它就是
 * {@code assets/<namespace>/lte_beams/<车型 id>.json}，一个车型一个文件，diff 也干净。
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

    /** {@code config/lte_beams}。 */
    static final String CONFIG_DIR = "lte_beams";

    private static final int FORMAT_VERSION = 1;

    private static final String[] COMMENT = {
        "LTE 3D beam editor export - one vehicle per file.",
        "Place it at assets/<your_pack>/lte_beams/<vehicleId>.json inside a resource pack.",
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

    static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve(CONFIG_DIR);
    }

    /**
     * 车型 id → 文件名。id 可能带通配符 {@code *}、命名空间冒号或路径分隔符，
     * 统一换成 {@code _}；保留扩展名可读性（文件名本身就叫 {@code <id>.json}）。
     */
    static String fileName(final String vehicleId) {
        final String raw = vehicleId == null ? "unknown" : vehicleId.trim();
        final String safe = raw.replaceAll("[^A-Za-z0-9._-]", "_");
        return (safe.isEmpty() ? "unknown" : safe) + ".json";
    }

    static Path pathFor(final String vehicleId) {
        return dir().resolve(fileName(vehicleId));
    }

    /** 写一个车型的文件，返回落盘路径。调用方负责把异常变成界面提示。 */
    static Path write(final String vehicleId, final List<Beam> beams) throws Exception {
        final Path path = pathFor(vehicleId);
        Files.createDirectories(path.getParent());
        Files.writeString(path, toJson(vehicleId, beams), StandardCharsets.UTF_8);
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

    /**
     * 读回同一份文件（编辑器重新打开某车型时优先用它，而不是运行时层）。
     * 复用 {@link VehicleLightJson} 的解析器做校验，因此非法字段会被丢弃而不是让界面崩掉。
     *
     * @return 光束列表；文件不存在 / 读不懂 / 没有 beams 时返回空列表
     */
    static List<Beam> read(final String vehicleId) {
        final Path path = pathFor(vehicleId);
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        try {
            final JsonElement rootEl = JsonParser.parseString(
                    Files.readString(path, StandardCharsets.UTF_8));
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
                    GSON.toJson(wrapper), path.toString());
            if (!result.ok() || result.vehicles.isEmpty()) {
                return List.of();
            }
            return new ArrayList<>(result.vehicles.get(0).beams);
        } catch (Throwable t) {
            LteLog.LOGGER.warn("[lte] beam editor: cannot read {}", path, t);
            return List.of();
        }
    }
}
