#!/usr/bin/env bash
# 发布一个 CurseForge 文件。
#
# 用法：
#   bash tools/cf-publish.sh <mc版本> <版本号> [changelog文件]
#   bash tools/cf-publish.sh 1.20.1 0.2.0 docs/changelog-0.2.0.md
#
# token 来源：$CURSEFORGE_TOKEN → ~/.curseforge_token
# jar 取桌面：mtr-external-lights-<版本号>+<mc>.jar
# 若需要代理：export CF_PROXY=http://127.0.0.1:7890
set -euo pipefail

MC=${1:?用法: cf-publish.sh <mc版本> <版本号> [changelog文件]}
VER=${2:?用法: cf-publish.sh <mc版本> <版本号> [changelog文件]}
CHANGELOG_FILE=${3:-}
PROJECT_ID=1786143
GAME_ID=432            # Minecraft
API=https://api.curseforge.com/v1

TOK=${CURSEFORGE_TOKEN:-}
if [ -z "$TOK" ] && [ -f "$HOME/.curseforge_token" ]; then
  TOK=$(tr -d ' \r\n\t' < "$HOME/.curseforge_token")
fi
if [ -z "$TOK" ]; then
  echo "找不到 CurseForge token（\$CURSEFORGE_TOKEN 或 ~/.curseforge_token）" >&2
  exit 1
fi

PROXY_ARGS=()
if [ -n "${CF_PROXY:-}" ]; then
  PROXY_ARGS=(-x "$CF_PROXY")
fi

DESK="$HOME/Desktop"
JAR="$DESK/mtr-external-lights-$VER+$MC.jar"
if [ ! -f "$JAR" ]; then
  echo "找不到 jar: $JAR" >&2
  exit 1
fi
JARWIN=$(cygpath -w "$JAR" 2>/dev/null || echo "$JAR")

TMP=$(mktemp -d)
TMPWIN=$(cygpath -w "$TMP" 2>/dev/null || echo "$TMP")
trap 'rm -rf "$TMP"' EXIT

# 版本号 -> CurseForge 的 gameVersionId（同时取 Fabric 的 loader id）
curl -s "${PROXY_ARGS[@]}" --max-time 60 -H "X-Api-Token: $TOK" \
  "$API/games/$GAME_ID/versions" -o "$TMPWIN/versions.json" || true

MC_ID=$(node -e '
const j=require(process.argv[1]);const list=j.data||[];
const hit=list.find(v=>v.name===process.argv[2]&&v.gameVersionTypeID===1);
console.log(hit?hit.id:"");
' "$TMPWIN/versions.json" "$MC" 2>/dev/null || true)

LOADER_ID=$(node -e '
const j=require(process.argv[1]);const list=j.data||[];
const hit=list.find(v=>/^fabric$/i.test(v.name)&&v.gameVersionTypeID===2);
console.log(hit?hit.id:"");
' "$TMPWIN/versions.json" 2>/dev/null || true)

if [ -z "$MC_ID" ]; then
  echo "查不到 Minecraft $MC 的 gameVersionId（网络被拦时会这样，先确认 api.curseforge.com 可达）" >&2
  exit 1
fi

CHANGELOG=""
if [ -n "$CHANGELOG_FILE" ] && [ -f "$CHANGELOG_FILE" ]; then
  CHANGELOG=$(cat "$CHANGELOG_FILE")
else
  CHANGELOG="MTR external lights $VER"
fi
export CHANGELOG VER MC MC_ID LOADER_ID

node -e '
const fs=require("fs");
const gv=[Number(process.env.MC_ID)];
if (process.env.LOADER_ID) gv.push(Number(process.env.LOADER_ID));
const meta={
  changelog: process.env.CHANGELOG,
  changelogType: "markdown",
  displayName: `MTR external lights ${process.env.VER}+${process.env.MC}`,
  releaseType: "release",
  gameVersions: gv
};
fs.writeFileSync(process.argv[1], JSON.stringify(meta));
' "$TMPWIN/meta.json"

CODE=$(curl -s "${PROXY_ARGS[@]}" -o "$TMPWIN/out.json" -w '%{http_code}' --max-time 600 \
  -X POST -H "X-Api-Token: $TOK" -H "Accept: application/json" \
  -F "metadata=<$TMPWIN/meta.json" -F "file=@$JARWIN" \
  "$API/projects/$PROJECT_ID/upload-file")

if [ "$CODE" != "200" ]; then
  echo "上传失败 HTTP $CODE:" >&2
  head -c 400 "$TMPWIN/out.json" >&2; echo >&2
  exit 1
fi

node -e 'const j=require(process.argv[1]);const f=j.data||{};console.log(`ok: fileID=${f.id} status=${f.fileStatus} name=${f.displayName}`)' "$TMPWIN/out.json"
