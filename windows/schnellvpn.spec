# -*- mode: python ; coding: utf-8 -*-
from pathlib import Path

ROOT = Path(SPECPATH)

datas = [
    (str(ROOT / "vendor" / "xray" / "xray.exe"), "xray"),
    (str(ROOT / "vendor" / "xray" / "LICENSE"), "xray"),
    (str(ROOT / "resources" / "schnellvpn.png"), "resources"),
    (str(ROOT / "resources" / "schnellvpn_connected.png"), "resources"),
]
icon = ROOT / "resources" / "schnellvpn.ico"

a = Analysis(
    [str(ROOT / "launcher.py")],
    pathex=[str(ROOT), str(ROOT.parent)],
    binaries=[],
    datas=datas,
    hiddenimports=[],
    hookspath=[],
    runtime_hooks=[],
    excludes=["tkinter"],
    noarchive=False,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name="SchnellVPN",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    runtime_tmpdir=r"%LOCALAPPDATA%\SchnellVPN\runtime",
    console=False,
    disable_windowed_traceback=False,
    icon=str(icon) if icon.is_file() else None,
)
