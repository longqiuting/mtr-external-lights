// Security blocks: client-side render layer + the scanner's config screen plumbing.
package dev.js.mtrheadlight.security;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 安检类方块的客户端部分，两件事：
 *
 * <ol>
 *   <li><b>渲染层</b> —— 给 {@code mtrlte:liquid_scanner} 挂 {@code cutout}。贴图
 *       {@code assets/mtrlte/textures/block/liquid_scanner.png}（1024×1024，模型的 UV 只用
 *       左上 {@code 10.25 × 9.5} 格，其余是 alpha=0），扫描口中间与机器四周也都是透明像素。
 *       挂 {@code solid} 层会把 alpha=0 画成黑色方块，所以与屏蔽门那一族一样用
 *       {@link RenderLayer#getCutout()}。</li>
 *   <li><b>配置界面</b> —— 收服务端 {@code mtrlte:liquid_scanner_cfg} 的 OPEN 包开界面
 *       （{@link LiquidScannerConfigScreen}），并把界面里点「保存」的名单发回去。
 *       名单的服务端权威在 {@link LiquidScannerNet}，这里只做「显示」和「回传」。</li>
 * </ol>
 *
 * <p>与 {@code XiamenClient} 同样的前提：方块本身由 {@code main} 入口
 * （{@link SecurityObjects#register()}）注册，Fabric 会先跑 {@code main} 再跑 {@code client}，
 * 所以这里拿得到实例。任何一步失败只影响这一块方块，不吞掉别的注册。
 */
public final class SecurityClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        try {
            final LiquidScannerBlock block = SecurityObjects.liquidScanner();
            if (block != null) {
                BlockRenderLayerMap.INSTANCE.putBlock(block, RenderLayer.getCutout());
            }
        } catch (Throwable ignored) {
            // 渲染层没挂上只是贴图难看（透明区域变黑），不影响游戏。
        }

        try {
            ClientPlayNetworking.registerGlobalReceiver(LiquidScannerNet.ID,
                    (client, handler, buf, sender) -> {
                        final BlockPos pos;
                        final boolean configured;
                        final Set<Identifier> ids = new LinkedHashSet<>();
                        try {
                            if (buf.readByte() != LiquidScannerNet.KIND_OPEN) {
                                return; // 只认 OPEN，其它类型（将来加的）忽略。
                            }
                            pos = buf.readBlockPos();
                            configured = buf.readBoolean();
                            final int n = Math.min(buf.readVarInt(), 512);
                            for (int i = 0; i < n; i++) {
                                final Identifier id = Identifier.tryParse(buf.readString());
                                if (id != null) {
                                    ids.add(id);
                                }
                            }
                        } catch (Throwable bad) {
                            return; // 坏包：不开界面，也不报错。
                        }
                        // 网络回调在 Netty 线程，开界面必须回到客户端主线程。
                        client.execute(() -> client.setScreen(
                                new LiquidScannerConfigScreen(pos, configured, ids)));
                    });
        } catch (Throwable ignored) {
            // Fabric API 版本不对时静默失效：刷子右键不弹界面，检测与灯照常。
        }
    }

    /**
     * 把界面里的名单发回服务端（界面「保存」按钮调）。
     * <p>必须在客户端主线程调用 —— 按钮回调本来就在主线程。
     * 服务端不在（对面服务器没装 LTE / 通道不通）时静默丢弃，界面照常关闭。
     */
    public static void sendSave(final BlockPos pos, final Collection<Identifier> ids) {
        try {
            if (!ClientPlayNetworking.canSend(LiquidScannerNet.ID)) {
                return;
            }
            ClientPlayNetworking.send(LiquidScannerNet.ID, LiquidScannerNet.savePacket(pos, ids));
        } catch (Throwable ignored) {
            // 发不出去就当没保存，不影响游戏。
        }
    }
}
