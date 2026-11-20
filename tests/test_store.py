"""Profile storage writes credentials; on Linux the file must stay private."""

import os
import stat

import pytest

from schnellvpn.core.store import Store


@pytest.mark.skipif(os.name != "posix", reason="POSIX permissions only")
def test_profile_file_and_dir_are_private(tmp_path):
    path = tmp_path / "cfg" / "profiles.json"
    Store(path=path).save()

    assert stat.S_IMODE(path.stat().st_mode) == 0o600
    assert stat.S_IMODE(path.parent.stat().st_mode) == 0o700
