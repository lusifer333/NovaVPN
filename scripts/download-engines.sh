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

# ------------------------------------------------------------
# hev-socks5-tunnel  →  jniLibs/<arch>/hev-socks5-tunnel
# TUN-to-SOCKS5 bridge binary for Android.
# ------------------------------------------------------------
echo ""
echo "--- hev-socks5-tunnel ($ARCH) ---"

TUNNEL_BIN="$JNILIBS_DIR/$ARCH/hev-socks5-tunnel"

if [ -f "$TUNNEL_BIN" ]; then
  echo "  Already exists: $TUNNEL_BIN ($(du -h "$TUNNEL_BIN" | cut -f1))"
  echo "  Skipping."
else
  echo "  NOTE: hev-socks5-tunnel is not yet pre-built for Android."
  echo "  Building from source requires Go + NDK cross-compilation."
  echo "  See: https://github.com/heiher/hev-socks5-tunnel"
  echo ""
  echo "  For now, the bridge runs in diagnostic mode (no native binary)."
  echo "  Place a pre-built hev-socks5-tunnel binary at:"
  echo "    $TUNNEL_BIN"
  echo ""

  # Create a placeholder script that logs bridge status
  cat > "$TUNNEL_BIN" << 'BRIDGE_PLACEHOLDER'
#!/system/bin/sh
# hev-socks5-tunnel bridge — placeholder
# Real binary should be compiled and placed here.
echo "hev-socks5-tunnel: diagnostic mode"
echo "TUN fd: $1"
echo "SOCKS5: $2:$3"
exit 0
BRIDGE_PLACEHOLDER
  chmod +x "$TUNNEL_BIN"
  echo "  Created placeholder: $TUNNEL_BIN"
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