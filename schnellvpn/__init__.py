"""SchnellVPN for Windows - an Xray-core client.

The Windows 7 build ships PySide2 (Qt 5) because Qt 6 needs Windows 10; the
finder below lets every module keep importing PySide6 names unchanged.
"""

from __future__ import annotations

import importlib
import importlib.abc
import importlib.util
import sys

__version__ = "1.6.7"


class _Qt5Loader(importlib.abc.Loader):
    def __init__(self, module):
        self._module = module

    def create_module(self, spec):
        return self._module

    def exec_module(self, module):
        pass


class _Qt5Finder(importlib.abc.MetaPathFinder):
    """Resolve ``PySide6.*`` to the installed ``PySide2`` (legacy build only)."""

    def find_spec(self, fullname, path=None, target=None):
        if fullname != "PySide6" and not fullname.startswith("PySide6."):
            return None
        real = "PySide2" + fullname[len("PySide6"):]
        module = importlib.import_module(real)
        sys.modules[fullname] = module
        if real == "PySide2.QtWidgets":
            _alias_exec(module)
        return importlib.util.spec_from_loader(fullname, _Qt5Loader(module))


def _alias_exec(qtwidgets) -> None:
    """Qt 5 spells it ``exec_``; the app (and Qt 6) uses ``exec``."""
    for name in ("QApplication", "QDialog", "QMenu"):
        cls = getattr(qtwidgets, name, None)
        if cls is not None and not hasattr(cls, "exec"):
            cls.exec = cls.exec_


def _use_pyside2() -> bool:
    if "PySide6" in sys.modules:
        return False
    try:
        if importlib.util.find_spec("PySide6") is not None:
            return False
        return importlib.util.find_spec("PySide2") is not None
    except (ImportError, ValueError):
        return False


if _use_pyside2():
    sys.meta_path.insert(0, _Qt5Finder())
