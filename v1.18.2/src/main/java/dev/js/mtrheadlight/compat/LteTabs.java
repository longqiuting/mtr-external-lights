package dev.js.mtrheadlight.compat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.fabricmc.fabric.api.client.itemgroup.FabricItemGroupBuilder;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import dev.js.mtrheadlight.compat.LteTabs;

/**
 * 1.18.2 stand-in for 1.20.1's Fabric Item Group API v1
 * ({@code net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup} +
 * {@code ItemGroupEvents.modifyEntriesEvent}).
 *
 * <h2>Why a shim and not a rewrite</h2>
 * <p>1.19.3 turned the creative tabs into a real registry entry
 * ({@code Registry.ITEM_GROUP} + {@code RegistryKey<ItemGroup>}) and replaced
 * {@code Item.Settings#group(...)} with the {@code ItemGroupEvents.modifyEntriesEvent(key)}
 * callback. 1.18.2 still has the old line: a tab is a {@code new ItemGroup(...)} registered through
 * the fabric mixin ({@code FabricItemGroupBuilder}), and its contents come from the builder's
 * {@code appendItems} consumer. Twenty of the ported files call the 1.19.3+ shape, so this class
 * keeps that shape and forwards to the 1.18.2 twin.
 *
 * <p>The two shapes differ in three ways, all handled here:
 * <ul>
 *   <li><b>No registry key.</b> A tab is identified by its {@link Identifier} only, so
 *       {@code LteTabs.modify(id)}
 *       collapses to {@link #modify(Identifier)}.</li>
 *   <li><b>No {@code ItemGroup.Entries}.</b> 1.18.2's {@code appendItems} hands out a plain
 *       {@code List<ItemStack>}. {@link Entries} wraps it and keeps the 1.20.1 surface the callers
 *       use ({@code add(Item)} / {@code add(ItemStack)} / {@code getDisplayStacks()} / list views).</li>
 *   <li><b>Ordering.</b> {@code modifyEntriesEvent} callbacks run in registration order, exactly
 *       like the 1.20.1 events. The consumers are kept on <b>live</b> per-tab list that the
 *       {@code appendItems} lambda walks when the tab is drawn, so a module registered later still
 *       lands after the earlier ones — and {@code LogoObjects#pinCoreTabOrder} /
 *       {@code LteCoreItems#pinToolsTabOrder} can still reorder {@code getDisplayStacks()}.</li>
 * </ul>
 */
public final class LteTabs {

    private static final Map<Identifier, ItemGroup> GROUPS = new LinkedHashMap<>();
    private static final Map<Identifier, List<Consumer<Entries>>> APPENDERS = new LinkedHashMap<>();

    private LteTabs() {
    }

    /**
     * 1.20.1's {@code ItemGroup.Entries}, over 1.18.2's {@code List<ItemStack>}.
     *
     * <p>{@code add(Item)} is the 1.19.3+ overload (it wraps the item in a stack); 1.18.2 only
     * knows {@code add(ItemStack)}, which is why both are here.
     */
    public static final class Entries {
        private final List<ItemStack> stacks;

        Entries(final List<ItemStack> stacks) {
            this.stacks = stacks;
        }

        public void add(final net.minecraft.item.ItemConvertible item) {
            if (item != null) {
                this.stacks.add(new ItemStack(item));
            }
        }

        public void add(final ItemStack stack) {
            if (stack != null && !stack.isEmpty()) {
                this.stacks.add(stack);
            }
        }

        public void addAll(final Collection<ItemStack> more) {
            if (more != null) {
                for (final ItemStack stack : more) {
                    this.add(stack);
                }
            }
        }

        public int size() {
            return this.stacks.size();
        }

        public boolean isEmpty() {
            return this.stacks.isEmpty();
        }

        public ItemStack get(final int index) {
            return this.stacks.get(index);
        }

        /** The tab's mutable, in-progress entry list — what the 1.20.1 ordering pins manipulate. */
        public List<ItemStack> getDisplayStacks() {
            return this.stacks;
        }
    }

    /** 1.20.1's {@code ItemGroupEvents.ModifyEntries} — one {@code register} call site shape. */
    public static final class Event {
        private final Identifier tabId;

        Event(final Identifier tabId) {
            this.tabId = tabId;
        }

        public void register(final Consumer<Entries> consumer) {
            final List<Consumer<Entries>> list = APPENDERS.get(this.tabId);
            if (list == null) {
                // Same failure the 1.20.1 event has for an unregistered tab; every call site in the
                // port wraps this in try/catch, exactly as it did there.
                throw new IllegalStateException("tab not created yet: " + this.tabId);
            }
            list.add(consumer);
        }
    }

    /** {@code LteTabs.modify(id)}. */
    public static Event modify(final Identifier tabId) {
        return new Event(tabId);
    }

    /**
     * {@code FabricItemGroup.builder().icon(icon).build()} registered into
     * {@code Registry.ITEM_GROUP}: the 1.18.2 builder already registers the tab, so this returns
     * the built group and remembers it (the append callbacks are wired to the live list).
     */
    public static ItemGroup create(final Identifier tabId, final Supplier<ItemStack> icon) {
        final List<Consumer<Entries>> consumers = new ArrayList<>();
        APPENDERS.put(tabId, consumers);
        final ItemGroup group = FabricItemGroupBuilder.create(tabId)
                .icon(icon)
                .appendItems(stacks -> {
                    final Entries entries = new Entries(stacks);
                    for (final Consumer<Entries> consumer : consumers) {
                        consumer.accept(entries);
                    }
                })
                .build();
        GROUPS.put(tabId, group);
        return group;
    }

    /** {@code Registry.ITEM_GROUP.get(id)} — {@code null} when the tab was never built. */
    public static ItemGroup get(final Identifier tabId) {
        return GROUPS.get(tabId);
    }
}
