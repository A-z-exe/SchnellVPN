"""Renders resources/schnellvpn.ico from the app's vector logo."""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from PySide6.QtWidgets import QApplication  # noqa: E402

from schnellvpn.ui.theme import make_logo  # noqa: E402


def main() -> int:
    QApplication(sys.argv)
    resources = ROOT / "resources"
    resources.mkdir(exist_ok=True)
    png = resources / "schnellvpn.png"
    ico = resources / "schnellvpn.ico"

    pixmap = make_logo(256)
    if not pixmap.save(str(png), "PNG"):
        print("could not write PNG")
        return 1
    if not pixmap.save(str(ico), "ICO"):
        print("could not write ICO")
        return 1
    print(f"wrote {ico}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
