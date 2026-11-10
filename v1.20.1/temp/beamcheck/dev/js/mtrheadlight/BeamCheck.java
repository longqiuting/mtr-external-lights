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
    }

    private static void expect(final String want, final String got) {
        if (!want.equals(got)) {
            throw new AssertionError("want " + want + " but got " + got);
        }
        System.out.println("ok: " + got);
    }
}
