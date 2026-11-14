// 售票机 / 余额查询机的客户端部分：注册查询机的 BER；方速售票界面的桥仍然是懒加载。
package dev.js.mtrheadlight.xiamen.machine;

import dev.js.mtrheadlight.LteLog;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;

/**
 * 两台机器的客户端入口。
 *
 * <ul>
 *   <li><b>余额查询机</b>：注册 {@link XiamenBalanceMachineRenderer}。它的几何来自
 *       {@code assets/mtrlte/rawmodels/balance_machine_{bottom,top}.json}（Blockbench 原样导出，
 *       屏幕是 {@code x:-12.5} 的斜面），原版 blockstate 模型表达不了，所以走 BER。
 *       方块模型已经是 {@code mtrlte:block/empty}，没有渲染层要挂
 *       （渲染器自己取 {@code getEntityCutoutNoCull}）。</li>
 *   <li><b>售票机</b>：这次没动，仍然是原版 blockstate 模型（它的角度全是 -22.5，原版能表达）；
 *       它没有方块实体，所以这里不注册任何东西。</li>
 *   <li><b>方速售票界面</b>：把方速的钩子接上 —— 但<b>不能在初始化阶段接</b>：
 *       {@code Class.forName("com.fangsu.client.ClientHooks")} 会把方速的客户端类提前拉起来，
 *       方速那套静态初始化会在 registry 冻结之前往 BLOCK 注册表里塞一个「空键的 air holder」，
 *       客户端启动直接崩：
 *       <pre>
 * IllegalStateException: Some intrusive holders were not registered: [Reference{null=Block{minecraft:air}}]
 *       </pre>
 *       所以桥改由 {@link FangsuTicketUi#open} 在第一次右键售票机时懒加载 —— 那时 registry 早就
 *       冻结完了，方速自己的初始化也早就跑过，不会再有这个副作用。</li>
 * </ul>
 */
public final class XiamenMachinesClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // 余额查询机的渲染器：失败只记日志，不影响方速那条懒加载路径。
        try {
            BlockEntityRendererRegistry.register(XiamenMachines.balanceEntityType(),
                    XiamenBalanceMachineRenderer::new);
            LteLog.LOGGER.info("[lte] balance machine renderer registered: {} (rawmodels)",
                    XiamenBalanceMachineRenderer.class.getSimpleName());
        } catch (Throwable t) {
            LteLog.LOGGER.error("[lte] could not register the balance machine renderer", t);
        }
        // 方速的类**一个字都不能在这里碰**，见类注释。
    }
}
