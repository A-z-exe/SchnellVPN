# SchnellVPN for Linux

The same Python client as the Windows build, packaged for Linux desktops:
subscriptions, profiles, real ping/delay tests, verified data flow and a system
proxy, with the Xray-core engine bundled.

> Status: stable (1.5.0). Works on X11 and Wayland desktops with Qt 6; the system
> proxy is applied through GSettings (GNOME and anything that follows
> `org.gnome.system.proxy`). TUN mode, split tunneling and a KDE-only proxy
> backend are not implemented yet.

## Install

Download one of the release assets:

| File | Use |
| --- | --- |
| `SchnellVPN-<version>-x86_64.AppImage` | Run directly: `chmod +x` it and launch. |
| `SchnellVPN_<version>_amd64.deb` | `sudo apt install ./SchnellVPN_<version>_amd64.deb` |

The deb installs `/usr/bin/SchnellVPN`, the desktop entry and the icon. The
single-file binary in the release is the same executable without an installer.

## System proxy

On connect the client sets:

- HTTP/HTTPS proxy `127.0.0.1:10809` (the exact port the app opened)
- SOCKS proxy `127.0.0.1:10808`
- `ignore-hosts` for localhost and the private ranges, so LAN traffic never
  enters the tunnel

The previous settings are saved to `~/.config/SchnellVPN/run/proxy_backup.json`
and restored on disconnect or on the next launch after a crash. Apps that read
GSettings (GNOME apps, Firefox with "Use system proxy settings", Chromium via
libproxy) pick the change up live. Without a GSettings schema the tunnel still
comes up on 127.0.0.1:10808/10809 for manual configuration.

## Run from source

```bash
cd linux
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt

mkdir -p vendor/xray-linux
curl -fL -o /tmp/xray.zip https://github.com/XTLS/Xray-core/releases/latest/download/Xray-linux-64.zip
unzip -o /tmp/xray.zip -d vendor/xray-linux
chmod +x vendor/xray-linux/xray

python launcher.py
```

`SCHNELLVPN_DATA` picks another profile directory; `SCHNELLVPN_XRAY` points at a
specific `xray` binary. Profiles live in `~/.config/SchnellVPN/`.

## Build

```bash
cd linux
./build.sh                 # dist/SchnellVPN, dist/*.deb, dist/*.AppImage
BUILD_APPIMAGE=0 ./build.sh   # skip the AppImage (no curl download for appimagetool)
```

`build.sh` downloads Xray at build time (never committed), renders the icon,
runs PyInstaller and assembles the deb (and AppImage when enabled). The GitHub
workflow builds the same artifacts on tags.

## Limitations

- No TUN mode: only apps that honor the system proxy or your manual settings
  use the tunnel. A tun2socks helper needs root and is the next step.
- KDE/XFCE store proxy settings in their own backends; set them manually to
  `127.0.0.1:10809`.
- Tray support needs a StatusNotifier host; without one, closing the window
  quits instead of hiding (so the app cannot get lost).
- XHTTP links need the Xray 26.x bundles; release builds download the latest
  Xray for x86_64.

Xray-core is MPL-2.0 (LICENSE bundled next to the binary). PySide6 is LGPL-3.0.
