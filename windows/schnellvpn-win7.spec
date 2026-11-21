# -*- mode: python ; coding: utf-8 -*-
from pathlib import Path

ROOT = Path(SPECPATH)

datas = [
    (str(ROOT / "vendor" / "xray-win7" / "xray.exe"), "xray"),
    (str(ROOT / "vendor" / "xray-win7" / "LICENSE"), "xray"),
]
icon = ROOT / "resources" / "schnellvpn.ico"

a = Analysis(
    [str(ROOT / "launcher.py")],
    pathex=[str(ROOT), str(ROOT.parent)],
    binaries=[],
    datas=datas,
    hiddenimports=[
        "PySide2.QtCore",
        "PySide2.QtGui",
        "PySide2.QtNetwork",
        "PySide2.QtWidgets",
    ],
    hookspath=[],
    runtime_hooks=[],
    excludes=["PySide6", "tkinter"],
    noarchive=False,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name="SchnellVPN-Win7",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    runtime_tmpdir=r"%LOCALAPPDATA%\SchnellVPN\runtime",
    console=False,
    disable_windowed_traceback=False,
    icon=str(icon) if icon.is_file() else None,
)
