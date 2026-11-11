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

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.class_1761;
import net.minecraft.class_1792;
import net.minecraft.class_1799;
import net.minecraft.class_1935;
import net.minecraft.class_7699;
import org.jetbrains.annotations.ApiStatus;

/**
 * This class allows the entries of {@linkplain class_1761 item groups} to be modified by the events in {@link ItemGroupEvents}.
 */
@ApiStatus.Experimental
public class FabricItemGroupEntries implements class_1761.class_7704 {
	private final class_1761.class_8128 context;
	private final List<class_1799> displayStacks;
	private final List<class_1799> searchTabStacks;

	@ApiStatus.Internal
	public FabricItemGroupEntries(class_1761.class_8128 context, List<class_1799> displayStacks, List<class_1799> searchTabStacks) {
		this.context = context;
		this.displayStacks = displayStacks;
		this.searchTabStacks = searchTabStacks;
	}

	public class_1761.class_8128 getContext() {
		return context;
	}

	/**
	 * @return the currently enabled feature set
	 */
	public class_7699 getEnabledFeatures() {
		return context.comp_1251();
	}

	/**
	 * @return whether to show items restricted to operators, such as command blocks
	 */
	public boolean shouldShowOpRestrictedItems() {
		return context.comp_1252();
	}

	/**
	 * @return the stacks that will be shown in the tab in the creative mode inventory
	 * @apiNote This list can be modified.
	 */
	public List<class_1799> getDisplayStacks() {
		return displayStacks;
	}

	/**
	 * @return the stacks that will be searched by the creative mode inventory search
	 * @apiNote This list can be modified.
	 */
	public List<class_1799> getSearchTabStacks() {
		return searchTabStacks;
	}

	/**
	 * Adds a stack to the end of the item group. Duplicate stacks will be removed.
	 *
	 * @param visibility Determines whether the stack will be shown in the tab itself, returned
	 *                   for searches, or both.
	 */
	@Override
	public void method_45417(class_1799 stack, class_1761.class_7705 visibility) {
		if (isEnabled(stack)) {
			checkStack(stack);

			switch (visibility) {
			case field_40191 -> {
				this.displayStacks.add(stack);
				this.searchTabStacks.add(stack);
			}
			case field_40192 -> this.displayStacks.add(stack);
			case field_40193 -> this.searchTabStacks.add(stack);
			}
		}
	}

	/**
	 * See {@link #prepend(class_1799, class_1761.class_7705)}. Will use {@link class_1761.class_7705#field_40191}
	 * for visibility.
	 */
	public void prepend(class_1799 stack) {
		prepend(stack, class_1761.class_7705.field_40191);
	}

	/**
	 * Adds a stack to the beginning of the item group. Duplicate stacks will be removed.
	 *
	 * @param visibility Determines whether the stack will be shown in the tab itself, returned
	 *                   for searches, or both.
	 */
	public void prepend(class_1799 stack, class_1761.class_7705 visibility) {
		if (isEnabled(stack)) {
			checkStack(stack);

			switch (visibility) {
			case field_40191 -> {
				this.displayStacks.add(0, stack);
				this.searchTabStacks.add(0, stack);
			}
			case field_40192 -> this.displayStacks.add(0, stack);
			case field_40193 -> this.searchTabStacks.add(0, stack);
			}
		}
	}

	/**
	 * See {@link #prepend(class_1799)}. Automatically creates an {@link class_1799} from the given item.
	 */
	public void prepend(class_1935 item) {
		prepend(item, class_1761.class_7705.field_40191);
	}

	/**
	 * See {@link #prepend(class_1799, net.minecraft.class_1761.class_7705)}.
	 * Automatically creates an {@link class_1799} from the given item.
	 */
	public void prepend(class_1935 item, class_1761.class_7705 visibility) {
		prepend(new class_1799(item), visibility);
	}

	/**
	 * See {@link #addAfter(class_1935, Collection)}.
	 */
	public void addAfter(class_1935 afterLast, class_1799... newStack) {
		addAfter(afterLast, Arrays.asList(newStack));
	}

	/**
	 * See {@link #addAfter(class_1799, Collection)}.
	 */
	public void addAfter(class_1799 afterLast, class_1799... newStack) {
		addAfter(afterLast, Arrays.asList(newStack));
	}

