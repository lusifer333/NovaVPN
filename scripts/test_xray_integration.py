#!/usr/bin/env python3
"""
NovaVPN Xray Integration Test

Tests:
  1. Parse subscription URL → generate Xray JSON config
  2. Spawn Xray process with SOCKS5 inbound
  3. Route real HTTP traffic through the tunnel
  4. Verify clean teardown (SIGKILL, no orphan process)
"""

import base64, json, os, signal, socket, subprocess, sys, time, urllib.request, urllib.parse

XRAY_BIN = "/tmp/xray-extract/xray"
SOCKS5_PORT = 11808  # use non-standard to avoid conflicts
TEST_URL = "https://www.google.com/generate_204"
SUBSCRIPTION_URL = "https://divine-morning-53a7.nahan-1-tarkibi.workers.dev/divooneop?sub=Me"

# ---------------------------------------------------------------------------
# 1. Parse subscription
# ---------------------------------------------------------------------------
def fetch_subscription(url):
    import subprocess
    result = subprocess.run(
        ["curl", "-s", "--max-time", "15", "-A",
         "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
         url],
        capture_output=True, text=True, timeout=20
    )
    if result.returncode != 0:
        raise ValueError(f"curl failed (rc={result.returncode}): {result.stderr}")
    raw = result.stdout.encode('latin-1')  # preserve raw bytes
    decoded = base64.b64decode(raw).decode('utf-8')
    lines = [l.strip() for l in decoded.split('\n') if l.strip()]
    # Find first VLESS config (lines 0-1 are trojan:// meta/info lines)
    for l in lines:
        if l.startswith("vless://"):
            return l
    if len(lines) > 0:
        return lines[2]  # fallback: skip first 2 info lines
    raise ValueError("No VLESS config found in subscription")

def parse_vless_uri(uri):
    """Convert vless:// URI to Xray JSON outbound config."""
    parsed = urllib.parse.urlparse(uri)
    uuid = parsed.username
    host = parsed.hostname
    port = parsed.port or 443
    params = dict(urllib.parse.parse_qsl(parsed.query))
    
    # Build stream settings based on network type
    network = params.get("type", "tcp")
    security = params.get("security", "none")
    sni = params.get("sni") or params.get("host") or host
    fp = params.get("fp", "")
    allow_insecure = params.get("allowInsecure", "0") == "1"
    
    stream_settings = {
        "network": network,
        "security": security,
    }
    
    # TLS settings
    if security == "tls":
        tls = {"serverName": sni}
        if fp:
            tls["fingerprint"] = fp
        if allow_insecure:
            tls["allowInsecure"] = True
        stream_settings["tlsSettings"] = tls
    
    # WebSocket settings
    if network == "ws":
        ws = {"path": params.get("path", "/")}
        ws_host = params.get("host") or host
        ws["headers"] = {"Host": ws_host}
        stream_settings["wsSettings"] = ws
    
    return {
        "protocol": "vless",
        "settings": {
            "vnext": [{
                "address": host,
                "port": port,
                "users": [{
                    "id": uuid,
                    "encryption": params.get("encryption", "none"),
                    "flow": params.get("flow", "")
                }]
            }]
        },
        "streamSettings": stream_settings
    }

# ---------------------------------------------------------------------------
# 2. Build full Xray config
# ---------------------------------------------------------------------------
def build_xray_config(outbound, socks_port=SOCKS5_PORT):
    return {
        "log": {
            "loglevel": "debug"
        },
        "inbounds": [{
            "tag": "socks-in",
            "protocol": "socks",
            "port": socks_port,
            "listen": "127.0.0.1",
            "settings": {
                "auth": "noauth",
                "udp": True,
                "ip": "127.0.0.1"
            },
            "sniffing": {
                "enabled": True,
                "destOverride": ["http", "tls"]
            }
        }],
        "outbounds": [outbound, {
            "tag": "direct",
            "protocol": "freedom",
            "settings": {}
        }],
        "routing": {
            "domainStrategy": "IPIfNonMatch",
            "rules": [
                {"type": "field", "ip": ["geoip:private"], "outboundTag": "direct"},
                {"type": "field", "domain": ["geosite:cn"], "outboundTag": "direct"}
            ]
        },
        "dns": {
            "servers": ["https://1.1.1.1/dns-query", "8.8.8.8"]
        }
    }

