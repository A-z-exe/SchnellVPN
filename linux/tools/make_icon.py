"""Renders resources/schnellvpn.png from the app's vector logo."""

from __future__ import annotations

import os
import sys
from pathlib import Path

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT.parent))

import schnellvpn  # noqa: E402,F401  (installs the PySide2 shim on old Qt builds)
from PySide6.QtWidgets import QApplication  # noqa: E402

from schnellvpn.ui.theme import make_logo  # noqa: E402


def main() -> int:
    QApplication(sys.argv)
    resources = ROOT / "resources"
    resources.mkdir(exist_ok=True)
    png = resources / "schnellvpn.png"
    if not make_logo(256).save(str(png), "PNG"):
        print("could not write PNG")
        return 1
    print(f"wrote {png}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
