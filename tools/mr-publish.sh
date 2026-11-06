#!/usr/bin/env bash
# 发布一个 Modrinth 版本。
#
# 用法：
#   bash tools/mr-publish.sh <mc版本> <版本号> [changelog文件]
#   bash tools/mr-publish.sh 1.20.1 0.1.2
#
# token 来源（按顺序）：$MODRINTH_TOKEN → ~/.modrinth_token
# 令牌不会打印。jar 从桌面取：~/Desktop/mtr-external-lights-<版本号>+<mc>.jar
set -euo pipefail

MC=${1:?用法: mr-publish.sh <mc版本> <版本号> [changelog文件]}
VER=${2:?用法: mr-publish.sh <mc版本> <版本号> [changelog文件]}
CHANGELOG_FILE=${3:-}
PROJECT_ID=S4MLXR8U
UA='longqiuting/mtr-external-lights'
API=https://api.modrinth.com/v2

TOK=${MODRINTH_TOKEN:-}
if [ -z "$TOK" ] && [ -f "$HOME/.modrinth_token" ]; then
  TOK=$(tr -d ' \r\n\t' < "$HOME/.modrinth_token")
fi
if [ -z "$TOK" ]; then
  echo "找不到 Modrinth token（\$MODRINTH_TOKEN 或 ~/.modrinth_token）" >&2
  exit 1
fi

DESK="$HOME/Desktop"
JAR="$DESK/mtr-external-lights-$VER+$MC.jar"
if [ ! -f "$JAR" ]; then
  echo "找不到 jar: $JAR" >&2
  exit 1
fi

# 轨道宿主依赖：1.20.1 是 ANTE，其余是 NTE
case "$MC" in
  1.20.1) HOST=ybOYitTg ;;
  *)      HOST=8pXSjgW8 ;;
esac

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

if [ -n "$CHANGELOG_FILE" ] && [ -f "$CHANGELOG_FILE" ]; then
  CHANGELOG=$(cat "$CHANGELOG_FILE")
else
  CHANGELOG="MTR external lights $VER"
fi
export CHANGELOG MC VER HOST PROJECT_ID

node -e '
const fs = require("fs");
const d = {
  project_id: process.env.PROJECT_ID,
  name: `${process.env.VER}+${process.env.MC}`,
  version_number: `${process.env.VER}+${process.env.MC}`,
  changelog: process.env.CHANGELOG,
  dependencies: [
    { project_id: "XKPAmI6u", dependency_type: "required" },  // MTR
    { project_id: "yBW8D80W", dependency_type: "required" },  // LambDynamicLights
    { project_id: "P7dR8mSH", dependency_type: "required" },  // Fabric API
    { project_id: process.env.HOST, dependency_type: "optional" } // ANTE / NTE
  ],
  game_versions: [process.env.MC],
  loaders: ["fabric"],
  version_type: "release",
  featured: false,
  status: "listed",
  file_parts: ["file"],
  primary_file: "file"
};
fs.writeFileSync(process.argv[1], JSON.stringify(d));
' "$TMP/data.json"

CODE=$(curl -s -o "$TMP/out.json" -w '%{http_code}' --max-time 300 \
  -X POST -H "Authorization: $TOK" -H "User-Agent: $UA" \
  -F "data=@$TMP/data.json" -F "file=@$JAR" "$API/version")

if [ "$CODE" != "200" ]; then
  echo "上传失败 HTTP $CODE:" >&2
  head -c 400 "$TMP/out.json" >&2; echo >&2
  exit 1
fi

node -e 'const j=require(process.argv[1]);console.log(`ok: ${j.version_number}  id=${j.id}  status=${j.status}  file=${j.files[0].filename} (${j.files[0].size}B)`)' "$TMP/out.json"