	/**
	 * See {@link #addAfter(class_1935, Collection)}.
	 */
	public void addAfter(class_1935 afterLast, class_1935... newItem) {
		addAfter(afterLast, Arrays.stream(newItem).map(class_1799::new).toList());
	}

	/**
	 * See {@link #addAfter(class_1799, Collection)}.
	 */
	public void addAfter(class_1799 afterLast, class_1935... newItem) {
		addAfter(afterLast, Arrays.stream(newItem).map(class_1799::new).toList());
	}

	/**
	 * See {@link #addAfter(class_1935, Collection, net.minecraft.class_1761.class_7705)}.
	 */
	public void addAfter(class_1935 afterLast, Collection<class_1799> newStacks) {
		addAfter(afterLast, newStacks, class_1761.class_7705.field_40191);
	}

	/**
	 * See {@link #addAfter(class_1799, Collection, net.minecraft.class_1761.class_7705)}.
	 */
	public void addAfter(class_1799 afterLast, Collection<class_1799> newStacks) {
		addAfter(afterLast, newStacks, class_1761.class_7705.field_40191);
	}

	/**
	 * Adds stacks after an existing item in the group, or at the end, if the item isn't in the group.
	 *
	 * @param afterLast  Add {@code newStacks} after the last entry of this item in the group.
	 * @param newStacks  The stacks to add. Only {@linkplain #isEnabled(class_1799) enabled} stacks will be added.
	 * @param visibility Determines whether the stack will be shown in the tab itself, returned
	 *                   for searches, or both.
	 */
	public void addAfter(class_1935 afterLast, Collection<class_1799> newStacks, class_1761.class_7705 visibility) {
		newStacks = getEnabledStacks(newStacks);

		if (newStacks.isEmpty()) {
			return;
		}

		switch (visibility) {
		case field_40191 -> {
			addAfter(afterLast, newStacks, displayStacks);
			addAfter(afterLast, newStacks, searchTabStacks);
		}
		case field_40192 -> addAfter(afterLast, newStacks, displayStacks);
		case field_40193 -> addAfter(afterLast, newStacks, searchTabStacks);
		}
	}

	/**
	 * Adds stacks after an existing stack in the group, or at the end, if the stack isn't in the group.
	 *
	 * @param afterLast  Add {@code newStacks} after the last group entry matching this stack (compared using {@link class_1799#method_31577}).
	 * @param newStacks  The stacks to add. Only {@linkplain #isEnabled(class_1799) enabled} stacks will be added.
	 * @param visibility Determines whether the stack will be shown in the tab itself, returned
	 *                   for searches, or both.
	 */
	public void addAfter(class_1799 afterLast, Collection<class_1799> newStacks, class_1761.class_7705 visibility) {
		newStacks = getEnabledStacks(newStacks);

		if (newStacks.isEmpty()) {
			return;
		}

		switch (visibility) {
		case field_40191 -> {
			addAfter(afterLast, newStacks, displayStacks);
			addAfter(afterLast, newStacks, searchTabStacks);
		}
		case field_40192 -> addAfter(afterLast, newStacks, displayStacks);
		case field_40193 -> addAfter(afterLast, newStacks, searchTabStacks);
		}
	}

	/**
	 * Adds stacks after the last group entry matching a predicate, or at the end, if no entries match.
	 *
	 * @param afterLast  Add {@code newStacks} after the last group entry matching this predicate.
	 * @param newStacks  The stacks to add. Only {@linkplain #isEnabled(class_1799) enabled} stacks will be added.
	 * @param visibility Determines whether the stack will be shown in the tab itself, returned
	 *                   for searches, or both.
	 */
	public void addAfter(Predicate<class_1799> afterLast, Collection<class_1799> newStacks, class_1761.class_7705 visibility) {
		newStacks = getEnabledStacks(newStacks);

		if (newStacks.isEmpty()) {
			return;
		}

		switch (visibility) {
		case field_40191 -> {
			addAfter(afterLast, newStacks, displayStacks);
			addAfter(afterLast, newStacks, searchTabStacks);
		}
		case field_40192 -> addAfter(afterLast, newStacks, displayStacks);
		case field_40193 -> addAfter(afterLast, newStacks, searchTabStacks);
		}
	}

	/**
	 * See {@link #addBefore(class_1935, Collection)}.
	 */
	public void addBefore(class_1935 beforeFirst, class_1799... newStack) {
		addBefore(beforeFirst, Arrays.asList(newStack));
	}

