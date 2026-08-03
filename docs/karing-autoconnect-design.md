# Karing-style auto-connect + config-test on Xray — Design (v0.17)

## What Karing actually does (verified from source)
1. **urltest is an in-core outbound** (sing-box `urltest` type): the CORE continuously
   tests every server in the group with a REAL HTTP 204 request and routes to the best.
2. App polls Clash API every 1s for "which server is selected now" (`getCurrentServerForUrltest`),
   persists it as `selectDefault`, updates WAN IP display when it changes.
3. Settings: `urlTest` URL (default gstatic 204, selectable from 6 URLs),
   `urlTestTimeout` 1–15s (default 15), `autoConnectAfterLaunch`, `autoConnectAtBoot`.
4. Manual connect: if current group is the urltest group → connect to the urltest outbound
   (core picks the best at connect time, keeps re-testing).

## Xray reality (verified from XTLS/Xray-core source)
- Xray has NO `urltest` outbound. `balancer` strategies = random/leastLoad/leastPing/
  roundRobin — none do REAL HTTP 204 health testing.
- ⇒ We must implement the Karing urltest layer OURSELVES, on top of the existing
  RealDelayProber machinery, but as a PERSISTENT in-session loop, not a one-shot fill.

## Design for NovaVPN

### A. Persistent in-session urltest (auto-connect, Karing-style)
In `NovaVpnService` while Connected, run a background coroutine (inside the session's
`holdConnection` scope — same lifecycle as the settings watcher):

```
urltestLoop:
  every URLTEST_INTERVAL_MS (default 10s):
    1. candidates = servers of the CURRENT profile group (subscription), from repository
    2. boot ONE shared probe xray session with all candidates (existing
       XrayRealDelayProber.start(servers, options=REAL settings)) — ports 10818+i
    3. probe ALL in parallel (existing bounded wave) → latency per server, 204 required
    4. best = fastest healthy (lowest e2eMs with ok=true)
    5. if best != currently-connected server && connection still Connected:
         switch tunnels in-place: build new engine config for best, restart engine
         with the SAME tun fd (bridge stays up — no VPN drop), update UI server name
    6. stop() the probe session
```

Key property (Karing parity): **the VPN never drops** — the TUN/bridge session stays,
only the engine's proxy outbound is swapped. We do this by keeping the bridge (hev-socks5-tunnel)
running and restarting only the xray engine (config.json rewritten + process restart).
The socks5 upstream the bridge points to stays 127.0.0.1:10808, so the swap is invisible
to the bridge.

When best == current → no-op (stay). When a server dies mid-session → next cycle picks
a live one automatically. This IS the Karing auto-connect behavior: always on the best
live server, tested continuously.

- Trigger: `enableAutoConnect` setting ON (existing setting) + Connected. If OFF → no loop.
- The loop is cancelled with the session (coroutineScope child) — no leak.
- Probe session ports 10818+ never collide with the engine's 10808 (existing invariant).

### B. Config-test screen (manual, Karing urltest settings parity)
New screen (from Home quick actions → "Test Configs" / or in Servers screen):

- URL selector: the 6 Karing URLs (gstatic 204 default, msftconnecttest, cloudflare 204,
  amazon checkip, ubuntu, firefox) — user picks which URL the test uses.
- Timeout setting: 1–15s (default 15, Karing default) — persisted in AppSettings.
- "Test All" button: probes every server in the mine/catalog with the REAL settings
  (fragment/keepalive), shows live results: 🚀 <ms> or ✗ per server, sorted by latency.
  This reuses the existing per-server probe machinery but with the chosen URL+timeout.
- Reuses `ProbeOptions` (real settings) + `TrafficProbe.httpRoundTrip` (already pure JVM,
  accepts host/path/timeout params — just needs the URL plumbing).

### C. Settings additions (AppSettings + SettingsSerializer migration v2)
- `urlTestUrl: String = "https://www.gstatic.com/generate_204"` (or index into list)
- `urlTestTimeoutSec: Int = 15`
- `autoConnectAfterLaunch: Boolean = true` (Karing default true; existing
  `enableAutoConnect` stays for the loop)
- Migration: settingsVersion 0→1 already done; add v1→2 overlay for the 3 new fields.

### D. Files touched
- core/domain: AppSettings + migration (Models.kt, SettingsSerializer.kt), ProbeOptions
  gains `url: String`, `timeoutMs: Int` (defaults = gstatic 204 / 15s).
- engine/xray: TrafficProbe.httpRoundTrip already accepts host/path/timeout — plumb URL.
  XrayRealDelayProber.start gains url/timeout pass-through. buildMineConfig unchanged.
- core/data: FillMineUseCase/MineFiller pass options.url/timeoutMs into the prober.
- feature/servers: config-test screen + Test All (URL picker, timeout, live results).
- app/service: NovaVpnService urltestLoop (session-scoped, engine-swap, no VPN drop).

### E. Risks / acceptance
- Engine-swap while bridge up: must write new config.json + restart engine process;
  socks5 stays 127.0.0.1:10808 → bridge unaffected. Test on device: connect, kill the
  connected server (or toggle), watch the app switch servers WITHOUT the VPN dropping.
- Probe session (10818+) co-exists with engine session (10808) — already proven by the
  fill feature.
- URL/timeout default = Karing defaults; user can change in the new screen.

### F. Tests
- SettingsSerializer: v1→v2 migration overlays urlTestUrl/timeoutSec/autoConnectAfterLaunch.
- MineFiller/ProbeOptions: url+timeoutMs plumb into probe (fake asserts received params).
- NovaVpnService urltestLoop: best-server swap picks lowest e2eMs healthy; no-op when
  best==current; loop stops on disconnect (unit-testable via injected prober fake).
