package net.fabricmc.fabric.api.event;

import java.util.ArrayList;
import java.util.List;

/** Minimal stub of Fabric API's Event holder. */
public class Event<T> {
    private final List<T> listeners = new ArrayList<>();

    public void register(T listener) {
        this.listeners.add(listener);
    }
}
