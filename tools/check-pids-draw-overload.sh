#!/bin/bash
# PIDS 渲染体检：按版本核对 TextRenderer#draw 的「真实重载」+ 贴图齐全。
#
# 为什么需要这个脚本：1.18.2 / 1.19.2 的 draw 第 8 位是 boolean seeThrough，
# 1.19.4 / 1.20.1 是 TextRenderer.TextLayerType 枚举；Matrix4f 也从
# net.minecraft.util.math.Matrix4f 换成 org.joml.Matrix4f。按位次「凑参数」也能编译通过，
# 但运行期会把「是否穿墙 / 底色 / 亮度」错位 —— 宽字符（中文）最先糊。
# 所以这里直接读已编译字节码里的方法描述符，跟该版本该有的描述符逐字比对。
#
# 用法：bash tools/check-pids-draw-overload.sh [版本...]        （默认三个旧版）
set -u

JAVAP="${JAVAP:-C:/Program Files/Java/jdk-21.0.10/bin/javap}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSIONS=("$@")
[ ${#VERSIONS[@]} -eq 0 ] && VERSIONS=(v1.18.2 v1.19.2 v1.19.4)

# javap / node 是原生程序，MSYS 的 /d/... 路径它不认，转成 D:/...
winpath() { cygpath -m "$1" 2>/dev/null || echo "$1"; }

# 每个版本「应该」调到的描述符（javap -s 的 descriptor，named 编译产物）。
expect_descriptor() {
    case "$1" in
        v1.18.2|v1.19.2)
            # draw(String, float, float, int, boolean, net.minecraft.util.math.Matrix4f,
            #      VertexConsumerProvider, boolean seeThrough, int bacgroundColor, int light)
            echo "(Ljava/lang/String;FFIZLnet/minecraft/util/math/Matrix4f;Lnet/minecraft/client/render/VertexConsumerProvider;ZII)I"
            ;;
        v1.19.4|v1.20.1)
            # draw(String, float, float, int, boolean, org.joml.Matrix4f,
            #      VertexConsumerProvider, TextRenderer.TextLayerType, int backgroundColor, int light)
            echo "(Ljava/lang/String;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/render/VertexConsumerProvider;Lnet/minecraft/client/font/TextRenderer\$TextLayerType;II)I"
            ;;
    esac
}

fail=0
for v in "${VERSIONS[@]}"; do
    cls="$ROOT/$v/build/classes/java/main/dev/js/mtrheadlight/xiamen/pids/PidsXiamenText.class"
    echo "===== $v"
    if [ ! -f "$cls" ]; then
        echo "  ✗ 没有编译产物（先 gradle -p $v compileJava）：$cls"
        fail=1
        continue
    fi
    want="$(expect_descriptor "$v")"
    got="$("$JAVAP" -p -c "$(winpath "$cls")" 2>/dev/null \
            | grep -o 'TextRenderer\.draw:(.*)I' | head -1 | sed 's/^TextRenderer\.draw://')"
    if [ -z "$got" ]; then
        echo "  ✗ 字节码里找不到 TextRenderer#draw 调用"
        fail=1
    elif [ "$got" = "$want" ]; then
        echo "  ✓ draw$got"
    else
        echo "  ✗ draw 描述符不对"
        echo "      want: $want"
        echo "      got : $got"
        fail=1
    fi

    # 贴图（源目录 + 打包后的 jar 都要有；图标另算）
    for t in textures/block/pids_xiamen_metro.png textures/block/pids_xiamen_brt.png \
             advertisements/xiamen_scenery.png advertisements/scenery1.png \
             advertisements/scenery2.png advertisements/scenery3.png; do
        src="$ROOT/$v/src/main/resources/assets/mtrlte/$t"
        if [ -f "$src" ]; then
            dim="$(node -e "const b=require('fs').readFileSync(process.argv[1]);console.log(b.readUInt32BE(16)+'x'+b.readUInt32BE(20))" "$(winpath "$src")" 2>/dev/null)"
            echo "  ✓ tex $t ${dim:-?}"
        else
            echo "  ✗ 缺贴图 $t"
            fail=1
        fi
    done
    jar="$(ls "$ROOT/$v"/build/libs/lte-*.jar 2>/dev/null | grep -v sources | tail -1)"
    if [ -n "$jar" ]; then
        n=$(unzip -l "$(winpath "$jar")" | grep -cE "assets/mtrlte/(textures/(block|item)/pids_|advertisements/)")
        echo "  · jar 内 PIDS 相关 png/json 条目：$n 个（$(basename "$jar")）"
    else
        echo "  · 还没有 jar（未执行 build）"
    fi
done

if [ $fail -ne 0 ]; then
    echo "==> FAIL"
    exit 1
fi
echo "==> OK"
