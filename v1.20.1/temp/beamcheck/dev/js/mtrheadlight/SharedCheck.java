package dev.js.mtrheadlight;

import net.minecraft.resource.DirectoryResourcePack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 离线检查 {@code _shared.json} 的「同包作用域」：给一个真实的资源包文件夹，问
 * {@link BeamPackScope} 三个问题 —— 这个 id 归不归这个包、这个包有没有 {@code _shared.json}、
 * 那份文件能解析出几束。不需要启动游戏（用 {@link DirectoryResourcePack} 直接读包）。
 *
 * <p>顺带验一个<b>负例</b>：只有 {@code _shared.json}、没有 {@code mtr_custom_resources.json}
 * 的包不产生作用域 —— 它的共用配置不会跑到别人的车上。
 */
public final class SharedCheck {

    public static void main(final String[] args) throws Exception {
        final Path packDir = args.length > 0
                ? Path.of(args[0])
                : Path.of("C:/Users/AD/Desktop/PCL/.minecraft/versions/1.20.1-Fabric 0.19.3/"
                + "resourcepacks/厦门金旅XML6125JEVD0C（连云港BRT）");
        if (!Files.isDirectory(packDir)) {
            throw new AssertionError("pack folder missing: " + packDir);
        }

        final List<BeamPackScope> scopes =
                BeamPackScope.of(List.of(new DirectoryResourcePack("real", packDir, false)));
        System.out.println("scopes = " + scopes);
        if (scopes.size() != 1) {
            throw new AssertionError("expected exactly 1 scope, got " + scopes.size());
        }
        final BeamPackScope scope = scopes.get(0);
        if (scope.size() != 5) {
            throw new AssertionError("custom_trains should be 5, got " + scope.size());
        }

        // 1) 渲染器报的 baseTrainType（MTR 用 model_properties 生成）→ 5 个涂装 key
        final List<String> byRuntime = scope.keysFor("train_12_2");
        System.out.println("keysFor(train_12_2) = " + byRuntime);
        if (byRuntime.size() != 5) {
            throw new AssertionError("train_12_2 must map to all 5 liveries: " + byRuntime);
        }
        // 2) 注册 id / key 直接命中
        expect(List.of("xml6125jevd0c_normal"),
                scope.keysFor("mtr_custom_train_xml6125jevd0c_normal"));
        expect(List.of("xml6125jevd0c"), scope.keysFor("xml6125jevd0c"));
        // 3) 别家 / 内置车不归这个包 —— 共用配置不能外溢
        expect(List.of(), scope.keysFor("SP1900"));
        expect(List.of(), scope.keysFor("train_9_2"));

        // 4) 包级共用：路径与内容
        final BeamPackScope.Shared shared = scope.shared();
        System.out.println("shared = " + (shared == null ? "null" : shared.path + " beams=" + shared.beams.size()));
        if (shared == null) {
            throw new AssertionError("the pack ships _shared.json but it was not found");
        }
        if (!shared.path.toLowerCase(java.util.Locale.ROOT)
                .endsWith("assets/lte/beam3d/_shared.json")) {
            throw new AssertionError("unexpected shared path: " + shared.path);
        }
        if (shared.beams.size() != 4) {
            throw new AssertionError("_shared.json should have 4 beams, got " + shared.beams.size());
        }
        for (final VehicleLightOverrides.Beam beam : shared.beams) {
            if (beam.pos == null || beam.color == null || beam.length == null
                    || beam.width == null || beam.alpha == null) {
                throw new AssertionError("shared beam lost a field: " + beam);
            }
            System.out.println("  " + beam);
        }
        if (!"head".equals(shared.beams.get(0).type) || !"tail".equals(shared.beams.get(2).type)) {
            throw new AssertionError("beam order should be 2 head + 2 tail: " + shared.beams);
        }
        // 车头朝 -Z：车头灯的 along 必须是负的，尾灯是正的
        if (shared.beams.get(0).pos[2] >= 0 || shared.beams.get(2).pos[2] <= 0) {
            throw new AssertionError("front must be -Z (head along < 0, tail along > 0)");
        }

        // 5) 负例：只有 _shared.json、没有 custom_trains 的包不产生作用域
        final Path orphan = Files.createTempDirectory("lte-shared-orphan");
        Files.createDirectories(orphan.resolve("assets/lte/beam3D"));
        Files.writeString(orphan.resolve("assets/lte/beam3D/_shared.json"),
                Files.readString(packDir.resolve("assets/lte/beam3D/_shared.json")));
        final List<BeamPackScope> none =
                BeamPackScope.of(List.of(new DirectoryResourcePack("orphan", orphan, false)));
        System.out.println("orphan scopes = " + none);
        if (!none.isEmpty()) {
            throw new AssertionError("a pack without custom_trains must not own any vehicle");
        }

        // 6) 同一个 id 的文件名候选顺序：key 在前、base 在后（日志里的 source 也按它打）
        final List<BeamPackWriter.Candidate> candidates =
                BeamPackWriter.Plan.of(scopes, "train_12_2").candidates;
        final List<String> order = new ArrayList<>();
        for (final BeamPackWriter.Candidate candidate : candidates) {
            order.add(candidate.source + ":" + candidate.names.get(0));
        }
        System.out.println("candidates = " + order);
        if (order.size() < 2) {
            throw new AssertionError("expected key candidates plus a base candidate: " + order);
        }
        if (!order.get(0).startsWith("key:")) {
            throw new AssertionError("a key file must be tried before a base file: " + order);
        }
        if (!order.get(order.size() - 1).startsWith("base:")) {
            throw new AssertionError("the baseTrainType file must come last: " + order);
        }
        if (!order.get(order.size() - 1).endsWith("train_12_2.json")) {
            throw new AssertionError("the last candidate must be the runtime id: " + order);
        }

        System.out.println("SHARED PACK CHECK OK");
    }

    private static void expect(final List<String> want, final List<String> got) {
        final List<String> a = new ArrayList<>(want);
        final List<String> b = new ArrayList<>(got);
        a.sort(String.CASE_INSENSITIVE_ORDER);
        b.sort(String.CASE_INSENSITIVE_ORDER);
        if (!a.equals(b)) {
            throw new AssertionError("want " + want + " but got " + got);
        }
        System.out.println("ok: " + got);
    }
}
