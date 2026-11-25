# SchnellVPN for Windows

A Windows client for [SchnellVPN](../README.md), built with **Python + PySide6** and the same
**Xray-core** engine as the Android app. It is a port of the Android feature set that makes
sense on a desktop: subscriptions, real latency measurement, verified data flow and a
system proxy.

> Status: stable (1.6.3). Connection, system proxy, probes, profiles, subscription refresh,
> in-app updates and the live speed chart work. TUN mode and per-app split tunneling are not
> implemented yet (see *Limitations*).

Two builds are attached to every release:

| File | Runs on | Stack |
| --- | --- | --- |
| `SchnellVPN.exe` | Windows 10/11 (64-bit) | Python 3.12, PySide6 (Qt 6), Xray 26.x |
| `SchnellVPN-Win7.exe` | Windows 7, 8, 8.1 (32- and 64-bit) | Python 3.8 x86, PySide2 (Qt 5), Xray 1.8.3 |

Qt 6 needs Windows 10, so the Windows 7 build ships Qt 5 behind a small
`PySide6` → `PySide2` import shim; both build from the same source. The updater picks
the asset that matches the running build (`SchnellVPN-Win7.exe` for the Qt 5 build), so
each variant updates itself.

## Features

- **Protocols**: VLESS (TCP / WS / gRPC / HTTPUpgrade / XHTTP, TLS and Reality), VMess,
  Trojan, Shadowsocks (no plugins).
- **Subscriptions**: plain link lists, Base64 lists and v2rayN-style JSON; direct links and
  ready-made JSON outbounds are accepted too. Local configs (`127.0.0.1`) are supported.
  A **refresh** button re-fetches every stored subscription, keeps manual links, and leaves
  the old servers of a failed subscription in place.
- **Live speed chart**: download and upload are sampled once per second from Xray's
  `StatsService` and plotted for the last minute.
- **In-app updates**: the header button checks GitHub Releases for the newest Windows
  build, verifies the asset's SHA-256 from its digest, and swaps the running exe through a
  short-lived helper. The helper waits for the new build's boot marker, retries the launch
  and restores the previous exe if it never comes up (log: `updates/install.log`). Running
  from source opens the release page instead.
- **Connection log and bug reports**: the logs dialog lists every accepted connection
  (time, network, destination, source) the way v2rayN does, next to the raw app/xray logs,
  and can email a prepared report to the developer or copy it to the clipboard.
- **Real delay** for every config: a temporary Xray core exposes one SOCKS inbound per
  config and a real HTTP request is timed through it. Local configs are skipped.
- **"Connected" means data flows**: after the core starts, a real request must pass
  through the tunnel before the UI switches to connected. The tunnel is re-probed every
  20 s and the connection is dropped if data stops flowing.
- **Honest ping**: TCP or TLS-handshake latency measured on the physical network, never
  through the tunnel and never invented when the host is offline.
- **System proxy** (WinINET) set on connect and restored on disconnect, including after a
  crash (a restore point is written before touching the registry). The bypass list always
  contains the Windows default (localhost, 10/8, 172.16/12, 192.168/16, `<local>`) even if
  the machine had an empty `ProxyOverride`, so local and LAN traffic never enters the tunnel.
- **Browser-ready check**: the HTTP inbound (10809, what browsers use through the system
  proxy) is probed with a real plain-HTTP request before the app reports "connected"; a
  server that only carries SOCKS traffic is rejected with a clear message.
- **Sorted by real delay**: after a ping or real-delay test the server list is re-ordered
  fastest first (untested servers last) and saved to the profile. Measured values survive a
  subscription refresh (matched by link), so the list is already ordered when the new
  servers land.
