from schnellvpn.core.sysproxy import DEFAULT_BYPASS, _bypass


def test_empty_override_gets_windows_default_list():
    result = _bypass("")
    assert result == DEFAULT_BYPASS
    assert "localhost" in result
    assert "192.168.*" in result
    assert "<local>" in result


def test_custom_override_keeps_entries_and_adds_local():
    result = _bypass("example.com;10.*")
    assert result.startswith("example.com;10.*")
    assert "<local>" in result


def test_override_with_localhost_not_duplicated():
    result = _bypass("localhost;foo.com")
    assert result.count("localhost") == 1
    assert "<local>" in result
