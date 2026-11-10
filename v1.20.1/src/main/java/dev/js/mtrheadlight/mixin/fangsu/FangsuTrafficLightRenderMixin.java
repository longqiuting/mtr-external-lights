package dev.js.mtrheadlight.mixin.fangsu;

import dev.js.mtrheadlight.signal.FangsuSignalSync;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 驱动方速交通灯的「MTR 信号联动」档。
 *
 * <h2>注入点（CFR 反编译 fangsu 1.3.2 build20261102090023 证实）</h2>
 * <pre>
 * com.fangsu.blockEntities.BlockEntityTrafficLight
 * public void whenRendering() {                       // line 119
 *     if (markedError || !modelLoaded || content == null) return;
 *     if ("program".equals(this.dispType)) this.drive();   // line 123 — mtr 档不跑这里
 *     ...                                                   // line 126..134：按 lightState 画灯珠
 * }
 * </pre>
 * HEAD 注入先算出 aspect 并写 {@code lightState}，随后方速自己的绘制循环就照着画 ——
 * 这正是 {@code drive()} 在 program 档里扮演的角色（{@code player.getLightStates()} →
 * {@code lightState}），我们只是把来源换成绑定的 MTR 节点。
 *
 * <h2>为什么注入 {@code whenRendering} 而不是渲染器</h2>
 * <p>交通灯用的是通用的 {@code BaseBlockEntityRender}（{@code ModBlockClient} 第 28 行
 * {@code BLOCK_ENTITY_TRAFFIC_LIGHT -> new BaseBlockEntityRender(ctx)})，它每帧只做
 * {@code tryBeginRendering() → whenRendering()}（异步 {@code fangsu-rendering-async} 线程）
 * 和绘制两个 {@code EyeCandyDrawCalls} 缓冲，没有任何 traffic light 专属的挂点；
 * {@code whenRendering} 是唯一「每帧必走、且在我们写完之后才会读 {@code lightState}」的位置。
 *
 * <h2>失败模式</h2>
 * <ul>
 *   <li>配置 {@code required: false} + {@link dev.js.mtrheadlight.mixin.FangsuMixinPlugin} 的
 *       class 探针：没装方速就整条不应用；</li>
 *   <li>{@code require = 0}：方速改了方法名/结构就只是丢掉这次注入，方块回到原行为；</li>
 *   <li>handler 里再包一层 {@code catch (Throwable)}，反射失败不影响这一帧的灯（更不会崩客户端）。</li>
 * </ul>
 *
 * <p>{@code remap = false}：目标是方速自己的未混淆类名，没有 refmap 可查。
 */
@Mixin(targets = "com.fangsu.blockEntities.BlockEntityTrafficLight", remap = false)
public abstract class FangsuTrafficLightRenderMixin {

    @Inject(method = "whenRendering", at = @At("HEAD"), require = 0, remap = false)
    private void lte$syncFromMtrSignal(final CallbackInfo ci) {
        try {
            FangsuSignalSync.onRender((Object) this);
        } catch (Throwable ignored) {
            // 每帧都跑的东西，绝不因为一次反射失败把灯弄没。
        }
    }
}
