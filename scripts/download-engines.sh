#!/usr/bin/env bash
# Download native VPN engine binaries for NovaVPN.
# Binaries go to jniLibs/<abi>/ so they get proper
# SELinux context on installation (requires extractNativeLibs=true).
#
# Usage:  ./scripts/download-engines.sh [arm64-v8a|armeabi-v7a|x86_64|x86]
#
# Default architecture: arm64-v8a (most Android devices)

set -euo pipefail

ARCH="${1:-arm64-v8a}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JNILIBS_DIR="$SCRIPT_DIR/../app/src/main/jniLibs"
HEV_VERSION="2.16.0"

echo "==> NovaVPN Engine Downloader (jniLibs target)"
echo "    Architecture: $ARCH"
echo ""

# Map common arch names to release filenames
case "$ARCH" in
  arm64-v8a)   RELEASE_ARCH="arm64-v8a"  ;;
  armeabi-v7a) RELEASE_ARCH="armeabi-v7a"  ;;
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

# ------------------------------------------------------------
# hev-socks5-tunnel  →  jniLibs/<arch>/hev-socks5-tunnel
# TUN-to-SOCKS5 bridge binary for Android.
# Pre-built binaries available since v2.15.0.
# ------------------------------------------------------------
echo ""
echo "--- hev-socks5-tunnel ($ARCH) ---"

TUNNEL_BIN="$JNILIBS_DIR/$ARCH/libhev-socks5-tunnel.so"

if [ -f "$TUNNEL_BIN" ] && [ "$(head -c 3 "$TUNNEL_BIN")" != "#!/" ]; then
  echo "  Already exists: $TUNNEL_BIN ($(du -h "$TUNNEL_BIN" | cut -f1))"
  echo "  Skipping."
else
  HEV_URL="https://github.com/heiher/hev-socks5-tunnel/releases/download/${HEV_VERSION}/hev-socks5-tunnel-android-${RELEASE_ARCH}"

  echo "  Downloading: $HEV_URL"
  # Remove old placeholder if exists
  rm -f "$TUNNEL_BIN"
  curl -sL "$HEV_URL" -o "$TUNNEL_BIN"
  chmod +x "$TUNNEL_BIN"
  echo "  Installed: $TUNNEL_BIN ($(du -h "$TUNNEL_BIN" | cut -f1))"
fi

echo ""
echo "==> Done! Binaries ready under $JNILIBS_DIR/$ARCH/"
echo ""
echo "Next: rebuild the APK with:"
echo "  ./gradlew assembleDebug"
echo ""
echo "Note: ensure app/src/main/AndroidManifest.xml has:"
echo '  android:extractNativeLibs="true"'
echo ""
