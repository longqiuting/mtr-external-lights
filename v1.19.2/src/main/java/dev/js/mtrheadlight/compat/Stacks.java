package dev.js.mtrheadlight.compat;

import net.minecraft.item.ItemStack;

/** 1.20.1 的 {@code ItemStack#copyWithCount(int)}（1.19.3 才加）在 1.19.2 上的替身。 */
public final class Stacks {

    private Stacks() {
    }

    /** 复制一份、把数量改成 {@code count}。{@code stack} 为空时返回 {@link ItemStack#EMPTY}。 */
    public static ItemStack copyWithCount(final ItemStack stack, final int count) {
        if (stack == null || stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        final ItemStack copy = stack.copy();
        copy.setCount(count);
        return copy;
    }
}
