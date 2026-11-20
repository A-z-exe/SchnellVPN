from schnellvpn.core.speedstats import format_rate, parse_totals, rates


def test_parse_totals_sums_socks_and_http_but_not_api():
    payload = """
    {
      "stat": [
        {"name": "inbound>>>socks>>>traffic>>>uplink", "value": "100"},
        {"name": "inbound>>>socks>>>traffic>>>downlink", "value": "400"},
        {"name": "inbound>>>http>>>traffic>>>uplink", "value": 50},
        {"name": "inbound>>>http>>>traffic>>>downlink", "value": "600"},
        {"name": "inbound>>>api>>>traffic>>>uplink", "value": "9999"},
        {"name": "outbound>>>proxy>>>traffic>>>uplink", "value": "777"}
      ]
    }
    """
    assert parse_totals(payload) == (1000, 150)


def test_parse_totals_handles_empty_and_garbage():
    assert parse_totals("{}") == (0, 0)
    assert parse_totals("Stats not found") == (0, 0)


def test_rates_need_two_samples():
    assert rates(None, (10, 10), 1.0) is None
    assert rates((0, 0), (0, 0), 0) is None
    assert rates((0, 0), (2048, 512), 2.0) == (1024.0, 256.0)


def test_rates_ignore_counter_resets():
    assert rates((500, 500), (100, 100), 1.0) == (0.0, 0.0)


def test_format_rate_units():
    assert format_rate(512) == "512 B/s"
    assert format_rate(1536) == "2 KB/s"
    assert format_rate(2.5 * 1024 * 1024) == "2.5 MB/s"
