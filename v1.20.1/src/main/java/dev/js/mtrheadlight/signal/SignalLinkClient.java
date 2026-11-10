package dev.js.mtrheadlight.signal;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;

/**
 * 客户端的一半：收 {@code mtrlte:signal_link}，维护 {@link SignalLinks} 镜像。
 *
 * <p>收到的东西只在「客户端线程」上写——网络回调在 Netty 线程，{@code client.execute} 才回到主线程。
 * 写进去以后 {@code RenderSignalBaseMixin} 下一帧就会用到，不需要刷新区块：MTR 的信号灯渲染每帧
 * 现算 aspect，不缓存。
 */
public final class SignalLinkClient implements ClientModInitializer {

    /** Fabric 的 entrypoint 用反射 new 这个类：构造器必须是 public，否则客户端一启动就崩。 */
    public SignalLinkClient() {
    }

    @Override
    public void onInitializeClient() {
        try {
            ClientPlayNetworking.registerGlobalReceiver(SignalLinkNet.ID, (client, handler, buf, sender) -> {
                try {
                    final byte kind = buf.readByte();
                    final String dim = buf.readString();
                    switch (kind) {
                        case 0 -> { // SYNC
                            final int n = buf.readVarInt();
                            final Map<BlockPos, BlockPos> links = new HashMap<>();
                            for (int i = 0; i < n; i++) {
                                links.put(buf.readBlockPos(), buf.readBlockPos());
                            }
                            client.execute(() -> SignalLinks.replace(dim, links));
                        }
                        case 1 -> { // PUT
                            final BlockPos signal = buf.readBlockPos();
                            final BlockPos node = buf.readBlockPos();
                            client.execute(() -> SignalLinks.put(dim, signal, node));
                        }
                        case 2 -> { // REMOVE
                            final BlockPos signal = buf.readBlockPos();
                            client.execute(() -> SignalLinks.remove(dim, signal));
                        }
                        case 3 -> { // LAMP_SYNC
                            final int n = buf.readVarInt();
                            final Map<BlockPos, BlockPos> lamps = new HashMap<>();
                            for (int i = 0; i < n; i++) {
                                lamps.put(buf.readBlockPos(), buf.readBlockPos());
                            }
                            client.execute(() -> SignalLinks.replaceLamps(dim, lamps));
                        }
                        case 4 -> { // LAMP_PUT
                            final BlockPos lamp = buf.readBlockPos();
                            final BlockPos source = buf.readBlockPos();
                            client.execute(() -> SignalLinks.putLamp(dim, lamp, source));
                        }
                        case 5 -> { // LAMP_REMOVE
                            final BlockPos lamp = buf.readBlockPos();
                            client.execute(() -> SignalLinks.removeLamp(dim, lamp));
                        }
                        default -> {
                            // 未知类型：忽略（向前兼容）。
                        }
                    }
                } catch (Throwable ignored) {
                    // 坏包不影响游戏。
                }
            });
        } catch (Throwable ignored) {
            // Fabric API 版本不对时静默失效：信号灯退回 MTR 原行为。
        }

        try {
            ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> SignalLinks.clear());
        } catch (Throwable ignored) {
        }
    }
}