- **Profiles**: independent server sets and selected servers per profile.
- **About and support**: version, engine, developer and links to the Telegram channel
  ([t.me/SchnellVPN](https://t.me/SchnellVPN)) and the repository.
- **Languages**: فارسی (RTL), English, Deutsch. Dark and light themes.
- **Single instance**: starting the app again (shortcut, autostart, second copy) does not
  spawn another core and window; it brings the running window to the front. A named mutex
  covers the whole Windows session, so even two different exe copies collapse into one.
- **Tray icon**, logs viewer and a packaged single-file executable in releases.

## Architecture

```
apps ──► WinINET system proxy ──► HTTP 127.0.0.1:10809 ┐
                                                       ├─► Xray-core ─► server
                         SOCKS 127.0.0.1:10808 ────────┘
```

| Module | Responsibility |
| --- | --- |
| `schnellvpn/core/links.py` | share-link and JSON-outbound parsing (VLESS/VMess/Trojan/SS) |
| `schnellvpn/core/config.py` | complete Xray configs, including per-config delay probes |
| `schnellvpn/core/subscription.py` | subscription download/parsing |
| `schnellvpn/core/netprobe.py` | SOCKS5 data-flow probe, TCP/TLS ping, port helpers |
| `schnellvpn/core/runner.py` | `xray.exe` lifecycle and logs |
| `schnellvpn/core/delay.py` | temporary multi-inbound core for real delay |
| `schnellvpn/core/speedstats.py` | live throughput from the StatsService API |
| `schnellvpn/core/updater.py` | release check, verified download and exe self-replace |
| `schnellvpn/core/sysproxy.py` | WinINET proxy with crash-safe restore |
| `schnellvpn/core/store.py` | profiles, servers and settings |
| `schnellvpn/ui/` | window, dialogs, speed chart, theme, translations |

The core is pure Python and unit-tested without Qt; the UI layer never talks to the network
directly.

## Run from source

```powershell
cd windows
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt

# the engine is not committed; put the Windows build in vendor/xray/
mkdir vendor\xray
Invoke-WebRequest `
  -Uri "https://github.com/XTLS/Xray-core/releases/latest/download/Xray-windows-64.zip" `
  -OutFile "$env:TEMP\xray.zip"
Expand-Archive "$env:TEMP\xray.zip" -DestinationPath vendor\xray -Force

python -m schnellvpn
```

Set `SCHNELLVPN_DATA` to use a different data directory and `SCHNELLVPN_XRAY` to point at a
specific `xray.exe`.

## Tests and build

```powershell
python -m pytest tests -q
.\build.ps1            # installs dev deps, downloads the core, runs tests, builds the exe
```

The executable is written to `dist\SchnellVPN.exe` with a SHA-256 printed at the end.
`build.ps1 -SkipInstall -SkipTests` is useful once the environment is ready.

The Windows 7 build needs a 32-bit Python 3.8 (e.g. at `C:\Python38-32`):

```powershell
.\build-win7.ps1 -Python C:\Python38-32\python.exe   # -> dist\SchnellVPN-Win7.exe
```

It installs `requirements-win7.txt` (PySide2 5.15), downloads Xray 1.8.3 (x86) and rejects
the result unless the PE header says x86 and subsystem 6.1 (`tools/check_pe.py`).

GitHub Actions (`.github/workflows/windows.yml`) builds both exes on tags and manual runs
and attaches them to the release.

## Where data lives

`%APPDATA%\SchnellVPN\` contains `profiles.json` (servers, including credentials),
`run\config.json` (the generated engine config), `run\proxy_backup.json` (system-proxy
restore point) and `logs\xray.log`. Configs are stored in plain text, exactly like the
Android client; protect the profile and avoid pasting real links into bug reports.

## Limitations / roadmap

- System proxy affects WinINET apps (browsers, most desktop apps). Apps that ignore the
  Windows proxy need TUN mode; that is the next step (Wintun + a service) and needs an
  administrator install.
- No per-app split tunneling on Windows yet. Workaround: Xray routing rules for domains.
- No QR-camera import (paste the link instead) and no auto-start at login yet.
- In-app updating replaces the executable in place, so it only self-installs from the
  packaged `SchnellVPN.exe`; running from source opens the release page instead.
- The live chart and its counters need the bundled Xray 26.x StatsService; a custom core
  without it still connects, but the chart stays empty.
- Xray 26.x refuses VLESS/Trojan links without TLS/Reality when the server address is a
  public IP. The app says so on connect, and the real-delay probe skips only that config
  instead of losing the delay of every server.
- The bundled `xray.exe` is downloaded at build time, never committed. The Windows 7
  build ships Xray 1.8.3, which predates the XHTTP transport, so XHTTP links only work
  in the Windows 10/11 build.
- Windows 7/8/8.1 users must take `SchnellVPN-Win7.exe`; the x64 `SchnellVPN.exe` needs
  Windows 10 or 11.

## Licenses

Xray-core is MPL-2.0; its `LICENSE` is bundled next to the executable. PySide6 is
LGPL-3.0. This client's source lives in this repository under the project's license.
