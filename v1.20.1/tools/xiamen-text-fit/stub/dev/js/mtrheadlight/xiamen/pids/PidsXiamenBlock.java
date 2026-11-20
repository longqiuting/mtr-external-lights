// 离线编译用的桩：PidsXiamenLayout 只在 of(Kind) / rowsOf(Kind) 的签名里用到 Kind，
// 而真正的 PidsXiamenBlock 依赖 MC 类。这里只补那一个枚举，让排版常量（512×235 的框、
// 字号比例）能在工具里按**真实源码**读到，不用把 MC 的 jar 拖进离线检查。
//
// 只在 tools/xiamen-text-fit/check.sh 的 javac 参数里出现，不进 mod 的编译。
package dev.js.mtrheadlight.xiamen.pids;

public final class PidsXiamenBlock {

    private PidsXiamenBlock() {
    }

    /** 两块屏（地铁 / BRT）。 */
    public enum Kind {
        METRO,
        BRT
    }
}