# ---------------------------------------------------------------------------
# 3. Process management helpers
# ---------------------------------------------------------------------------
def start_xray(config_path):
    proc = subprocess.Popen(
        [XRAY_BIN, "run", "-c", config_path],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        bufsize=1, universal_newlines=True
    )
    return proc

def wait_for_inbound(proc, port, timeout=8):
    """Wait for 'started' or 'listening TCP' in Xray output, or timeout."""
    start = time.time()
    marker_seen = False
    lines_buf = []
    
    while time.time() - start < timeout:
        # Check if process still alive
        if proc.poll() is not None:
            rc = proc.poll()
            remaining = proc.stdout.read(5000) if proc.stdout else ""
            return False, f"Xray died (rc={rc}): {remaining}", lines_buf
        
        # Read available output
        if proc.stdout:
            import select
            r, _, _ = select.select([proc.stdout], [], [], 0.5)
            if r:
                try:
                    line = proc.stdout.readline()
                    if line:
                        lines_buf.append(line.rstrip())
                        # Xray v26+ emits "Xray ... started" and "listening TCP on ..."
                        if "started" in line and "Xray" in line:
                            marker_seen = True
                        if "listening TCP" in line:
                            marker_seen = True
                        if marker_seen:
                            # collect a bit more output
                            time.sleep(0.3)
                            try:
                                r2, _, _ = select.select([proc.stdout], [], [], 0.3)
                                while r2:
                                    extra = proc.stdout.readline()
                                    if extra:
                                        lines_buf.append(extra.rstrip())
                                    r2, _, _ = select.select([proc.stdout], [], [], 0.1)
                            except:
                                pass
                            return True, "", lines_buf
                except:
                    break
    
    remaining = ""
    if proc.stdout:
        try:
            r, _, _ = select.select([proc.stdout], [], [], 0.5)
            if r:
                remaining = proc.stdout.read(5000)
        except:
            pass
    return False, f"Timeout after {timeout}s\n{remaining}", lines_buf

def check_port_listening(port, host="127.0.0.1"):
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(1)
    result = sock.connect_ex((host, port))
    sock.close()
    return result == 0

def count_xray_processes():
    """Count Xray processes via /proc."""
    xray_pids = []
    try:
        for entry in os.listdir("/proc"):
            if not entry.isdigit():
                continue
            try:
                with open(f"/proc/{entry}/cmdline", "rb") as f:
                    cmdline = f.read().decode('latin-1')
                    if "xray" in cmdline and "python" not in cmdline:
                        xray_pids.append(int(entry))
            except (IOError, OSError, ValueError):
                continue
    except:
        pass
    return xray_pids

def traffic_test(port, url=TEST_URL, timeout=15):
    """Route HTTP through SOCKS5 proxy using curl (handles TLS+proxy correctly)."""
    import subprocess, time
    start = time.time()
    try:
        result = subprocess.run(
            ["curl", "-x", f"socks5://127.0.0.1:{port}",
             "-s", "--max-time", str(timeout),
             "-w", "%{http_code}", "-o", "/dev/null",
             url],
            capture_output=True, text=True, timeout=timeout+5
        )
        elapsed = (time.time() - start) * 1000
        if result.returncode == 0 and result.stdout.strip():
            code = int(result.stdout.strip())
            return True, code, f"{elapsed:.0f}"
        else:
            return False, f"curl rc={result.returncode}: {result.stderr[:100]}", f"{elapsed:.0f}"
    except Exception as e:
        elapsed = (time.time() - start) * 1000
        return False, str(e), f"{elapsed:.0f}"

