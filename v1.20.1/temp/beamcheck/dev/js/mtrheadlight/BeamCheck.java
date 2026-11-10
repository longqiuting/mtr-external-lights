package dev.js.mtrheadlight;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Offline check for the id→key→file-name rules (no game, no Fabric loader). */
public final class BeamCheck {

    public static void main(final String[] args) throws Exception {
        expect("xmq6127agbevl3", BeamPackWriter.keyOf("mtr_custom_train_xmq6127agbevl3"));
        expect("xmq6127agbevl3.json", BeamPackWriter.fileName("mtr_custom_train_xmq6127agbevl3"));
        // 已经是 key 的输入原样通过
        expect("xmq6127agbevl3.json", BeamPackWriter.fileName("xmq6127agbevl3"));
        // 前缀大小写不敏感
        expect("xmq6127agbevl3", BeamPackWriter.keyOf("MTR_CUSTOM_TRAIN_xmq6127agbevl3"));
        // 内容里的 id = key
        final String json = BeamPackWriter.toJson(
                BeamPackWriter.keyOf("mtr_custom_train_xmq6127agbevl3"), List.of());
        if (!json.contains("\"id\": \"xmq6127agbevl3\"")) {
            throw new AssertionError("toJson id is not the key:\n" + json);
        }
        System.out.println("fileNames(mtr_custom_train_xmq6127agbevl3) = "
                + BeamPackWriter.fileNames("mtr_custom_train_xmq6127agbevl3"));

        // 导出落点：桌面
        System.out.println("exportDir = " + BeamPackWriter.exportDir());
        final Path target = BeamPackWriter.pathFor("mtr_custom_train_xmq6127agbevl3");
        System.out.println("pathFor  = " + target);
        if (!target.toString().endsWith("\\Desktop\\xmq6127agbevl3.json")) {
            throw new AssertionError("export path is not <desktop>\\<key>.json: " + target);
        }

        // 大小写不敏感：目录里只有 XMQ6127AGBEVL3.json，也要能找到
        final Path dir = Files.createTempDirectory("beamcheck");
        Files.writeString(dir.resolve("XMQ6127AGBEVL3.json"), "{}");
        final Path found = BeamPackWriter.locate(dir, List.of("xmq6127agbevl3.json"));
        System.out.println("locate(case-insensitive) = " + found);
        if (found == null || !found.getFileName().toString().equalsIgnoreCase("XMQ6127AGBEVL3.json")) {
            throw new AssertionError("case-insensitive lookup failed: " + found);
        }
        System.out.println("ALL OK");
        final Path pack = args.length > 0
                ? Path.of(args[0])
                : Path.of("C:/Users/AD/Desktop/PCL/.minecraft/versions/1.20.1-Fabric 0.19.3/resourcepacks/"
                        + "厦门金旅XML6125JEVD0C（连云港BRT）/assets/lte/beam3D/train_12_2.json");
        aliasAndPacket(pack);
        System.out.println("BEAM PACK CHECK OK");
    }

