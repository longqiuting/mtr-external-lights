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

package net.fabricmc.fabric.impl.itemgroup;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.mixin.itemgroup.ItemGroupsAccessor;
import net.minecraft.class_1761;
import net.minecraft.class_7706;

public final class ItemGroupHelper {
	private ItemGroupHelper() {
	}

	/**
	 * A list of item groups, but with special groups grouped at the end.
	 */
	public static List<class_1761> sortedGroups = class_7706.method_47341();

	public static void appendItemGroup(class_1761 itemGroup) {
		for (class_1761 existingGroup : class_7706.method_47341()) {
			if (existingGroup.getId().equals(itemGroup.getId())) {
				throw new IllegalStateException("Duplicate item group: " + itemGroup.getId());
			}
		}

		var itemGroups = new ArrayList<>(class_7706.method_47341());
		itemGroups.add(itemGroup);

		List<class_1761> validated = ItemGroupsAccessor.invokeCollect(itemGroups.toArray(class_1761[]::new));
		ItemGroupsAccessor.setGroups(validated);
		sortedGroups = validated.stream().sorted((a, b) -> {
			if (a.method_7752() && !b.method_7752()) return 1;
			if (!a.method_7752() && b.method_7752()) return -1;
			return 0;
		}).toList();
	}
}
