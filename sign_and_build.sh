#!/bin/bash
# ================================================================
# 逆天回复 —— 一键签名构建脚本（Linux/macOS）
# 用法:  ./sign_and_build.sh
# 逻辑:  明文 release.keystore 存在则直接用；不存在则从仓库里的
#        release.keystore.enc 解密（口令默认 lelecz，可用环境变量
#        KEYSTORE_PASS 覆盖），再构建 + apksigner 验签。
# ================================================================
set -e
cd "$(dirname "$0")"

KS_PASS="${KEYSTORE_PASS:-lelecz}"

echo "==> 逆天回复 签名构建"

# ── 1. 密钥库：有明文用明文，没有就解密 ──
if [ ! -f release.keystore ]; then
    if [ ! -f release.keystore.enc ]; then
        echo "[ERROR] release.keystore 和 release.keystore.enc 都不存在" >&2
        exit 1
    fi
    echo "[1/3] 从 release.keystore.enc 解密密钥库..."
    openssl enc -d -aes-256-cbc -pbkdf2 \
        -in release.keystore.enc -out release.keystore \
        -pass pass:"$KS_PASS"
    chmod 600 release.keystore
    echo "  [OK] 已解密 release.keystore（本机文件，已被 gitignore）"
else
    echo "[1/3] 使用现有 release.keystore"
fi

# ── 2. 构建 ──
echo "[2/3] 构建签名 APK..."
./gradlew assembleRelease -x lintVitalRelease

# ── 3. 验签 ──
APK=app/build/outputs/apk/release/app-release.apk
if [ ! -f "$APK" ]; then
    echo "[ERROR] 未找到 $APK" >&2
    exit 1
fi
echo "[3/3] apksigner 验签..."
SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$(grep '^sdk.dir' local.properties 2>/dev/null | cut -d= -f2 | tr -d ' ' | sed 's/\\\\/\\\\/g')}}"
APKSIGNER=$(ls "$SDK_DIR"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)
if [ -n "$APKSIGNER" ]; then
    "$APKSIGNER" verify --print-certs "$APK" | head -4
else
    echo "  [WARN] 未找到 apksigner，跳过验签"
fi
echo ""
echo "==> 完成: $APK"
