package net.fabricmc.fabric.api.client.event.lifecycle.v1;

import net.fabricmc.fabric.api.event.Event;
import net.minecraft.client.MinecraftClient;

/** Stub mirroring fabric-lifecycle-events-v1 ClientTickEvents.END_CLIENT_TICK. */
public final class ClientTickEvents {
    public interface EndTick {
        void onEndTick(MinecraftClient client);
    }

    public static final Event<EndTick> END_CLIENT_TICK = new Event<>();

    private ClientTickEvents() {
    }
}