    /**
     * 别名 + 真实资源包文件：这是「编辑器/运行时读不到」的两个真实原因。
     *
     * <p>1) MTR 一辆自定义车有三个 id —— key（{@code xml6125jevd0c_normal}）、注册 id
     * （{@code mtr_custom_train_<key>}）、渲染器报的 {@code baseTrainType}（{@code train_12_2}）。
     * 作者按后者命名文件时，只拿 key 去找必然落空；
     * <p>2) 文件里的字段和我们的 schema 必须一一对上（{@code beams[]} 的
     * type/pos/rot/color/length/width/alpha），缺字段走默认值。
     */
    static void aliasAndPacket(final Path packFile) throws Exception {
        final List<String> ids = BeamPackWriter.candidateIds(
                "mtr_custom_train_xml6125jevd0c_normal", "train_12_2");
        final List<String> names = BeamPackWriter.namesFor(ids);
        System.out.println("candidateIds = " + ids);
        System.out.println("namesFor     = " + names);
        if (!names.contains("train_12_2.json")) {
            throw new AssertionError("alias id did not produce train_12_2.json: " + names);
        }
        if (!names.contains("xml6125jevd0c_normal.json")) {
            throw new AssertionError("key did not produce xml6125jevd0c_normal.json: " + names);
        }
        if (!"xml6125jevd0c_normal.json".equals(names.get(0))) {
            throw new AssertionError("the key must stay the first candidate: " + names);
        }
        if (names.indexOf("train_12_2.json") <= 0) {
            throw new AssertionError("the alias must come after the key: " + names);
        }

        if (packFile != null && Files.isRegularFile(packFile)) {
            final List<VehicleLightOverrides.Beam> beams = BeamPackWriter.parse(
                    Files.readString(packFile), "train_12_2", packFile.toString());
            System.out.println("pack beams = " + beams.size());
            for (final VehicleLightOverrides.Beam b : beams) {
                System.out.println("  " + b.type + " color=" + Integer.toHexString(b.color)
                        + " pos=" + java.util.Arrays.toString(b.pos)
                        + " rot=" + java.util.Arrays.toString(b.rot)
                        + " len=" + b.length + " w=" + b.width + " a=" + b.alpha);
            }
            if (beams.size() != 4) {
                throw new AssertionError("expected the 4 beams of the Xiamen pack, got " + beams.size());
            }
            if (beams.get(0).color == null || beams.get(0).pos == null || beams.get(0).length == null
                    || beams.get(0).width == null || beams.get(0).alpha == null) {
                throw new AssertionError("field mapping lost a value: " + beams.get(0));
            }
            // 模型空间 → 渲染端数值的反算：车长 14.112（半车 7.056），车头朝 -Z。
            // 文件里的 pos[2] = -7.056 = 车头端面 → inset 必须是 0；尾灯 6.068 → 0.988。
            final VehicleLights.Layout layout = new VehicleLights.Layout();
            layout.sectionLen = 14.112D;
            layout.frontSign = -1;
            layout.latCentre = 0.0D;
            for (final VehicleLightOverrides.Beam b : beams) {
                final VehicleLightOverrides.Resolved r = VehicleLightOverrides.Resolved.of(b, layout);
                System.out.println("  resolved " + r);
                if (r.inset < -0.05D || r.inset > 7.0D) {
                    throw new AssertionError("inset out of range (head/tail inverse is wrong): " + r);
                }
                if (Math.abs(r.lateral - b.pos[0]) > 1.0E-6D
                        || Math.abs(r.height - b.pos[1]) > 1.0E-6D) {
                    throw new AssertionError("lateral/height not mapped: " + r);
                }
            }
            if (Math.abs(VehicleLightOverrides.Resolved.of(beams.get(0), layout).inset) > 0.02D) {
                throw new AssertionError("head lamp at the nose must have inset ~0");
            }
        }

        // 缺字段：只写 type，其余全部走默认值（不能因为少一个字段就整份丢掉）
        final List<VehicleLightOverrides.Beam> sparse = BeamPackWriter.parse(
                "{\"beams\":[{\"type\":\"head\"},{\"type\":\"tail\",\"color\":\"#ff0000\"}]}",
                "sparse", "sparse.json");
        System.out.println("sparse beams = " + sparse.size() + " " + sparse);
        if (sparse.size() != 2) {
            throw new AssertionError("sparse file must keep both beams: " + sparse);
        }
        if (sparse.get(1).color == null || (sparse.get(1).color & 0xFFFFFF) != 0xFF0000) {
            throw new AssertionError("color not parsed: " + sparse.get(1).color);
        }
    }

    private static void expect(final String want, final String got) {
        if (!want.equals(got)) {
            throw new AssertionError("want " + want + " but got " + got);
        }
        System.out.println("ok: " + got);
    }
}
