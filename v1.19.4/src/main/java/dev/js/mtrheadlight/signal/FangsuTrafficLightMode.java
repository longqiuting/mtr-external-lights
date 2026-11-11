package dev.js.mtrheadlight.signal;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 1.19.4 降级版：方速（fangsu）交通灯的「切到 MTR 信号联动档」功能。
 *
 * <p>1.20.1 的实现要走 {@code com.fangsu} 的 {@code EnumConfig} 反射（{@link Enum} 里那个
 * {@code dispType} 字段只在方速的 1.20.1 字节码里存在），本仓库的 1.19.4 运行环境没有对应构建，
 * 所以这里只保留<b>同一套 API 形状</b>：{@link #toMtr} 一律回答
 * {@link Result#NOT_FANGSU}，也就是「不是方速灯，别切」。
 *
 * <p>对玩家的影响：信号连接器在方速交通灯上仍然能绑定（绑定表与模式无关），只是不会替你切换
 * 模式；需要时在方速自己的配置界面里选「MTR信号联动」。这条降级与 1.20.1 上装了不兼容方速
 * 版本时的行为完全一样（那条路径返回的也是 {@code NOT_FANGSU}）。
 */
public final class FangsuTrafficLightMode {
    /** 与 1.20.1 版完全一致的返回码，调用方的 switch 不用改。 */
    public enum Result {
        OK,
        ALREADY,
        NOT_FANGSU,
        FAIL
    }

    private FangsuTrafficLightMode() {
    }

    public static Result toMtr(final World world, final BlockPos pos) {
        return Result.NOT_FANGSU;
    }

    public static String currentMode(final World world, final BlockPos pos) {
        return "unsupported";
    }
}
