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


_exit_code = 0


def pytest_sessionfinish(session, exitstatus):
    global _exit_code
    _exit_code = int(exitstatus)


def pytest_unconfigure(config):
    # CPython 3.10 + PySide6 aborts in AIX/Linux finalization with
    # "none_dealloc: deallocating None" after every test has passed. Leave the
    # process before that GC pass; the real exit status is preserved.
    if os.environ.get("CI") and sys.platform.startswith("linux"):
        sys.stdout.flush()
        sys.stderr.flush()
        os._exit(_exit_code)
