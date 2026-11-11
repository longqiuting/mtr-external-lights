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

package net.fabricmc.fabric.impl.client.itemgroup;

import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.class_1761;
import net.minecraft.class_2561;
import net.minecraft.class_2960;
import net.minecraft.class_4185;
import net.minecraft.class_4587;
import net.minecraft.class_481;
import net.minecraft.class_7706;
import com.mojang.blaze3d.systems.RenderSystem;

public class FabricCreativeGuiComponents {
	private static final class_2960 BUTTON_TEX = new class_2960("fabric", "textures/gui/creative_buttons.png");
	public static final Set<class_1761> COMMON_GROUPS = Set.of(class_7706.field_40200, class_7706.field_40206, class_7706.field_40199);

	public static class ItemGroupButtonWidget extends class_4185 {
		final CreativeGuiExtensions extensions;
		final class_481 gui;
		final Type type;

		public ItemGroupButtonWidget(int x, int y, Type type, CreativeGuiExtensions extensions) {
			super(x, y, 11, 12, type.text, (bw) -> type.clickConsumer.accept(extensions), class_4185.field_40754);
			this.extensions = extensions;
			this.type = type;
			this.gui = (class_481) extensions;
		}

		@Override
		public void method_25394(class_4587 matrixStack, int mouseX, int mouseY, float float_1) {
			this.field_22762 = mouseX >= this.method_46426() && mouseY >= this.method_46427() && mouseX < this.method_46426() + this.field_22758 && mouseY < this.method_46427() + this.field_22759;
			this.field_22764 = extensions.fabric_isButtonVisible(type);
			this.field_22763 = extensions.fabric_isButtonEnabled(type);

			if (this.field_22764) {
				int u = field_22763 && this.method_49606() ? 22 : 0;
				int v = field_22763 ? 0 : 12;

				RenderSystem.setShaderTexture(0, BUTTON_TEX);
				RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
				this.method_25302(matrixStack, this.method_46426(), this.method_46427(), u + (type == Type.NEXT ? 11 : 0), v, 11, 12);

				if (this.field_22762) {
					int pageCount = (int) Math.ceil((class_7706.method_47335().size() - COMMON_GROUPS.size()) / 9D);
					gui.method_25424(matrixStack, class_2561.method_43469("fabric.gui.creativeTabPage", extensions.fabric_currentPage() + 1, pageCount), mouseX, mouseY);
				}
			}
		}
	}

	public enum Type {
		NEXT(class_2561.method_43470(">"), CreativeGuiExtensions::fabric_nextPage),
		PREVIOUS(class_2561.method_43470("<"), CreativeGuiExtensions::fabric_previousPage);

		final class_2561 text;
		final Consumer<CreativeGuiExtensions> clickConsumer;

		Type(class_2561 text, Consumer<CreativeGuiExtensions> clickConsumer) {
			this.text = text;
			this.clickConsumer = clickConsumer;
		}
	}
}
