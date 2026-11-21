import os
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")


@pytest.fixture(scope="session")
def qt_app():
    """One QApplication for the whole run.

    Creating it per module lets the first wrapper be collected in between,
    which destroys the C++ application; the second one then crashes Qt at
    interpreter shutdown.
    """
    from PySide6.QtWidgets import QApplication

    return QApplication.instance() or QApplication([])
