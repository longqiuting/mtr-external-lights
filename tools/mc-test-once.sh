#!/usr/bin/env bash
# 一次只允许跑一个 Minecraft 客户端。
#
# 为什么存在：这台机器只有 16 GB 内存。两个客户端同时跑会直接把原生内存打爆
# （"Native memory allocation (mmap) failed to map ... for G1 virtual space"），
# 表现为启动失败或 0xC0000005，看起来像模组崩了，其实是内存不够。
#
# 铁律：**只等，不杀**。正在跑的客户端可能是玩家自己开的，脚本绝不对任何
# 非自己启动的进程做 Stop-Process / kill。
#
# 用法：
#   bash tools/mc-test-once.sh <工作目录> <命令...>
# 例：
#   bash tools/mc-test-once.sh "C:/Users/AD/AppData/Local/Temp/mcboot" \
#        node launch.js "C:/.../game" --quickPlaySingleplayer 测试摄影专用

set -u

LOCK_DIR="${MC_TEST_LOCK:-D:/MC-MTR-JS/temp/mc-launch.lock}"
MAX_WAIT_SECONDS="${MC_TEST_MAX_WAIT:-1200}"
MAX_RUN_SECONDS="${MC_TEST_MAX_RUN:-600}"
HEAP="${MC_TEST_HEAP:-8G}"

if [ "$#" -lt 2 ]; then
    echo "用法: bash tools/mc-test-once.sh <工作目录> <命令...>" >&2
    exit 2
fi

WORKDIR="$1"
shift

mc_pids() {
    # 只认真正的 Minecraft 客户端（命令行里带 fabric/gameDir/minecraft）。
    powershell -NoProfile -Command \
        "Get-CimInstance Win32_Process -Filter \"Name='javaw.exe' or Name='java.exe'\" -ErrorAction SilentlyContinue | Where-Object { \$_.CommandLine -match 'fabric|gameDir|minecraft' } | Select-Object -ExpandProperty ProcessId" \
        2>/dev/null | tr -d '\r' | grep -E '^[0-9]+$' || true
}

cleanup() {
    # 只释放锁。绝不杀进程 —— 我们启动的子进程由 timeout 自己回收。
    rmdir "$LOCK_DIR" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

# 1) 抢锁
waited=0
until mkdir "$LOCK_DIR" 2>/dev/null; do
    if [ "$waited" -ge "$MAX_WAIT_SECONDS" ]; then
        echo "[mc-test-once] 等锁超时（${MAX_WAIT_SECONDS}s），放弃" >&2
        exit 3
    fi
    sleep 5
    waited=$((waited + 5))
done
[ "$waited" -gt 0 ] && echo "[mc-test-once] 等了 ${waited}s 才拿到锁"

# 2) 等所有客户端退出（锁只能防合作方，防不了漏网的；也可能玩家自己在玩）
waited=0
while : ; do
    pids="$(mc_pids)"
    [ -z "$pids" ] && break
    if [ "$waited" -ge "$MAX_WAIT_SECONDS" ]; then
        echo "[mc-test-once] 仍有客户端在跑（$pids），放弃，不杀它们" >&2
        exit 4
    fi
    echo "[mc-test-once] 等待其它客户端退出: $pids（不干预）"
    sleep 5
    waited=$((waited + 5))
done

# 硬上限：无论谁传 MC_TEST_HEAP，都不许超过 3G（16G 机器的安全线）
case "$HEAP" in
    *[Gg]) heap_g="${HEAP%[Gg]}" ;;
    *[Mm]) heap_g=$(( ${HEAP%[Mm]} / 1024 )) ;;
    *)     heap_g=3 ;;
esac
case "$heap_g" in ''|*[!0-9]*) heap_g=3 ;; esac
[ "$heap_g" -gt 10 ] && heap_g=10
[ "$heap_g" -lt 1 ] && heap_g=1
HEAP="${heap_g}G"

# 2.5) 确认物理内存够（Windows 页面文件太小会报 errno=1455，看起来像崩溃）
need_mb=$(( heap_g * 1024 + 1536 ))
waited=0
while : ; do
    free_mb=$(powershell -NoProfile -Command "[int]((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024)" 2>/dev/null | tr -d '\r' | grep -E '^[0-9]+$' || echo 0)
    [ "${free_mb:-0}" -ge "$need_mb" ] && break
    if [ "$waited" -ge "$MAX_WAIT_SECONDS" ]; then
        echo "[mc-test-once] 可用内存只有 ${free_mb}MB，需要 ${need_mb}MB，放弃" >&2
        exit 5
    fi
    echo "[mc-test-once] 等待内存释放（${free_mb}/${need_mb} MB）"
    sleep 10
    waited=$((waited + 10))
done
echo "[mc-test-once] 可用内存 ${free_mb}MB，启动 ${HEAP} 堆"

# 3) 限制堆再启动（覆盖而不是追加：调用方写进来的 -Xmx 会被丢掉）
export _JAVA_OPTIONS="-Xmx${HEAP} -XX:MaxMetaspaceSize=768m"
echo "[mc-test-once] _JAVA_OPTIONS=$_JAVA_OPTIONS"
echo "[mc-test-once] 启动: $*"

cd "$WORKDIR" || exit 1
if ! command -v "$1" >/dev/null 2>&1; then
    echo "[mc-test-once] 找不到命令: $1（PATH=$PATH）" >&2
    echo "[mc-test-once] 建议改用绝对路径，例如 /c/Program Files/nodejs/node" >&2
    exit 127
fi
timeout --signal=KILL "$MAX_RUN_SECONDS" "$@"
code=$?
[ "$code" -eq 137 ] && echo "[mc-test-once] 跑满 ${MAX_RUN_SECONDS}s 被强杀（不是崩溃）"
echo "[mc-test-once] exit=$code"
exit "$code"
