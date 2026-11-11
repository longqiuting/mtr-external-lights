package dev.js.mtrheadlight.bind;

import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

import java.util.List;

/**
 * LTE 车灯编辑器（{@code mtrlte:beam_editor}）：拿在手上右键，打开 3D 光束编辑器。
 *
 * <h2>和 {@code /mtr_lte beam3d} 同一个入口</h2>
 * 物品只负责「把屏幕装上」，编辑、导出、读资源包的逻辑全在
 * {@code BeamEditor3DScreen} / {@code BeamPackWriter} 里 —— 和指令那条路共用，
 * 两边不会各长一套。
 *
 * <h2>只有客户端</h2>
 * 服务端那一次 {@code use} 直接 {@code PASS}：不发包、不产生任何服务端状态。客户端走反射
 * （{@code dev.js.mtrheadlight.BeamEditorOpener}），因为编辑器屏幕类只存在于客户端，
 * dedicated server 上没有它。物品本身两端都注册（{@code LteCoreItems}，{@code main} 入口）。
 */
public final class BeamEditorItem extends Item {

    public BeamEditorItem(final Settings settings) {
        super(settings);
    }

    @Override
    public TypedActionResult<ItemStack> use(final World world, final PlayerEntity user, final Hand hand) {
        final ItemStack stack = user.getStackInHand(hand);
        if (!world.isClient) {
            // 服务端不处理：编辑器是纯客户端的东西。
            return TypedActionResult.pass(stack);
        }
        open();
        return TypedActionResult.success(stack);
    }

    /**
     * 打开 3D 编辑器（客户端）。反射同名入口，见类注释。
     *
     * @return {@code false} = 这一侧没有客户端类（dedicated server）或调用失败
     */
    public static boolean open() {
        try {
            Class.forName("dev.js.mtrheadlight.BeamEditorOpener")
                    .getMethod("open")
                    .invoke(null);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void appendTooltip(final ItemStack stack, final World world,
                              final List<Text> tooltip, final TooltipContext context) {
        tooltip.add(Text.translatable("tooltip.mtrlte.beam_editor.hint").formatted(Formatting.GRAY));
        tooltip.add(Text.translatable("tooltip.mtrlte.beam_editor.export").formatted(Formatting.DARK_GRAY));
        tooltip.add(Text.translatable("tooltip.mtrlte.beam_editor.client_only").formatted(Formatting.DARK_GRAY));
    }
}