	/**
	 * See {@link #addBefore(class_1799, Collection)}.
	 */
	public void addBefore(class_1799 beforeFirst, class_1799... newStack) {
		addBefore(beforeFirst, Arrays.asList(newStack));
	}

	/**
	 * See {@link #addBefore(class_1935, Collection)}.
	 */
	public void addBefore(class_1935 beforeFirst, class_1935... newItem) {
		addBefore(beforeFirst, Arrays.stream(newItem).map(class_1799::new).toList());
	}

	/**
	 * See {@link #addBefore(class_1799, Collection)}.
	 */
	public void addBefore(class_1799 beforeFirst, class_1935... newItem) {
		addBefore(beforeFirst, Arrays.stream(newItem).map(class_1799::new).toList());
	}

	/**
	 * See {@link #addBefore(class_1935, Collection, net.minecraft.class_1761.class_7705)}.
	 */
	public void addBefore(class_1935 beforeFirst, Collection<class_1799> newStacks) {
		addBefore(beforeFirst, newStacks, class_1761.class_7705.field_40191);
	}

	/**
	 * See {@link #addBefore(class_1799, Collection, net.minecraft.class_1761.class_7705)}.
	 */
	public void addBefore(class_1799 beforeFirst, Collection<class_1799> newStacks) {
		addBefore(beforeFirst, newStacks, class_1761.class_7705.field_40191);
	}

	/**
	 * Adds stacks before an existing item in the group, or at the end, if the item isn't in the group.
	 *
	 * @param beforeFirst Add {@code newStacks} before the first entry of this item in the group.
	 * @param newStacks   The stacks to add. Only {@linkplain #isEnabled(class_1799) enabled} stacks will be added.
	 * @param visibility  Determines whether the stack will be shown in the tab itself, returned
	 *                    for searches, or both.
	 */
	public void addBefore(class_1935 beforeFirst, Collection<class_1799> newStacks, class_1761.class_7705 visibility) {
		newStacks = getEnabledStacks(newStacks);

		if (newStacks.isEmpty()) {
			return;
		}

		switch (visibility) {
		case field_40191 -> {
			addBefore(beforeFirst, newStacks, displayStacks);
			addBefore(beforeFirst, newStacks, searchTabStacks);
		}
		case field_40192 -> addBefore(beforeFirst, newStacks, displayStacks);
		case field_40193 -> addBefore(beforeFirst, newStacks, searchTabStacks);
		}
	}

	/**
	 * Adds stacks before an existing stack to the group, or at the end, if the stack isn't in the group.
	 *
	 * @param beforeFirst Add {@code newStacks} before the first group entry matching this stack (compared using {@link class_1799#method_31577}).
	 * @param newStacks   The stacks to add. Only {@linkplain #isEnabled(class_1799) enabled} stacks will be added.
	 * @param visibility  Determines whether the stack will be shown in the tab itself, returned
	 *                    for searches, or both.
	 */
	public void addBefore(class_1799 beforeFirst, Collection<class_1799> newStacks, class_1761.class_7705 visibility) {
		newStacks = getEnabledStacks(newStacks);

		if (newStacks.isEmpty()) {
			return;
		}

		switch (visibility) {
		case field_40191 -> {
			addBefore(beforeFirst, newStacks, displayStacks);
			addBefore(beforeFirst, newStacks, searchTabStacks);
		}
		case field_40192 -> addBefore(beforeFirst, newStacks, displayStacks);
		case field_40193 -> addBefore(beforeFirst, newStacks, searchTabStacks);
		}
	}

	/**
	 * Adds stacks before the first group entry matching a predicate, or at the end, if no entries match.
	 *
	 * @param beforeFirst Add {@code newStacks} before the first group entry matching this predicate.
	 * @param newStacks   The stacks to add. Only {@linkplain #isEnabled(class_1799) enabled} stacks will be added.
	 * @param visibility  Determines whether the stack will be shown in the tab itself, returned
	 *                    for searches, or both.
	 */
	public void addBefore(Predicate<class_1799> beforeFirst, Collection<class_1799> newStacks, class_1761.class_7705 visibility) {
		newStacks = getEnabledStacks(newStacks);

		if (newStacks.isEmpty()) {
			return;
		}

		switch (visibility) {
		case field_40191 -> {
			addBefore(beforeFirst, newStacks, displayStacks);
			addBefore(beforeFirst, newStacks, searchTabStacks);
		}
		case field_40192 -> addBefore(beforeFirst, newStacks, displayStacks);
		case field_40193 -> addBefore(beforeFirst, newStacks, searchTabStacks);
		}
	}

