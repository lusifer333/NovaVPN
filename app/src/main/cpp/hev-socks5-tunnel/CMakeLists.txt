cmake_minimum_required(VERSION 3.22.1)

# ============================================================================
# hev-socks5-tunnel v2.16.0 — static library for Android (NDK)
# Official upstream: https://github.com/heiher/hev-socks5-tunnel
# Built with ENABLE_LIBRARY to expose C API (hev_socks5_tunnel_main_from_file, etc.)
# ============================================================================

# --- Project root inside the extracted source tree ---
set(HEV_SRC "${CMAKE_CURRENT_SOURCE_DIR}")

# --- Include directories ---
set(HEV_INCLUDES
    "${HEV_SRC}/src"
    "${HEV_SRC}/src/misc"
    "${HEV_SRC}/src/core/include"
    "${HEV_SRC}/third-part/yaml/include"
    "${HEV_SRC}/third-part/lwip/src/include"
    "${HEV_SRC}/third-part/lwip/src/ports/include"
    "${HEV_SRC}/third-part/hev-task-system/include"
    "${HEV_SRC}/include"
)

# --- Compile definitions ---
set(HEV_DEFINES
    ENABLE_LIBRARY           # expose C API, exclude main()

    # hev-task-system configs (from configs.mk)
    ENABLE_STACK_OVERFLOW_DETECTION
    ENABLE_MEMALLOC_SLICE
    ENABLE_IO_SPLICE_SYSCALL
    CONFIG_STACK_BACKEND=STACK_MMAP
    CONFIG_STACK_OVERFLOW_DETECTION=1
    CONFIG_MEMALLOC_SLICE_ALIGN=16
    CONFIG_MEMALLOC_SLICE_MAX_SIZE=4096
    CONFIG_MEMALLOC_SLICE_MAX_COUNT=1000
    CONFIG_SCHED_CLOCK=CLOCK_NONE

    # libyaml version info (from configs.mk)
    YAML_VERSION_MAJOR=0
    YAML_VERSION_MINOR=2
    YAML_VERSION_PATCH=5
    YAML_VERSION_STRING="0.2.5"

    # lwIP port: skip fd_set typedef on NDK 26+ (system <sys/select.h> provides it)
    FD_SET_DEFINED=1

    $<$<CONFIG:Debug>:ENABLE_DEBUG>  # debug logging
)

# ============================================================================
# Collect all source files (recursive)
# ============================================================================

set(YAML_SOURCES
    "${HEV_SRC}/third-part/yaml/src/api.c"
    "${HEV_SRC}/third-part/yaml/src/reader.c"
    "${HEV_SRC}/third-part/yaml/src/writer.c"
    "${HEV_SRC}/third-part/yaml/src/parser.c"
    "${HEV_SRC}/third-part/yaml/src/loader.c"
    "${HEV_SRC}/third-part/yaml/src/emitter.c"
    "${HEV_SRC}/third-part/yaml/src/scanner.c"
    "${HEV_SRC}/third-part/yaml/src/dumper.c"
)

