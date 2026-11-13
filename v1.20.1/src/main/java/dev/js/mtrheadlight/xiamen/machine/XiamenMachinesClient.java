// 售票机的客户端部分：方速售票界面的桥改成「第一次开界面时才反射」。
package dev.js.mtrheadlight.xiamen.machine;

import net.fabricmc.api.ClientModInitializer;

/**
 * 售票机的客户端入口。
 *
 * <p>两台机器本身是纯方块（没有方块实体、没有渲染器、不需要挂渲染层），客户端唯一要做的事是
 * 把方速的售票界面钩子接上 —— 但<b>不能在初始化阶段接</b>：
 * {@code Class.forName("com.fangsu.client.ClientHooks")} 会把方速的客户端类提前拉起来，
 * 方速那套静态初始化会在 registry 冻结之前往 BLOCK 注册表里塞一个「空键的 air holder」，
 * 客户端启动直接崩：
 *
 * <pre>
 * IllegalStateException: Some intrusive holders were not registered: [Reference{null=Block{minecraft:air}}]
 * </pre>
 *
 * <p>所以桥改由 {@link FangsuTicketUi#open} 在第一次右键售票机时懒加载 —— 那时 registry 早就
 * 冻结完了，方速自己的初始化也早就跑过，不会再有这个副作用。本入口只留一个日志，方便排查。
 */
public final class XiamenMachinesClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // 故意什么都不做：方速的类**一个字都不能在这里碰**，见类注释。
    }
}
