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

import java.util.Map;
import net.minecraft.class_1761;
import net.minecraft.class_2960;
import net.minecraft.class_7706;
import com.google.common.collect.ImmutableMap;

public final class MinecraftItemGroups {
	public static final class_2960 BUILDING_BLOCKS_ID = new class_2960("minecraft:building_blocks");
	public static final class_2960 COLOURED_BLOCKS_ID = new class_2960("minecraft:colored_blocks");
	public static final class_2960 NATURAL_ID = new class_2960("minecraft:natural");
	public static final class_2960 FUNCTIONAL_ID = new class_2960("minecraft:functional");
	public static final class_2960 REDSTONE_ID = new class_2960("minecraft:redstone");
	public static final class_2960 HOTBAR_ID = new class_2960("minecraft:hotbar");
	public static final class_2960 SEARCH_ID = new class_2960("minecraft:search");
	public static final class_2960 TOOLS_ID = new class_2960("minecraft:tools");
	public static final class_2960 COMBAT_ID = new class_2960("minecraft:combat");
	public static final class_2960 FOOD_AND_DRINK_ID = new class_2960("minecraft:food_and_drink");
	public static final class_2960 INGREDIENTS_ID = new class_2960("minecraft:ingredients");
	public static final class_2960 SPAWN_EGGS_ID = new class_2960("minecraft:spawn_eggs");
	public static final class_2960 OP_ID = new class_2960("minecraft:op");
	public static final class_2960 INVENTORY_ID = new class_2960("minecraft:inventory");

	public static final Map<class_1761, class_2960> GROUP_ID_MAP = new ImmutableMap.Builder<class_1761, class_2960>()
			.put(class_7706.field_40195, MinecraftItemGroups.BUILDING_BLOCKS_ID)
			.put(class_7706.field_41059, MinecraftItemGroups.COLOURED_BLOCKS_ID)
			.put(class_7706.field_40743, MinecraftItemGroups.NATURAL_ID)
			.put(class_7706.field_40197, MinecraftItemGroups.FUNCTIONAL_ID)
			.put(class_7706.field_40198, MinecraftItemGroups.REDSTONE_ID)
			.put(class_7706.field_40199, MinecraftItemGroups.HOTBAR_ID)
			.put(class_7706.field_40200, MinecraftItemGroups.SEARCH_ID)
			.put(class_7706.field_41060, MinecraftItemGroups.TOOLS_ID)
			.put(class_7706.field_40202, MinecraftItemGroups.COMBAT_ID)
			.put(class_7706.field_41061, MinecraftItemGroups.FOOD_AND_DRINK_ID)
			.put(class_7706.field_41062, MinecraftItemGroups.INGREDIENTS_ID)
			.put(class_7706.field_40205, MinecraftItemGroups.SPAWN_EGGS_ID)
			.put(class_7706.field_41063, MinecraftItemGroups.OP_ID)
			.put(class_7706.field_40206, MinecraftItemGroups.INVENTORY_ID)
			.build();

	private MinecraftItemGroups() {
	}
}
