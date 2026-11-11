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

package net.fabricmc.fabric.api.itemgroup.v1;

import net.fabricmc.fabric.impl.itemgroup.FabricItemGroupBuilderImpl;
import net.minecraft.class_1761;
import net.minecraft.class_2561;
import net.minecraft.class_2960;
import net.minecraft.class_7706;

/**
 * Contains a method to create an item group builder.
 */
public final class FabricItemGroup {
	private FabricItemGroup() {
	}

	/**
	 * Creates a new builder for {@link class_1761}. Item groups are used to group items in the creative
	 * inventory.
	 *
	 * <p>Each new {@link class_1761} instance of this class is automatically appended to {@link class_7706#method_47341()} when
	 * {@link class_1761.class_7913#method_47324()} is invoked.
	 *
	 * <p>Example:
	 *
	 * <pre>{@code
	 * private static final ItemGroup ITEM_GROUP = FabricItemGroup.builder(new Identifier(MOD_ID, "test_group"))
	 *    .icon(() -> new ItemStack(Items.DIAMOND))
	 *    .entries((enabledFeatures, entries, operatorEnabled) -> {
	 *       entries.add(TEST_ITEM);
	 *    })
	 *    .build();
	 * }</pre>
	 *
	 * @param identifier the id of the ItemGroup, to be used as the default translation key
	 * @return a new {@link class_1761} instance
	 */
	public static class_1761.class_7913 builder(class_2960 identifier) {
		return new FabricItemGroupBuilderImpl(identifier)
				.method_47321(class_2561.method_43471("itemGroup.%s.%s".formatted(identifier.method_12836(), identifier.method_12832())));
	}
}
