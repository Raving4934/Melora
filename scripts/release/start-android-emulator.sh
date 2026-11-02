#!/usr/bin/env bash
# CI 独立模拟器启动：首次启动与超时重试共用同一组参数，重试只追加 -wipe-data 冷启动。
set -euo pipefail

log_name=${1:?usage: start-android-emulator.sh LOG_NAME [EXTRA_EMULATOR_ARGS...]}
shift

# 保留真实 GLES 渲染与动画，改走 ANGLE，避开已复现的直接 SwiftShader GLES
# ColorBuffer/纹理绑定异常；不能通过关闭 GPU 或跳过沉浸测试掩盖设备失联。
dns_servers="$(python3 -B scripts/release/prepare_android_network.py --host-dns)"
printf '%s\n' "$dns_servers" | tee -a "$DEVICE_LOG_DIR/emulator-dns.log"
nohup "$ANDROID_HOME/emulator/emulator" -avd melora-ci -port 5554 \
  -no-window -no-audio -no-boot-anim -no-snapshot -gpu swangle \
  -accel on -cores 2 -memory 2048 -dns-server "$dns_servers" "$@" > "$DEVICE_LOG_DIR/$log_name" 2>&1 &
echo $! > "$DEVICE_LOG_DIR/emulator.pid"
