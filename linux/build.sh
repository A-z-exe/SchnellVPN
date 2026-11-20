#!/usr/bin/env bash
# Builds the Linux client: one-file binary, .deb and (optionally) an AppImage.
# Needs Python 3.9+ with venv-capable pip; run on Ubuntu 22.04+ for releases.
set -euo pipefail

cd "$(dirname "$0")"
ROOT="$(cd .. && pwd)"
PYTHON="${PYTHON:-python3}"
XRAY_VERSION="${XRAY_VERSION:-latest}"

VERSION="$("$PYTHON" -c "import sys; sys.path.insert(0, '$ROOT'); import schnellvpn; print(schnellvpn.__version__)")"
echo "SchnellVPN $VERSION"

if [ ! -x vendor/xray-linux/xray ]; then
    echo "Fetching Xray (linux-64, ${XRAY_VERSION})..."
    mkdir -p vendor/xray-linux
    curl -fL -o /tmp/Xray-linux-64.zip \
        "https://github.com/XTLS/Xray-core/releases/${XRAY_VERSION}/download/Xray-linux-64.zip"
    unzip -o -q /tmp/Xray-linux-64.zip -d vendor/xray-linux
    chmod +x vendor/xray-linux/xray
fi
vendor/xray-linux/xray version | head -1

"$PYTHON" -m pip install -q -r requirements.txt pyinstaller
"$PYTHON" tools/make_icon.py
"$PYTHON" -m PyInstaller schnellvpn.spec --noconfirm --clean

install -d dist/deb/usr/bin dist/deb/usr/share/applications \
    dist/deb/usr/share/icons/hicolor/256x256/apps dist/deb/DEBIAN
install -m 755 dist/SchnellVPN dist/deb/usr/bin/SchnellVPN
install -m 644 packaging/schnellvpn.desktop dist/deb/usr/share/applications/schnellvpn.desktop
install -m 644 resources/schnellvpn.png dist/deb/usr/share/icons/hicolor/256x256/apps/schnellvpn.png

cat > dist/deb/DEBIAN/control <<EOF
Package: schnellvpn
Version: ${VERSION}
Architecture: amd64
Maintainer: A-z-exe <a.z.exe@proton.me>
Depends: libc6, libdbus-1-3, libfontconfig1, libfreetype6, libgl1, libegl1, libxkbcommon-x11-0, libxcb-cursor0, libxcb-icccm4, libxcb-image0, libxcb-keysyms1, libxcb-render-util0, libxcb-shape0
Section: net
Priority: optional
Description: Fast Xray-core VPN client
 Subscriptions, real latency tests and a system proxy for GNOME desktops.
EOF

dpkg-deb --build --root-owner-group dist/deb "dist/SchnellVPN_${VERSION}_amd64.deb"

if [ "${BUILD_APPIMAGE:-1}" = "1" ]; then
    install -d dist/AppDir/usr/bin dist/AppDir/usr/share/icons/hicolor/256x256/apps
    install -m 755 dist/SchnellVPN dist/AppDir/usr/bin/SchnellVPN
    install -m 644 packaging/schnellvpn.desktop dist/AppDir/schnellvpn.desktop
    install -m 644 resources/schnellvpn.png \
        dist/AppDir/usr/share/icons/hicolor/256x256/apps/schnellvpn.png
    install -m 644 resources/schnellvpn.png dist/AppDir/schnellvpn.png
    ln -sf schnellvpn.png dist/AppDir/.DirIcon
    cat > dist/AppDir/AppRun <<'EOF'
#!/bin/sh
exec "${APPDIR}/usr/bin/SchnellVPN" "$@"
EOF
    chmod +x dist/AppDir/AppRun
    if [ ! -x /tmp/appimagetool ]; then
        curl -fL -o /tmp/appimagetool \
            "https://github.com/AppImage/AppImageKit/releases/download/continuous/appimagetool-x86_64.AppImage"
        chmod +x /tmp/appimagetool
    fi
    ARCH=x86_64 VERSION="$VERSION" /tmp/appimagetool --appimage-extract-and-run \
        dist/AppDir "dist/SchnellVPN-${VERSION}-x86_64.AppImage"
fi

echo
sha256sum dist/SchnellVPN dist/SchnellVPN_*_amd64.deb dist/*.AppImage 2>/dev/null || true
