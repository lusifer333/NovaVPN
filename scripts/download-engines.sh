#!/usr/bin/env bash
# Download native VPN engine binaries for NovaVPN.
# Binaries go to jniLibs/<abi>/ so they get proper
# SELinux context on installation (requires extractNativeLibs=true).
#
# Usage:  ./scripts/download-engines.sh [arm64-v8a|armeabi-v7a] ...
#
# Default architecture: arm64-v8a (most Android devices)
# Multiple architectures can be passed: ./scripts/download-engines.sh arm64-v8a armeabi-v7a

set -euo pipefail

ARCHS="${*:-arm64-v8a}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JNILIBS_DIR="$SCRIPT_DIR/../app/src/main/jniLibs"

echo "==> NovaVPN Engine Downloader (jniLibs target)"
echo "    Architectures: $ARCHS"
echo ""

# Map common arch names to Xray release filenames.
# Note: Xray no longer publishes android-arm32-v7a assets; the linux-arm32
# build is fully static Go and runs as libxray.so on Android (same approach
# as the proven arm64-v8a binary).
for ARCH in $ARCHS; do
  case "$ARCH" in
    arm64-v8a)   RELEASE_ARCH="arm64-v8a"  ;;
    armeabi-v7a) RELEASE_ARCH="arm32-v7a"  ;;
    x86_64)      RELEASE_ARCH="64"         ;;
    x86)         RELEASE_ARCH="32"         ;;
    *) echo "Unknown architecture: $ARCH"; exit 1 ;;
  esac

  mkdir -p "$JNILIBS_DIR/$ARCH"

  # ------------------------------------------------------------
  # Xray-core  →  jniLibs/<arch>/libxray.so
  # ------------------------------------------------------------
  echo "--- Xray-core ($ARCH) ---"

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
    rm -rf /tmp/xray-extract
    unzip -qo "$XRAY_ZIP" -d /tmp/xray-extract/
    mv /tmp/xray-extract/xray "$XRAY_SO"
    chmod +x "$XRAY_SO"
    rm -rf /tmp/xray-extract "$XRAY_ZIP"
    echo "  Installed: $XRAY_SO ($(du -h "$XRAY_SO" | cut -f1))"
  fi

  echo ""
done

echo "==> Done! Binaries ready under $JNILIBS_DIR/"
echo ""
echo "Note: hev-socks5-tunnel is now compiled from source as a CMake static library."
echo "No separate binary download needed."
echo ""
echo "Next: rebuild the APK with:"
echo "  ./gradlew assembleDebug"
echo ""
