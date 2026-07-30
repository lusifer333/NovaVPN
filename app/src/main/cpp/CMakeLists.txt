cmake_minimum_required(VERSION 3.22.1)
project("nativebridge")

# --- hev-socks5-tunnel static library (upstream v2.16.0) ---
add_subdirectory(hev-socks5-tunnel)

# --- Our native bridge (JNI entry points) ---
add_library(nativebridge SHARED NativeProcess.c)

target_include_directories(nativebridge PRIVATE
    .
    hev-socks5-tunnel/include
    hev-socks5-tunnel/src
    hev-socks5-tunnel/src/misc
)

target_link_libraries(nativebridge
    hev_socks5_tunnel  # hev-socks5-tunnel static library
    log                # Android logging (__android_log_print)
)

# Suppress some warnings from our code
target_compile_options(nativebridge PRIVATE
    -Wall -Wno-unused-parameter
)
