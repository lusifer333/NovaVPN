#!/usr/bin/env bash
# Download native VPN engine binaries for NovaVPN.
# Binaries go to jniLibs/<abi>/lib<name>.so so they get proper
# SELinux context on installation (requires extractNativeLibs=true).
#
# Usage:  ./scripts/download-engines.sh [arm64-v8a|armeabi-v7a|x86_64|x86]
#
# Default architecture: arm64-v8a (most Android devices)

set -euo pipefail

ARCH="${1:-arm64-v8a}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JNILIBS_DIR="$SCRIPT_DIR/../app/src/main/jniLibs"

echo "==> NovaVPN Engine Downloader (jniLibs target)"
echo "    Architecture: $ARCH"
echo ""

# Map common arch names to release filenames
case "$ARCH" in
  arm64-v8a)   RELEASE_ARCH="arm64-v8a"  ;;
  armeabi-v7a) RELEASE_ARCH="arm32-v7a"  ;;
  x86_64)      RELEASE_ARCH="x86_64"     ;;
  x86)         RELEASE_ARCH="x86"        ;;
  *) echo "Unknown architecture: $ARCH"; exit 1 ;;
esac

mkdir -p "$JNILIBS_DIR/$ARCH"

# ------------------------------------------------------------
# Xray-core  →  jniLibs/<arch>/libxray.so
# ------------------------------------------------------------
echo "--- Xray-core ($RELEASE_ARCH) ---"

XRAY_SO="$JNILIBS_DIR/$ARCH/libxray.so"

if [ -f "$XRAY_SO" ]; then
  echo "  Already exists: $XRAY_SO ($(du -h "$XRAY_SO" | cut -f1))"
  echo "  Skipping."
else
  XRAY_URL="https://github.com/XTLS/Xray-core/releases/latest/download/Xray-linux-${RELEASE_ARCH}.zip"
  XRAY_ZIP="/tmp/xray-${RELEASE_ARCH}.zip"

  echo "  Downloading: $XRAY_URL"
  curl -sL "$XRAY_URL" -o "$XRAY_ZIP"
  echo "  Extracting..."
  unzip -qo "$XRAY_ZIP" -d /tmp/xray-extract/
  mv /tmp/xray-extract/xray "$XRAY_SO"
  chmod +x "$XRAY_SO"
  rm -rf /tmp/xray-extract "$XRAY_ZIP"
  echo "  Installed: $XRAY_SO ($(du -h "$XRAY_SO" | cut -f1))"
fi

echo ""
echo "==> Done! Binary ready under $JNILIBS_DIR/$ARCH/"
echo ""
echo "Next: rebuild the APK with:"
echo "  ./gradlew assembleDebug"
echo ""
echo "Note: ensure app/src/main/AndroidManifest.xml has:"
echo '  android:extractNativeLibs="true"'
echo ""