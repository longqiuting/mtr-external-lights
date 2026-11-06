#!/usr/bin/env bash
# Builds every supported Minecraft version and drops the jars on the Desktop.
set -u

ROOT=/d/MC-MTR-JS/mtr-headlight
DESK=/c/Users/AD/Desktop
export JAVA_HOME="C:/Program Files/Java/jdk-21.0.10"
GRADLE=/d/MC-MTR-JS/temp/gradle/gradle-8.14/bin/gradle
CACHE=/c/Users/AD/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-intermediary
NAMED_CACHE=/c/Users/AD/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged
# maven.fabricmc.net 实测很慢（元数据要 20s），默认超时不够
HTTP=(-Dorg.gradle.internal.http.socketTimeout=240000 -Dorg.gradle.internal.http.connectionTimeout=120000)

build_one() {
  local dir=$1
  cd "$ROOT/$dir" || { echo "!! $dir 不存在"; return; }
  local mc
  mc=$(grep -E '^minecraft_version=' gradle.properties | cut -d= -f2)
  echo "=== $dir (MC $mc) ==="

  if grep -q '__INTERMEDIARY__' build.gradle; then
    local jar named
    jar=$(find "$CACHE" -maxdepth 2 -path "*${mc}-net.fabricmc*" -name '*.jar' 2>/dev/null | head -1)
    named=$(find "$NAMED_CACHE" -maxdepth 2 -path "*${mc}-net.fabricmc*" -name '*.jar' 2>/dev/null | head -1)
    if [ -z "$jar" ] || [ -z "$named" ]; then
      echo "   !! 缺 MC $mc 的 intermediary/named jar，先跑 gradle help"
      "$GRADLE" help --no-daemon "${HTTP[@]}" > "/d/MC-MTR-JS/temp/help-${mc}.log" 2>&1
      jar=$(find "$CACHE" -maxdepth 2 -path "*${mc}-net.fabricmc*" -name '*.jar' 2>/dev/null | head -1)
      named=$(find "$NAMED_CACHE" -maxdepth 2 -path "*${mc}-net.fabricmc*" -name '*.jar' 2>/dev/null | head -1)
    fi
    if [ -z "$jar" ] || [ -z "$named" ]; then
      echo "   !! 仍然缺 jar (intermediary=$jar named=$named)"
      return
    fi
    sed -i "s|__MC_NAMED__|$(echo "$named" | sed 's|^/c/|C:/|')|; s|__INTERMEDIARY__|$(echo "$jar" | sed 's|^/c/|C:/|')|" build.gradle
    echo "   named+intermediary 已注入"
  fi

  local log="/d/MC-MTR-JS/temp/build-${mc}.log"
  local ok=1
  for attempt in 1 2 3; do
    "$GRADLE" build --no-daemon "${HTTP[@]}" > "$log" 2>&1 && { ok=0; break; }
    echo "   第 $attempt 次构建失败，重试…"
    grep -E "Could not|错误|error:" "$log" | head -3
    sleep 5
  done
  if [ $ok -ne 0 ]; then
    echo "   !! 构建失败，日志: $log"
    return
  fi

  local out="$ROOT/$dir/build/libs/mtr-external-lights-0.1.0.jar"
  cp -f "$out" "$DESK/mtr-external-lights-0.1.0+$mc.jar"
  echo "   ok -> 桌面/mtr-external-lights-0.1.0+$mc.jar ($(stat -c%s "$out") 字节)"
}

for d in "$@"; do
  build_one "$d"
done
