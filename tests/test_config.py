import pytest

from schnellvpn.core.config import build_config, build_delay_probe_config
from schnellvpn.core.links import LinkError

UUID = "b831381d-6324-4d53-ad4f-8cda48b30811"
VLESS = f"vless://{UUID}@1.2.3.4:443?type=tcp&security=tls&sni=example.com#T"
LOCAL = f"vless://{UUID}@127.0.0.1:1080?type=tcp&security=none#L"
PLAIN_PUBLIC = f"vless://{UUID}@8.8.8.8:9?type=tcp&security=none#dead"
PLAIN_PRIVATE = f"vless://{UUID}@10.0.0.5:9?type=tcp&security=none#lan"
PLAIN_DOMAIN = f"vless://{UUID}@example.com:9?type=tcp&security=none#d"
ENCRYPTED = f"vless://{UUID}@8.8.8.8:9?type=tcp&security=none&encryption=mlkem768x25519plus#e"


def test_main_config_has_socks_http_and_routing():
    config = build_config(VLESS, 10808, 10809)
    tags = [inbound["tag"] for inbound in config["inbounds"]]
    assert tags == ["socks", "http"]
    assert config["inbounds"][1]["listen"] == "127.0.0.1"
    assert [out["tag"] for out in config["outbounds"]] == ["proxy", "DIRECT", "BLOCK"]
    assert config["outbounds"][0]["protocol"] == "vless"
    rules = config["routing"]["rules"]
    assert {
        "type": "field",
        "network": "udp",
        "port": "443",
        "outboundTag": "BLOCK",
    } in rules
    assert config["log"]["loglevel"] == "warning"


def test_main_config_can_skip_http_inbound():
    config = build_config(VLESS, 10808, 0)
    assert [inbound["tag"] for inbound in config["inbounds"]] == ["socks"]


def test_delay_config_one_inbound_per_remote_link():
    config, ports = build_delay_probe_config([VLESS, LOCAL], 22000)
    assert ports == [22000, None]
    assert len(config["inbounds"]) == 1
    inbound = config["inbounds"][0]
    assert inbound["port"] == 22000
    rules = config["routing"]["rules"]
    assert rules == [
        {
            "type": "field",
            "inboundTag": ["delay_in_0"],
            "outboundTag": "delay_out_0",
        }
    ]
    protocols = [out.get("protocol") for out in config["outbounds"]]
    assert protocols[-2:] == ["freedom", "blackhole"]


def test_delay_config_ports_increment():
    _, ports = build_delay_probe_config([VLESS, VLESS, VLESS], 30000)
    assert ports == [30000, 30001, 30002]


def test_delay_config_skips_plain_vless_that_xray_would_refuse():
    _, ports = build_delay_probe_config(
        [VLESS, PLAIN_PUBLIC, PLAIN_PRIVATE, PLAIN_DOMAIN, ENCRYPTED], 24000
    )
    assert ports == [24000, None, 24001, 24002, 24003]


def test_main_config_explains_refused_plain_vless():
    with pytest.raises(LinkError, match="TLS or Reality"):
        build_config(PLAIN_PUBLIC)


def test_main_config_accepts_plain_vless_for_private_and_domain_hosts():
    for link in (PLAIN_PRIVATE, PLAIN_DOMAIN):
        config = build_config(link)
        assert config["outbounds"][0]["protocol"] == "vless"


def test_main_config_has_no_stats_api_by_default():
    config = build_config(VLESS, 10808, 10809)
    assert "stats" not in config
    assert "api" not in config
    assert [inbound["tag"] for inbound in config["inbounds"]] == ["socks", "http"]


def test_main_config_enables_stats_api_on_requested_port():
    config = build_config(VLESS, 10808, 10809, api_port=10810)
    assert config["stats"] == {}
    assert config["api"] == {"tag": "api", "services": ["StatsService"]}
    assert config["policy"]["system"] == {
        "statsInboundUplink": True,
        "statsInboundDownlink": True,
    }
    api_inbound = config["inbounds"][-1]
    assert api_inbound["tag"] == "api"
    assert api_inbound["port"] == 10810
    assert api_inbound["protocol"] == "dokodemo-door"
    assert config["routing"]["rules"][0] == {
        "type": "field",
        "inboundTag": ["api"],
        "outboundTag": "api",
    }
