#!/bin/bash
# Build debug APK and install it to the connected phone via Windows adb.
set -euo pipefail

cd "$(dirname "$0")"
ADB="$(command -v adb || echo /home/yan/tools/bin/adb)"
APK="app/build/outputs/apk/debug/app-debug.apk"
PKG="com.yan.client"

# Windows adb.exe prints CRLF; strip \r before parsing.
adb_out() { "$ADB" "$@" 2>&1 | tr -d '\r'; }

echo "==> Checking device ..."
if ! adb_out devices | awk 'NR>1 && $2=="device" {found=1} END {exit !found}'; then
    echo "No device in 'device' state. Plug in the phone and allow USB debugging." >&2
    exit 1
fi

echo "==> Building debug APK ..."
./gradlew :app:assembleDebug

if [[ ! -f "$APK" ]]; then
    echo "APK not found at $APK" >&2
    exit 1
fi

echo "==> Installing $APK ..."
if ! adb_out install -r "$APK"; then
    # Debug keystore differs across machines → signature mismatch with old install.
    echo "==> Install failed, uninstalling old $PKG and retrying ..."
    adb_out uninstall "$PKG" || true
    adb_out install -r "$APK"
fi

echo "==> Launching $PKG ..."
adb_out shell am start -n "$PKG/.MainActivity"
echo "==> Done."