set(LWIP_SOURCES
    "${HEV_SRC}/third-part/lwip/src/core/def.c"
    "${HEV_SRC}/third-part/lwip/src/core/init.c"
    "${HEV_SRC}/third-part/lwip/src/core/mem.c"
    "${HEV_SRC}/third-part/lwip/src/core/memp.c"
    "${HEV_SRC}/third-part/lwip/src/core/netif.c"
    "${HEV_SRC}/third-part/lwip/src/core/pbuf.c"
    "${HEV_SRC}/third-part/lwip/src/core/raw.c"
    "${HEV_SRC}/third-part/lwip/src/core/stats.c"
    "${HEV_SRC}/third-part/lwip/src/core/sys.c"
    "${HEV_SRC}/third-part/lwip/src/core/tcp.c"
    "${HEV_SRC}/third-part/lwip/src/core/tcp_in.c"
    "${HEV_SRC}/third-part/lwip/src/core/tcp_out.c"
    "${HEV_SRC}/third-part/lwip/src/core/timeouts.c"
    "${HEV_SRC}/third-part/lwip/src/core/udp.c"
    "${HEV_SRC}/third-part/lwip/src/core/ip.c"
    "${HEV_SRC}/third-part/lwip/src/core/inet_chksum.c"
    "${HEV_SRC}/third-part/lwip/src/core/dns.c"
    "${HEV_SRC}/third-part/lwip/src/core/altcp.c"
    "${HEV_SRC}/third-part/lwip/src/core/altcp_alloc.c"
    "${HEV_SRC}/third-part/lwip/src/core/altcp_tcp.c"
    "${HEV_SRC}/third-part/lwip/src/core/ipv4/autoip.c"
    "${HEV_SRC}/third-part/lwip/src/core/ipv4/dhcp.c"
    "${HEV_SRC}/third-part/lwip/src/core/ipv4/etharp.c"
    "${HEV_SRC}/third-part/lwip/src/core/ipv4/icmp.c"
    "${HEV_SRC}/third-part/lwip/src/core/ipv4/igmp.c"
    "${HEV_SRC}/third-part/lwip/src/core/ipv4/ip4.c"
    "${HEV_SRC}/third-part/lwip/src/core/ipv4/ip4_addr.c"
    "${HEV_SRC}/third-part/lwip/src/core/ipv4/ip4_frag.c"
    "${HEV_SRC}/third-part/lwip/src/core/ipv4/acd.c"
    "${HEV_SRC}/third-part/lwip/src/core/ipv6/dhcp6.c"
    "${HEV_SRC}/third-part/lwip/src/core/ipv6/ethip6.c"
    "${HEV_SRC}/third-part/lwip/src/core/ipv6/icmp6.c"
    "${HEV_SRC}/third-part/lwip/src/core/ipv6/inet6.c"
    "${HEV_SRC}/third-part/lwip/src/core/ipv6/ip6.c"
    "${HEV_SRC}/third-part/lwip/src/core/ipv6/ip6_addr.c"
    "${HEV_SRC}/third-part/lwip/src/core/ipv6/ip6_frag.c"
    "${HEV_SRC}/third-part/lwip/src/core/ipv6/mld6.c"
    "${HEV_SRC}/third-part/lwip/src/core/ipv6/nd6.c"
    # lwIP port layer
    "${HEV_SRC}/third-part/lwip/src/ports/lib/mem.c"
)

