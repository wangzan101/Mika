#!/bin/bash
set -euo pipefail

# 独立开发包的 USB 初始化;不修改发布包数据或替换其他无障碍服务。
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
adb_bin="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
serial="${1:?Usage: bash tools/development-device.sh DEVICE_SERIAL}"
pkg="com.wangzan101.mika.development"
component="$pkg/com.wangzan101.mika.a11y.MikaAccessibilityService"
"$adb_bin" -s "$serial" install -r -g "$repo_dir/app/build/outputs/apk/development/app-development.apk"
"$adb_bin" -s "$serial" shell pm grant "$pkg" android.permission.POST_NOTIFICATIONS
"$adb_bin" -s "$serial" shell pm grant "$pkg" android.permission.WRITE_SECURE_SETTINGS
"$adb_bin" -s "$serial" shell appops set "$pkg" SCHEDULE_EXACT_ALARM allow
existing="$("$adb_bin" -s "$serial" shell settings get secure enabled_accessibility_services | tr -d '\r')"
if [[ "$existing" == "null" ]]; then existing=""; fi
case ":$existing:" in
    *":$component:"*) ;;
    *) enabled="${existing:+$existing:}$component"
       "$adb_bin" -s "$serial" shell settings put secure enabled_accessibility_services "$enabled" ;;
esac
"$adb_bin" -s "$serial" shell settings put secure accessibility_enabled 1
"$adb_bin" -s "$serial" shell input keyevent KEYCODE_WAKEUP
"$adb_bin" -s "$serial" shell am start -n "$pkg/com.wangzan101.mika.ui.MainActivity"
# App 启动后生成一次性 USB 助手脚本;单次有界等待只用于文件握手。
"$adb_bin" -s "$serial" shell 'for i in 1 2 3 4 5; do test -f /sdcard/Android/data/com.wangzan101.mika.development/files/usb-start.sh && break; sleep 1; done; sh /sdcard/Android/data/com.wangzan101.mika.development/files/usb-start.sh'
