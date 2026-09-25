#!/usr/bin/env bash
# 本地备用出包：CI 不可用或需要在本机快速验证时，产出与 CI 同一签名方案的 release APK。
# 版本名用 ac 前缀的独立序号（sta-ac1、sta-ac2 …），与 CI 的 as 序号互不顶替；
# 正式发布的包仍以 CI 产物为准。
#
# 用法：
#   STA_RELEASE_STORE_FILE=/path/Asagnc.jks \
#   STA_RELEASE_STORE_PASSWORD=... \
#   STA_RELEASE_KEY_ALIAS=asagnc \
#   STA_RELEASE_KEY_PASSWORD=... \
#   scripts/build-release-local.sh
#
# 序号记在仓库之外（默认 $HOME/.sta/sta-ac-counter，可用 STA_LOCAL_BUILD_COUNTER 指定），
# 构建成功才推进，因此失败重跑不会跳号。
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
counter_file="${STA_LOCAL_BUILD_COUNTER:-$HOME/.sta/sta-ac-counter}"

for name in STA_RELEASE_STORE_FILE STA_RELEASE_STORE_PASSWORD STA_RELEASE_KEY_ALIAS STA_RELEASE_KEY_PASSWORD; do
  if [[ -z "${!name:-}" ]]; then
    echo "缺少环境变量 $name" >&2
    exit 1
  fi
done

sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk_root" ]]; then
  echo "缺少 ANDROID_HOME 或 ANDROID_SDK_ROOT，无法定位 apksigner" >&2
  exit 1
fi
apksigner_path="$(find "$sdk_root/build-tools" -type f -name apksigner | sort -V | tail -n 1)"
if [[ -z "$apksigner_path" ]]; then
  echo "在 $sdk_root/build-tools 下未找到 apksigner" >&2
  exit 1
fi

current=0
[[ -f "$counter_file" ]] && current="$(<"$counter_file")"
build_number=$((current + 1))

cd "$repo_root"
build_date="$(TZ=Asia/Shanghai date +%Y%m%d)"
./gradlew -PstaBuildPrefix=ac -PstaBuildNumber="$build_number" -PstaBuildDate="$build_date" :app:assembleRelease

apk="$repo_root/app/build/outputs/apk/release/app-release.apk"
if [[ ! -f "$apk" ]]; then
  echo "未找到 Release APK：$apk" >&2
  exit 1
fi

if ! verify_output="$("$apksigner_path" verify -v --print-certs "$apk" 2>&1)"; then
  echo "$verify_output"
  echo "apksigner 校验失败" >&2
  exit 1
fi
echo "$verify_output"
if ! grep -q "^Verified using v3 scheme (APK Signature Scheme v3): true$" <<<"$verify_output"; then
  echo "APK 不是 V3 签名，检查 signingConfigs.release 的签名方案配置" >&2
  exit 1
fi

mkdir -p "$(dirname "$counter_file")"
printf '%s\n' "$build_number" > "$counter_file"

echo "sta-ac$build_number：$apk"