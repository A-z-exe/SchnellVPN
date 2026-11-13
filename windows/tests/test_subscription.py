import base64
import json

from schnellvpn.core.subscription import is_direct_link, parse_content

UUID = "b831381d-6324-4d53-ad4f-8cda48b30811"


def vless(name: str, host: str = "1.2.3.4") -> str:
    return f"vless://{UUID}@{host}:443?type=tcp&security=tls&sni=example.com#{name}"


def b64(text: str) -> str:
    return base64.b64encode(text.encode()).decode()


def test_plain_list_and_bom():
    body = "\ufeff" + "\n".join([vless("One"), vless("Two", "5.6.7.8")])
    servers = parse_content(body)
    assert [s.name for s in servers] == ["One", "Two"]
    assert servers[0].label == "VLESS"
    assert servers[0].id == 1 and servers[1].id == 2


def test_base64_list():
    raw = "\n".join([vless("A"), vless("B", "9.9.9.9")])
    servers = parse_content(b64(raw))
    assert len(servers) == 2
    assert servers[1].name == "B"


def test_skips_unparseable_lines():
    body = "\n".join(
        [
            vless("ok"),
            "vless://broken",
            "ss://not-base64!!",
            "random text",
        ]
    )
    servers = parse_content(body)
    assert len(servers) == 1
    assert servers[0].name == "ok"


def test_deduplicates_links():
    link = vless("dup")
    servers = parse_content("\n".join([link, link]))
    assert len(servers) == 1


def test_local_links_with_different_names_both_survive():
    a = vless("local-a", "127.0.0.1")
    b = vless("local-b", "127.0.0.1")
    assert len(parse_content("\n".join([a, b]))) == 2


def test_v2rayn_json_array():
    outbound = {
        "protocol": "vless",
        "settings": {
            "vnext": [
                {
                    "address": "example.com",
                    "port": 443,
                    "users": [{"id": UUID, "encryption": "none"}],
                }
            ]
        },
        "streamSettings": {"network": "ws", "security": "tls"},
    }
    payload = json.dumps([{"remarks": "From JSON", "outbounds": [outbound]}])
    servers = parse_content(payload)
    assert len(servers) == 1
    assert servers[0].name == "From JSON"
    assert servers[0].label == "VLESS \u00b7 WS"


def test_json_object_with_protocol_is_an_outbound():
    outbound = {
        "protocol": "trojan",
        "remarks": "Trojan node",
        "settings": {
            "servers": [{"address": "t.example.com", "port": 443, "password": "x"}]
        },
        "streamSettings": {"security": "tls"},
    }
    servers = parse_content(json.dumps(outbound))
    assert servers[0].name == "Trojan node"
    assert servers[0].label == "Trojan"


def test_json_skips_freedom_outbounds():
    payload = json.dumps(
        [
            {"remarks": "direct", "protocol": "freedom", "settings": {}},
        ]
    )
    assert parse_content(payload) == []


def test_direct_link_detection():
    assert is_direct_link(vless("x"))
    assert is_direct_link("  TROJAN://a@b:443")
    assert not is_direct_link("https://example.com/sub")
    assert not is_direct_link("hello")


def test_empty_input():
    assert parse_content("") == []
    assert parse_content("   \n  ") == []