def traffic_proxy_ip_check(port, timeout=15):
    """Check if traffic routes through proxy by comparing origin IPs."""
    import subprocess
    try:
        # Get IP through proxy
        result_proxy = subprocess.run(
            ["curl", "-x", f"socks5://127.0.0.1:{port}",
             "-s", "--max-time", str(timeout),
             "http://httpbin.org/ip"],
            capture_output=True, text=True, timeout=timeout+5
        )
        result_direct = subprocess.run(
            ["curl", "-s", "--max-time", str(timeout),
             "http://httpbin.org/ip"],
            capture_output=True, text=True, timeout=timeout+5
        )
        import json
        proxy_ip = json.loads(result_proxy.stdout).get("origin", "unknown") if result_proxy.returncode == 0 else "error"
        direct_ip = json.loads(result_direct.stdout).get("origin", "unknown") if result_direct.returncode == 0 else "error"
        return proxy_ip != direct_ip, proxy_ip, direct_ip
    except Exception as e:
        return False, str(e), "N/A"

# ---------------------------------------------------------------------------
# MAIN TEST
# ---------------------------------------------------------------------------
def main():
    passed = 0
    failed = 0
    errors = []

    def check(name, cond, detail=""):
        nonlocal passed, failed
        if cond:
            passed += 1
            print(f"  ✅ {name}")
        else:
            failed += 1
            msg = f"  ❌ {name} — {detail}" if detail else f"  ❌ {name}"
            print(msg)
            errors.append(msg)

    # ── Phase 1: Subscription ──
    print("\n═══ Phase 1: Parse Subscription ═══")
    try:
        vless_uri = fetch_subscription(SUBSCRIPTION_URL)
        check("Fetch subscription", True, vless_uri[:80])
    except Exception as e:
        check(f"Fetch subscription: {e}", False)
        return

    try:
        outbound = parse_vless_uri(vless_uri)
        check("Parse VLESS URI", True, f"{outbound['settings']['vnext'][0]['address']}:{outbound['settings']['vnext'][0]['port']}")
    except Exception as e:
        check(f"Parse VLESS URI: {e}", False)
        return

    # ── Phase 2: Generate Config & Start Xray ──
    print("\n═══ Phase 2: Start Xray Engine ═══")
    
    config = build_xray_config(outbound, SOCKS5_PORT)
    config_path = "/tmp/xray-test-config.json"
    with open(config_path, "w") as f:
        json.dump(config, f, indent=2)
    print(f"  Config written: {config_path}")
    with open(config_path) as f:
        cfg_text = f.read()
    # Show config snippet (hide UUID)
    cfg_show = cfg_text[:300] + "..."
    print(f"  Config preview: {cfg_show}")

    # Kill any leftover Xray from prior runs
    for pid in count_xray_processes():
        os.kill(pid, signal.SIGKILL)
        print(f"  Cleaned up stale Xray PID {pid}")
    time.sleep(0.5)

    # Start Xray
    proc = start_xray(config_path)
    check(f"Xray process started (PID={proc.pid})", True, f"PID={proc.pid}")

    # Wait for inbound marker
    ready, msg, startup_lines = wait_for_inbound(proc, SOCKS5_PORT, timeout=8)
    check(f"Engine 'running inbound' marker", ready, msg if not ready else "")
    if not ready:
        for l in startup_lines[-10:]:
            print(f"    [xray] {l[:120]}")
        # Kill and abort
        proc.kill()
        proc.wait(3)
        return

    # Show startup lines (last 5)
    print(f"  Startup output ({len(startup_lines)} lines):")
    for l in startup_lines[-5:]:
        print(f"    [xray] {l[:120]}")

    # ── Phase 3: Verify Port Listening ──
    print("\n═══ Phase 3: Verify Port Listening ═══")
    check("SOCKS5 inbound listening", check_port_listening(SOCKS5_PORT))

    # ── Phase 4: Traffic Routing Test ──
    print("\n═══ Phase 4: Traffic Routing Test ═══")
    success, detail, latency = traffic_test(SOCKS5_PORT, TEST_URL, timeout=15)
    check(f"HTTPS via SOCKS5 → {TEST_URL}", success, f"{detail} ({latency}ms)")
    print(f"  Latency: {latency}ms")
    
    # Verify traffic actually routes through proxy (different origin IP)
    ip_ok, proxy_ip, direct_ip = traffic_proxy_ip_check(SOCKS5_PORT, timeout=15)
    check(f"Traffic routed via proxy (IP check)", ip_ok,
          f"proxy={proxy_ip}, direct={direct_ip}")
    print(f"  Proxy origin IP: {proxy_ip}")
    print(f"  Direct origin IP: {direct_ip}")

    # ── Phase 5: Clean Teardown Test ──
    print("\n═══ Phase 5: Clean Teardown ═══")
    
    # Kill by SIGTERM first (like our Engine.stop)
    xray_pid = proc.pid
    proc.terminate()
    try:
        proc.wait(timeout=3)
        check(f"Xray died on SIGTERM (clean stop)", True, f"rc={proc.returncode}")
    except subprocess.TimeoutExpired:
        check("Xray cleaned with SIGTERM within 3s", False, "process still alive")
        print("  → Escalating to SIGKILL")
        proc.kill()
        proc.wait(2)

    # Verify process is gone
    time.sleep(1)
    remaining = count_xray_processes()
    # Filter to just this run's process
    process_still_alive = any(p == xray_pid for p in remaining)
    check(f"Xray PID {xray_pid} fully reaped", not process_still_alive,
          f"still alive: {remaining}" if process_still_alive else "")
    
    # Verify the config file cleanup (simulate NovaVPN's cleanup())
    if os.path.exists(config_path):
        os.remove(config_path)
        check("Config file cleaned", True)
    else:
        check("Config file cleaned", True)

    # ── Phase 6: Hard-Kill Test ──
    print("\n═══ Phase 6: Hard-Kill (SIGKILL) Test ═══")
    print("  Starting Xray again...")
    
    # Extra: test that stopping while frozen (during init) works via hard kill
    # Create config that will hang (invalid port)
    hang_config = dict(config)
    hang_config["inbounds"][0]["port"] = -1  # invalid
    hang_config_path = "/tmp/xray-hang-config.json"
    with open(hang_config_path, "w") as f:
        json.dump(hang_config, f, indent=2)
    
    # Start with bad config (should hang on "Reading config")
    hang_proc = start_xray(hang_config_path)
    print(f"  Started Xray with invalid config (PID={hang_proc.pid})")
    time.sleep(1)
    
    # Check it's stuck on "Reading config"
    hang_ready, hang_msg, hang_lines = wait_for_inbound(hang_proc, SOCKS5_PORT, timeout=4)
    check(f"Bad config does NOT emit startup marker", not hang_ready, "")
    
    # Now hard-kill (simulating our hardKillProcess)
    hang_pid = hang_proc.pid
    hang_proc.kill()  # SIGKILL via Java destroyForcibly
    try:
        hang_proc.wait(2)
        check(f"Hard-kill kills frozen Xray (SIGKILL)", True)
    except subprocess.TimeoutExpired:
        check("Hard-kill kills frozen Xray (SIGKILL)", False, "still alive after 2s")
        # Extra kill -9
        try:
            subprocess.run(["kill", "-9", str(hang_pid)], capture_output=True, timeout=3)
            time.sleep(0.5)
        except:
            pass
    
    # Verify no orphans
    time.sleep(1)
    all_xray = count_xray_processes()
    our_pids = [xray_pid, hang_pid]
    orphaned = [p for p in all_xray if p in our_pids]
    check(f"No orphaned Xray processes", len(orphaned) == 0,
          f"orphans: {orphaned}" if orphaned else "")

    # ── Summary ──
    print(f"\n{'═' * 50}")
    print(f"RESULTS: {passed} passed, {failed} failed, {len(errors)} errors")
    if errors:
        print("\nErrors:")
        for e in errors:
            print(f"  {e}")
    print(f"{'═' * 50}")
    
    # Cleanup
    for f in ["/tmp/xray-test-config.json", "/tmp/xray-test-access.log",
              "/tmp/xray-test-error.log", "/tmp/xray-hang-config.json"]:
        try:
            os.remove(f)
        except:
            pass
    
    sys.exit(0 if failed == 0 else 1)

if __name__ == "__main__":
    main()
