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

import java.util.Objects;
import net.minecraft.class_1761;
import net.minecraft.class_2960;

public final class FabricItemGroupBuilderImpl extends class_1761.class_7913 {
	private final class_2960 identifier;

	public FabricItemGroupBuilderImpl(class_2960 identifier) {
		// Set when building.
		super(null, -1);
		this.identifier = Objects.requireNonNull(identifier);
	}

	@Override
	public class_1761 method_47324() {
		final class_1761 itemGroup = super.method_47324();
		final FabricItemGroup fabricItemGroup = (FabricItemGroup) itemGroup;
		fabricItemGroup.setId(identifier);
		ItemGroupHelper.appendItemGroup(itemGroup);
		return itemGroup;
	}
}
