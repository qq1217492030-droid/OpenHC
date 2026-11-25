#!/usr/bin/env bash
# 把 AOSP 公开测试密钥（platform.pk8 + platform.x509.pem）转成给 OpenHC 签名用的 PKCS12 密钥库。
#
# 用途：有的车机系统（老车机的原厂包、第三方刷新包都不少见）整包用 AOSP 公开测试密钥签名。
# OpenHC 用同一把密钥签出来，系统就把它当平台签名的应用，签名级权限自动授予——其中
# 免确认的 VPN 授权（CONTROL_VPN）正好补上这类车机缺 VPN 授权页、有线连不了的短板。
# 背景见 docs/ANDROID6.md。
#
# 用法：
#   ./make-keystore.sh platform.pk8 platform.x509.pem [输出的 .p12 路径]
# 前两个文件从 AOSP 源码的 build/target/product/security/ 里拿（获取方式见本目录 README.md）。
set -euo pipefail

if [ "$#" -lt 2 ]; then
    echo "用法: $0 platform.pk8 platform.x509.pem [输出.p12]" >&2
    exit 2
fi
pk8="$1"
pem="$2"
out="${3:-openhc-platform.p12}"
password="openhc"   # 不保密：密钥本身是公开的，密码只是文件格式要求。

command -v openssl >/dev/null || { echo "需要 openssl（Windows 上 Git Bash 自带）" >&2; exit 1; }
[ -f "$pk8" ] || { echo "找不到 $pk8" >&2; exit 1; }
[ -f "$pem" ] || { echo "找不到 $pem" >&2; exit 1; }

# 先核对证书指纹：不对说明拿到的不是 AOSP testkey，签出来也和车机对不上。
echo "证书指纹（SHA-256）："
openssl x509 -in "$pem" -noout -fingerprint -sha256

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
openssl pkcs8 -inform DER -nocrypt -in "$pk8" -out "$work/key.pem"
openssl pkcs12 -export -inkey "$work/key.pem" -in "$pem" -name platform \
    -password pass:"$password" -out "$out"

echo
echo "已生成 $out（别名 platform，密码 $password）"
echo "接下来在 OpenHC 目录的 local.properties 里写："
echo "  openhc.platformSigning=<指向签名配置文件的路径>"
echo "配置文件内容（四行，路径用绝对路径）："
cat <<EOF
  storeFile=<这个 .p12 的绝对路径>
  storeType=PKCS12
  storePassword=$password
  keyAlias=platform
  keyPassword=$password
EOF
