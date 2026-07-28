#!/usr/bin/env bash
# Download native VPN engine binaries for NovaVPN.
#
# Usage:  ./scripts/download-engines.sh [arm64-v8a|arm64-v7a|x86_64|x86]
#
# Default architecture: arm64-v8a (most Android devices)
# Place extracted files under app/src/main/assets/engines/<engine>/<arch>/

set -euo pipefail

ARCH="${1:-arm64-v8a}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ASSETS_DIR="$SCRIPT_DIR/../app/src/main/assets/engines"

echo "==> NovaVPN Engine Downloader"
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

# ------------------------------------------------------------
# Xray-core
# ------------------------------------------------------------
echo "--- Xray-core ($RELEASE_ARCH) ---"
XRAY_DIR="$ASSETS_DIR/xray/$ARCH"
mkdir -p "$XRAY_DIR"

XRAY_URL="https://github.com/XTLS/Xray-core/releases/latest/download/Xray-linux-${RELEASE_ARCH}.zip"
XRAY_ZIP="/tmp/xray-${RELEASE_ARCH}.zip"

if [ -f "$XRAY_DIR/xray" ]; then
  echo "  Already downloaded, skipping."
else
  echo "  Downloading: $XRAY_URL"
  curl -sL "$XRAY_URL" -o "$XRAY_ZIP"
  echo "  Extracting..."
  unzip -qo "$XRAY_ZIP" -d /tmp/xray-extract/
  mv /tmp/xray-extract/xray "$XRAY_DIR/xray"
  chmod +x "$XRAY_DIR/xray"
  rm -rf /tmp/xray-extract "$XRAY_ZIP"
  echo "  Installed: $XRAY_DIR/xray"
fi

# ------------------------------------------------------------
# Sing-box
# ------------------------------------------------------------
echo ""
echo "--- Sing-box ($RELEASE_ARCH) ---"
SINGBOX_DIR="$ASSETS_DIR/sing-box/$ARCH"
mkdir -p "$SINGBOX_DIR"

# Fetch latest release tag
SINGBOX_TAG=$(curl -sL https://api.github.com/repos/SagerNet/sing-box/releases/latest | grep '"tag_name"' | cut -d '"' -f 4)
echo "  Latest version: $SINGBOX_TAG"

SINGBOX_URL="https://github.com/SagerNet/sing-box/releases/download/${SINGBOX_TAG}/sing-box-${SINGBOX_TAG#v}-linux-${RELEASE_ARCH}.tar.gz"
SINGBOX_TGZ="/tmp/sing-box-${RELEASE_ARCH}.tar.gz"

if [ -f "$SINGBOX_DIR/sing-box" ]; then
  echo "  Already downloaded, skipping."
else
  echo "  Downloading: $SINGBOX_URL"
  curl -sL "$SINGBOX_URL" -o "$SINGBOX_TGZ"
  echo "  Extracting..."
  tar -xzf "$SINGBOX_TGZ" -C /tmp/
  # The tar creates a directory like sing-box-1.x.y-linux-arm64/
  EXTRACTED_DIR=$(find /tmp -maxdepth 1 -type d -name "sing-box-*" | head -1)
  if [ -n "$EXTRACTED_DIR" ] && [ -f "$EXTRACTED_DIR/sing-box" ]; then
    cp "$EXTRACTED_DIR/sing-box" "$SINGBOX_DIR/sing-box"
    chmod +x "$SINGBOX_DIR/sing-box"
    rm -rf "$EXTRACTED_DIR"
  fi
  rm -f "$SINGBOX_TGZ"
  echo "  Installed: $SINGBOX_DIR/sing-box"
fi

echo ""
echo "==> Done! Engines ready under $ASSETS_DIR"
echo ""
echo "Next: rebuild the APK with:"
echo "  ./gradlew assembleDebug"
echo ""
