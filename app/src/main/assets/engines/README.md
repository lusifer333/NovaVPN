# Native VPN Engine Binaries

This directory must contain the actual Xray and Sing-box executables before building the APK.

## Quick Download

```bash
./scripts/download-engines.sh
```

This will download the latest **ARM64** binaries for both engines.

## Manual Download

### Xray-core

1. Go to https://github.com/XTLS/Xray-core/releases
2. Download `Xray-linux-arm64-v8a.zip`
3. Extract and place `xray` at:
   `engines/xray/arm64-v8a/xray`
4. Make executable: `chmod +x engines/xray/arm64-v8a/xray`

### Sing-box

1. Go to https://github.com/SagerNet/sing-box/releases
2. Download `sing-box-*-linux-arm64.tar.gz`
3. Extract and place `sing-box` at:
   `engines/sing-box/arm64-v8a/sing-box`
4. Make executable: `chmod +x engines/sing-box/arm64-v8a/sing-box`

## Architecture Support

| Path | Architecture |
|---|---|
| `xray/arm64-v8a/xray` | 64-bit ARM (most modern devices) |
| `xray/armeabi-v7a/xray` | 32-bit ARM (older devices) |
| `sing-box/arm64-v8a/sing-box` | 64-bit ARM |
| `sing-box/armeabi-v7a/sing-box` | 32-bit ARM |

Only `arm64-v8a` is bundled by default. Add other ABIs as needed.

## Verification

After placing binaries, verify:

```bash
file engines/xray/arm64-v8a/xray
file engines/sing-box/arm64-v8a/sing-box
```

Should show: `ELF 64-bit LSB executable, ARM aarch64`
