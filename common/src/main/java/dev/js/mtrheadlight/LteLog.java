package dev.js.mtrheadlight;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 全模组共用的 logger，<b>不引用任何客户端类</b>。
 *
 * <p>从前日志直接走 {@code MtrHeadlightClient.LOGGER}。那个类实现了
 * {@code ClientModInitializer}，在 dedicated server 上虽然通常仍能初始化（客户端类只出现在
 * 方法体里，JVM 惰性解析），但「配置加载」这种两端都会走到的路径没有理由把它拖进来：一旦某个
 * 客户端类在验证阶段被解析（不同 JVM / 不同 -Xverify 设置），服务端就会在启动时炸
 * {@code NoClassDefFoundError}。配置、几何、伤害这些两端共用的代码统一用本类。
 */
public final class LteLog {
    public static final Logger LOGGER = LoggerFactory.getLogger("lte");

    private LteLog() {
    }
}
