#!/usr/bin/env bash
# 把本机的 openhc-identity.json 接进构建：在 local.properties 里写 openhc.identity.dir。
#
# 用法：
#   ./tools/identity/use-identity.sh <openhc-identity.json 的路径>
#
# 这份文件是配对/配网认证用的真实凭据（nearbyKeys），不进仓库（见 SECURITY.md 和
# tools/check_public_tree.py）；仓库里只有一份全零占位的示例 docs/identity.example.json。
# 目录里只能有这一个文件，构建脚本会检查。
set -euo pipefail

if [ "$#" -lt 1 ]; then
    echo "用法: $0 <openhc-identity.json 的路径>" >&2
    echo "  文件格式见 docs/identity.example.json；目录里只能有这一个文件。" >&2
    exit 2
fi

src="$1"
[ -f "$src" ] || { echo "找不到 $src" >&2; exit 1; }

name="$(basename "$src")"
[ "$name" = "openhc-identity.json" ] || {
    echo "文件名必须是 openhc-identity.json（现在是 $name）" >&2
    exit 1
}

grep -q '"nearbyKeys"' "$src" || {
    echo "$src 不像连接参数文件（没有 nearbyKeys），格式见 docs/identity.example.json" >&2
    exit 1
}

dir="$(cd "$(dirname "$src")" && { pwd -W 2>/dev/null || pwd; })"
others="$(ls -A "$dir" | grep -vx 'openhc-identity.json' || true)"
if [ -n "$others" ]; then
    echo "目录里只能有 openhc-identity.json，现在还有：" >&2
    echo "$others" >&2
    exit 1
fi

root="$(cd "$(dirname "$0")/../.." && pwd)"
props="$root/local.properties"
touch "$props"

# 去掉旧的 openhc.identity.dir 行再追加，顺手整理掉重复项；awk 保证文件以换行结尾。
tmp="$(mktemp)"
awk -v line="openhc.identity.dir=$dir" '/^openhc\.identity\.dir=/{next} {print} END{print line}' "$props" > "$tmp"
mv "$tmp" "$props"

echo "已写入 $props"
grep -n '^openhc\.identity\.dir=' "$props"
echo "接下来构建即可，例如：./gradlew assembleRelease -PplatformSigning"
