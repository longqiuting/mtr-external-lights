package dev.js.mtrheadlight;

import net.minecraft.client.MinecraftClient;

/**
 * 「打开 3D 光束编辑器」的客户端入口，给物品用（{@code /mtr_lte beam3d} 走
 * {@link Commands#openBeamEditor}，两者最终都是 {@link BeamEditor3DScreen}）。
 *
 * <h2>为什么单独一个类</h2>
 * {@code mtrlte:beam_editor} 物品在<b>两端</b>都注册（只在客户端注册的物品在服务器上是
 * 「未知物品」，联机时会掉），而 {@link BeamEditor3DScreen} 只存在于客户端。物品类因此只
 * 用反射碰这个类：dedicated server 上没有它，链接期不会被拖进常量池。
 *
 * <p>换屏仍然延迟一帧（{@code client.send}）：物品的 {@code use()} 是在交互回调里跑的，
 * 那一帧的输入处理可能把刚装上的屏幕换掉 —— 和指令那条路的处理一致。
 */
public final class BeamEditorOpener {

    private BeamEditorOpener() {
    }

    /** 下一帧装上编辑器（不指定车型：编辑器自己挑世界里的第一辆 / 包里的第一辆）。 */
    public static void open() {
        final MinecraftClient client = MinecraftClient.getInstance();
        client.send(() -> client.setScreen(new BeamEditor3DScreen(client.currentScreen, null)));
    }
}
