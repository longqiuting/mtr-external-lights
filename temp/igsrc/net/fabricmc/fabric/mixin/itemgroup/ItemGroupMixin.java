/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.fabric.mixin.itemgroup;

import java.util.Collection;
import java.util.LinkedList;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroupEntries;
import net.fabricmc.fabric.api.itemgroup.v1.IdentifiableItemGroup;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.impl.itemgroup.FabricItemGroup;
import net.fabricmc.fabric.impl.itemgroup.ItemGroupEventsImpl;
import net.fabricmc.fabric.impl.itemgroup.MinecraftItemGroups;
import net.minecraft.class_1761;
import net.minecraft.class_1799;
import net.minecraft.class_2960;
import net.minecraft.class_7706;

@Mixin(class_1761.class)
abstract class ItemGroupMixin implements IdentifiableItemGroup, FabricItemGroup {
	@Shadow
	private Collection<class_1799> displayStacks;

	@Shadow
	private Set<class_1799> searchTabStacks;

	@Unique
	private int fabric_page = -1;

	@Unique
	private class_2960 identifier;

	@Unique
	@Nullable
	private UUID fabric_fallbackUUID;

	@SuppressWarnings("ConstantConditions")
	@Inject(method = "updateEntries", at = @At(value = "INVOKE", target = "Lnet/minecraft/item/ItemGroup;reloadSearchProvider()V"))
	public void getStacks(class_1761.class_8128 context, CallbackInfo ci) {
		class_1761 self = (class_1761) (Object) this;

		// Do not modify special item groups (except Operator Blocks) at all.
		// Special item groups include Saved Hotbars, Search, and Survival Inventory.
		// Note, search gets modified as part of the parent item group.
		if (self.method_7752() && self != class_7706.field_41063) return;

		// Sanity check for the injection point. It should be after these fields are set.
		Objects.requireNonNull(displayStacks, "displayStacks");
		Objects.requireNonNull(searchTabStacks, "searchTabStacks");

		// Convert the entries to lists
		var mutableDisplayStacks = new LinkedList<>(displayStacks);
		var mutableSearchTabStacks = new LinkedList<>(searchTabStacks);
		var entries = new FabricItemGroupEntries(context, mutableDisplayStacks, mutableSearchTabStacks);

		final Event<ItemGroupEvents.ModifyEntries> modifyEntriesEvent = ItemGroupEventsImpl.getModifyEntriesEvent(getId());

		if (modifyEntriesEvent != null) {
			modifyEntriesEvent.invoker().modifyEntries(entries);
		}

		// Now trigger the global event
		if (self != class_7706.field_41063 || context.comp_1252()) {
			ItemGroupEvents.MODIFY_ENTRIES_ALL.invoker().modifyEntries(self, entries);
		}

		// Convert the stacks back to sets after the events had a chance to modify them
		displayStacks.clear();
		displayStacks.addAll(mutableDisplayStacks);

		searchTabStacks.clear();
		searchTabStacks.addAll(mutableSearchTabStacks);
	}

	@Override
	public class_2960 getId() {
		if (this.identifier != null) {
			return identifier;
		}

		final class_2960 vanillaId = MinecraftItemGroups.GROUP_ID_MAP.get((class_1761) (Object) this);

		if (vanillaId != null) {
			return vanillaId;
		}

		// No id known, generate a random one
		if (identifier == null) {
			setId(new class_2960("minecraft", "unidentified_" + UUID.randomUUID()));
		}

		return identifier;
	}

	@Override
	public int getPage() {
		if (fabric_page < 0) {
			throw new IllegalStateException("Item group has no page");
		}

		return fabric_page;
	}

	@Override
	public void setPage(int page) {
		this.fabric_page = page;
	}

	@Override
	public void setId(class_2960 identifier) {
		if (this.identifier != null) {
			throw new IllegalStateException("Cannot set id to (%s) as item group already has id (%s)".formatted(identifier, this.identifier));
		}

		this.identifier = identifier;
	}
}