set(HEV_TASK_SOURCES
    "${HEV_SRC}/third-part/hev-task-system/src/lib/misc/hev-debugger.c"
    "${HEV_SRC}/third-part/hev-task-system/src/lib/list/hev-list.c"
    "${HEV_SRC}/third-part/hev-task-system/src/lib/object/hev-object.c"
    "${HEV_SRC}/third-part/hev-task-system/src/lib/object/hev-object-atomic.c"
    "${HEV_SRC}/third-part/hev-task-system/src/lib/rbtree/hev-rbtree.c"
    "${HEV_SRC}/third-part/hev-task-system/src/lib/rbtree/hev-rbtree-cached.c"
    "${HEV_SRC}/third-part/hev-task-system/src/lib/io/basic/hev-task-io.c"
    "${HEV_SRC}/third-part/hev-task-system/src/lib/io/buffer/hev-circular-buffer.c"
    "${HEV_SRC}/third-part/hev-task-system/src/lib/io/pipe/hev-task-io-pipe.c"
    "${HEV_SRC}/third-part/hev-task-system/src/lib/io/poll/hev-task-io-poll.c"
    "${HEV_SRC}/third-part/hev-task-system/src/lib/io/socket/hev-task-io-socket.c"
    "${HEV_SRC}/third-part/hev-task-system/src/lib/dns/hev-task-dns.c"
    "${HEV_SRC}/third-part/hev-task-system/src/lib/dns/hev-task-dns-proxy.c"
    "${HEV_SRC}/third-part/hev-task-system/src/kern/core/hev-task-system.c"
    "${HEV_SRC}/third-part/hev-task-system/src/kern/core/hev-task-system-schedule.c"
    "${HEV_SRC}/third-part/hev-task-system/src/kern/io/hev-task-io-reactor-epoll.c"
    "${HEV_SRC}/third-part/hev-task-system/src/kern/time/hev-task-timer.c"
    "${HEV_SRC}/third-part/hev-task-system/src/kern/task/hev-task.c"
    "${HEV_SRC}/third-part/hev-task-system/src/kern/task/hev-task-executer.c"
    "${HEV_SRC}/third-part/hev-task-system/src/kern/task/hev-task-stack-mmap.c"
    "${HEV_SRC}/third-part/hev-task-system/src/kern/task/hev-task-stack-heap.c"
    "${HEV_SRC}/third-part/hev-task-system/src/kern/task/hev-task-call.c"
    "${HEV_SRC}/third-part/hev-task-system/src/kern/itc/hev-task-channel.c"
    "${HEV_SRC}/third-part/hev-task-system/src/kern/itc/hev-task-channel-select.c"
    "${HEV_SRC}/third-part/hev-task-system/src/kern/sync/hev-task-mutex.c"
    "${HEV_SRC}/third-part/hev-task-system/src/kern/sync/hev-task-cond.c"
    "${HEV_SRC}/third-part/hev-task-system/src/kern/aide/hev-task-aide.c"
    "${HEV_SRC}/third-part/hev-task-system/src/mem/api/hev-memory-allocator-api.c"
    "${HEV_SRC}/third-part/hev-task-system/src/mem/base/hev-memory-allocator.c"
    "${HEV_SRC}/third-part/hev-task-system/src/mem/simple/hev-memory-allocator-simple.c"
    "${HEV_SRC}/third-part/hev-task-system/src/mem/slice/hev-memory-allocator-slice.c"
    # Architecture-specific assembly (auto-included by hev-task-execute.S)
    "${HEV_SRC}/third-part/hev-task-system/src/kern/task/hev-task-execute.S"
)

set(TUNNEL_SOURCES
    "${HEV_SRC}/src/hev-main.c"
    "${HEV_SRC}/src/hev-config.c"
    "${HEV_SRC}/src/hev-socks5-tunnel.c"
    "${HEV_SRC}/src/hev-tunnel-linux.c"
    "${HEV_SRC}/src/hev-socks5-session-tcp.c"
    "${HEV_SRC}/src/hev-socks5-session-udp.c"
    "${HEV_SRC}/src/hev-mapped-dns.c"
    "${HEV_SRC}/src/core/src/hev-socks5-misc.c"
    "${HEV_SRC}/src/core/src/hev-socks5.c"
    "${HEV_SRC}/src/core/src/hev-socks5-tcp.c"
    "${HEV_SRC}/src/core/src/hev-socks5-server.c"
    "${HEV_SRC}/src/core/src/hev-socks5-client-tcp.c"
    "${HEV_SRC}/src/core/src/hev-socks5-client-udp.c"
    "${HEV_SRC}/src/core/src/hev-socks5-authenticator.c"
    "${HEV_SRC}/src/core/src/hev-socks5-logger.c"
    "${HEV_SRC}/src/misc/hev-exec.c"
    "${HEV_SRC}/src/misc/hev-logger.c"
    "${HEV_SRC}/src/misc/hev-ring-buffer.c"
    "${HEV_SRC}/src/misc/hev-utils.c"
)

# ============================================================================
# Static library target
# ============================================================================
add_library(hev_socks5_tunnel STATIC
    ${YAML_SOURCES}
    ${LWIP_SOURCES}
    ${HEV_TASK_SOURCES}
    ${TUNNEL_SOURCES}
)

target_include_directories(hev_socks5_tunnel PRIVATE
    ${HEV_INCLUDES}
)

target_compile_definitions(hev_socks5_tunnel PRIVATE
    ${HEV_DEFINES}
)

target_link_libraries(hev_socks5_tunnel
    log    # Android logging
    c      # standard C library
)

# Suppress warnings from third-party code
target_compile_options(hev_socks5_tunnel PRIVATE
    -Wall -Wno-unused-parameter -Wno-unused-variable -Wno-sign-compare
    -Wno-pointer-sign -Wno-deprecated-declarations
    -Wno-cast-function-type
)