	/**
	 * @return True if the item of a given stack is enabled in the current {@link class_7699}.
	 * @see class_1792#method_45382
	 */
	private boolean isEnabled(class_1799 stack) {
		return stack.method_7909().method_45382(getEnabledFeatures());
	}

	private Collection<class_1799> getEnabledStacks(Collection<class_1799> newStacks) {
		// If not all stacks are enabled, filter the list, otherwise use it as-is
		if (newStacks.stream().allMatch(this::isEnabled)) {
			return newStacks;
		}

		return newStacks.stream().filter(this::isEnabled).toList();
	}

	/**
	 * Adds the {@link class_1799} before the first match, if no matches the {@link class_1799} is appended to the end of the {@link class_1761}.
	 */
	private static void addBefore(Predicate<class_1799> predicate, Collection<class_1799> newStacks, List<class_1799> addTo) {
		checkStacks(newStacks);

		for (int i = 0; i < addTo.size(); i++) {
			if (predicate.test(addTo.get(i))) {
				addTo.subList(i, i).addAll(newStacks);
				return;
			}
		}

		// Anchor not found, add to end
		addTo.addAll(newStacks);
	}

	private static void addAfter(Predicate<class_1799> predicate, Collection<class_1799> newStacks, List<class_1799> addTo) {
		checkStacks(newStacks);

		// Iterate in reverse to add after the last match
		for (int i = addTo.size() - 1; i >= 0; i--) {
			if (predicate.test(addTo.get(i))) {
				addTo.subList(i + 1, i + 1).addAll(newStacks);
				return;
			}
		}

		// Anchor not found, add to end
		addTo.addAll(newStacks);
	}

	private static void addBefore(class_1799 anchor, Collection<class_1799> newStacks, List<class_1799> addTo) {
		checkStacks(newStacks);

		for (int i = 0; i < addTo.size(); i++) {
			if (class_1799.method_31577(anchor, addTo.get(i))) {
				addTo.subList(i, i).addAll(newStacks);
				return;
			}
		}

		// Anchor not found, add to end
		addTo.addAll(newStacks);
	}

	private static void addAfter(class_1799 anchor, Collection<class_1799> newStacks, List<class_1799> addTo) {
		checkStacks(newStacks);

		// Iterate in reverse to add after the last match
		for (int i = addTo.size() - 1; i >= 0; i--) {
			if (class_1799.method_31577(anchor, addTo.get(i))) {
				addTo.subList(i + 1, i + 1).addAll(newStacks);
				return;
			}
		}

		// Anchor not found, add to end
		addTo.addAll(newStacks);
	}

	private static void addBefore(class_1935 anchor, Collection<class_1799> newStacks, List<class_1799> addTo) {
		checkStacks(newStacks);

		class_1792 anchorItem = anchor.method_8389();

		for (int i = 0; i < addTo.size(); i++) {
			if (addTo.get(i).method_31574(anchorItem)) {
				addTo.subList(i, i).addAll(newStacks);
				return;
			}
		}

		// Anchor not found, add to end
		addTo.addAll(newStacks);
	}

	private static void addAfter(class_1935 anchor, Collection<class_1799> newStacks, List<class_1799> addTo) {
		checkStacks(newStacks);

		class_1792 anchorItem = anchor.method_8389();

		// Iterate in reverse to add after the last match
		for (int i = addTo.size() - 1; i >= 0; i--) {
			if (addTo.get(i).method_31574(anchorItem)) {
				addTo.subList(i + 1, i + 1).addAll(newStacks);
				return;
			}
		}

		// Anchor not found, add to end
		addTo.addAll(newStacks);
	}

	private static void checkStacks(Collection<class_1799> stacks) {
		for (class_1799 stack : stacks) {
			checkStack(stack);
		}
	}

	private static void checkStack(class_1799 stack) {
		if (stack.method_7960()) {
			throw new IllegalArgumentException("Cannot add empty stack");
		}

		if (stack.method_7947() != 1) {
			throw new IllegalArgumentException("Stack size must be exactly 1 for stack: " + stack);
		}
	}
}
