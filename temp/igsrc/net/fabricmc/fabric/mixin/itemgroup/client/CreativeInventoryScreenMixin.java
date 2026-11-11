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

package net.fabricmc.fabric.mixin.itemgroup.client;

import java.util.Objects;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.fabricmc.fabric.impl.client.itemgroup.CreativeGuiExtensions;
import net.fabricmc.fabric.impl.client.itemgroup.FabricCreativeGuiComponents;
import net.fabricmc.fabric.impl.itemgroup.FabricItemGroup;
import net.fabricmc.fabric.impl.itemgroup.ItemGroupHelper;
import net.minecraft.class_1661;
import net.minecraft.class_1703;
import net.minecraft.class_1761;
import net.minecraft.class_2561;
import net.minecraft.class_4587;
import net.minecraft.class_481;
import net.minecraft.class_485;
import net.minecraft.class_7706;

@Mixin(class_481.class)
public abstract class CreativeInventoryScreenMixin<T extends class_1703> extends class_485<T> implements CreativeGuiExtensions {
	public CreativeInventoryScreenMixin(T screenHandler, class_1661 playerInventory, class_2561 text) {
		super(screenHandler, playerInventory, text);
	}

	@Shadow
	protected abstract void setSelectedTab(class_1761 itemGroup_1);

	@Shadow
	private static class_1761 selectedTab;

	// "static" matches selectedTab
	private static int fabric_currentPage = 0;

	@Override
	public void fabric_nextPage() {
		if (!fabric_hasGroupForPage(fabric_currentPage + 1)) {
			return;
		}

		fabric_currentPage++;
		fabric_updateSelection();
	}

	@Override
	public void fabric_previousPage() {
		if (fabric_currentPage == 0) {
			return;
		}

		fabric_currentPage--;
		fabric_updateSelection();
	}

	@Override
	public boolean fabric_isButtonVisible(FabricCreativeGuiComponents.Type type) {
		return class_7706.method_47335().size() > (Objects.requireNonNull(class_7706.field_42466).comp_1252() ? 14 : 13);
	}

	@Override
	public boolean fabric_isButtonEnabled(FabricCreativeGuiComponents.Type type) {
		if (type == FabricCreativeGuiComponents.Type.NEXT) {
			return fabric_hasGroupForPage(fabric_currentPage + 1);
		}

		if (type == FabricCreativeGuiComponents.Type.PREVIOUS) {
			return fabric_currentPage != 0;
		}

		return false;
	}

	private void fabric_updateSelection() {
		if (!fabric_isGroupVisible(selectedTab)) {
			ItemGroupHelper.sortedGroups.stream()
					.filter(this::fabric_isGroupVisible)
					.findFirst()
					.ifPresent(this::setSelectedTab);
		}
	}

	@Inject(method = "init", at = @At("RETURN"))
	private void init(CallbackInfo info) {
		fabric_currentPage = fabric_getPage(selectedTab);

		int xpos = field_2776 + 170;
		int ypos = field_2800 + 4;

		method_37063(new FabricCreativeGuiComponents.ItemGroupButtonWidget(xpos + 11, ypos, FabricCreativeGuiComponents.Type.NEXT, this));
		method_37063(new FabricCreativeGuiComponents.ItemGroupButtonWidget(xpos, ypos, FabricCreativeGuiComponents.Type.PREVIOUS, this));
	}

	@Inject(method = "setSelectedTab", at = @At("HEAD"), cancellable = true)
	private void setSelectedTab(class_1761 itemGroup, CallbackInfo info) {
		if (!fabric_isGroupVisible(itemGroup)) {
			info.cancel();
		}
	}

	@Inject(method = "renderTabTooltipIfHovered", at = @At("HEAD"), cancellable = true)
	private void renderTabTooltipIfHovered(class_4587 matrixStack, class_1761 itemGroup, int mx, int my, CallbackInfoReturnable<Boolean> info) {
		if (!fabric_isGroupVisible(itemGroup)) {
			info.setReturnValue(false);
		}
	}

	@Inject(method = "isClickInTab", at = @At("HEAD"), cancellable = true)
	private void isClickInTab(class_1761 itemGroup, double mx, double my, CallbackInfoReturnable<Boolean> info) {
		if (!fabric_isGroupVisible(itemGroup)) {
			info.setReturnValue(false);
		}
	}

	@Inject(method = "renderTabIcon", at = @At("HEAD"), cancellable = true)
	private void renderTabIcon(class_4587 matrixStack, class_1761 itemGroup, CallbackInfo info) {
		if (!fabric_isGroupVisible(itemGroup)) {
			info.cancel();
		}
	}

	private boolean fabric_isGroupVisible(class_1761 itemGroup) {
		return fabric_currentPage == fabric_getPage(itemGroup);
	}

	private static int fabric_getPage(class_1761 itemGroup) {
		if (FabricCreativeGuiComponents.COMMON_GROUPS.contains(itemGroup)) {
			return fabric_currentPage;
		}

		final FabricItemGroup fabricItemGroup = (FabricItemGroup) itemGroup;
		return fabricItemGroup.getPage();
	}

	private static boolean fabric_hasGroupForPage(int page) {
		return class_7706.method_47335().stream()
				.anyMatch(itemGroup -> fabric_getPage(itemGroup) == page);
	}

	@Override
	public int fabric_currentPage() {
		return fabric_currentPage;
	}
}
