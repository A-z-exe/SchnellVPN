import base64
import json

import pytest

from schnellvpn.core.links import (
    LinkError,
    endpoint_of,
    is_local_link,
    parse_outbound,
    security_of,
    server_name_of,
)

UUID = "b831381d-6324-4d53-ad4f-8cda48b30811"
VLESS = f"vless://{UUID}@1.2.3.4:443?type=tcp&security=tls&sni=example.com#Test"


def vmess_link(**overrides) -> str:
    payload = {
        "v": "2",
        "ps": "VMess node",
        "add": "5.6.7.8",
        "port": "8443",
        "id": UUID,
        "aid": "0",
        "net": "ws",
        "type": "none",
        "host": "cdn.example.com",
        "path": "/ws",
        "tls": "tls",
        "sni": "cdn.example.com",
    }
    payload.update(overrides)
    encoded = base64.b64encode(json.dumps(payload).encode()).decode()
    return "vmess://" + encoded


def test_vless_tls_stream():
    outbound = parse_outbound(VLESS)
    assert outbound["protocol"] == "vless"
    assert outbound["settings"]["vnext"][0]["address"] == "1.2.3.4"
    assert outbound["settings"]["vnext"][0]["port"] == 443
    assert outbound["streamSettings"]["security"] == "tls"
    assert outbound["streamSettings"]["tlsSettings"]["serverName"] == "example.com"
    assert outbound["streamSettings"]["tlsSettings"]["allowInsecure"] is False
    assert endpoint_of(VLESS) == ("1.2.3.4", 443)


def test_vless_flow_and_encryption():
    link = (
        f"vless://{UUID}@example.com:443?type=tcp&security=none&flow=xtls-rprx-vision"
        "&encryption=none#flow"
    )
    user = parse_outbound(link)["settings"]["vnext"][0]["users"][0]
    assert user["flow"] == "xtls-rprx-vision"
    assert user["encryption"] == "none"


def test_reality_requires_public_key():
    without = (
        f"vless://{UUID}@1.2.3.4:443?type=tcp&security=reality&sni=example.com"
    )
    with pytest.raises(LinkError, match="pbk"):
        parse_outbound(without)

    link = without + "&pbk=PUBKEY&sid=ab12&spx=%2F"
    stream = parse_outbound(link)["streamSettings"]
    assert stream["security"] == "reality"
    assert stream["realitySettings"]["publicKey"] == "PUBKEY"
    assert stream["realitySettings"]["shortId"] == "ab12"
    assert security_of(link) == "reality"
    assert server_name_of(link) == "example.com"


def test_ws_and_grpc_and_xhttp_transports():
    ws = (
        f"vless://{UUID}@1.2.3.4:443?type=ws&security=tls&host=cdn.example.com"
        "&path=%2Fws#ws"
    )
    settings = parse_outbound(ws)["streamSettings"]
    assert settings["network"] == "ws"
    assert settings["wsSettings"]["path"] == "/ws"
    assert settings["wsSettings"]["headers"]["Host"] == "cdn.example.com"

    grpc = (
        f"vless://{UUID}@1.2.3.4:443?type=grpc&security=tls&serviceName=svc&mode=multi"
    )
    grpc_settings = parse_outbound(grpc)["streamSettings"]
    assert grpc_settings["grpcSettings"]["serviceName"] == "svc"
    assert grpc_settings["grpcSettings"]["multiMode"] is True

    xhttp = (
        f"vless://{UUID}@1.2.3.4:443?type=splithttp&security=tls&path=%2Fx&host=a.com"
    )
    xhttp_settings = parse_outbound(xhttp)["streamSettings"]
    assert xhttp_settings["network"] == "xhttp"
    assert xhttp_settings["xhttpSettings"]["path"] == "/x"
    assert xhttp_settings["xhttpSettings"]["mode"] == "auto"


def test_httpupgrade_and_tcp_http_header():
    upgrade = (
        f"vless://{UUID}@1.2.3.4:443?type=httpupgrade&security=tls&host=a.com&path=%2Fu"
    )
    up = parse_outbound(upgrade)["streamSettings"]
    assert up["httpupgradeSettings"] == {"path": "/u", "host": "a.com"}

    tcp = (
        f"vless://{UUID}@1.2.3.4:443?type=tcp&security=none&headerType=http"
        "&host=a.com,b.com&path=%2Fa,%2Fb"
    )
    tcp_settings = parse_outbound(tcp)["streamSettings"]
    request = tcp_settings["tcpSettings"]["header"]["request"]
    assert request["path"] == ["/a", "/b"]
    assert request["headers"]["Host"] == ["a.com", "b.com"]


def test_vmess_ws_tls():
    outbound = parse_outbound(vmess_link())
    assert outbound["protocol"] == "vmess"
    vnext = outbound["settings"]["vnext"][0]
    assert (vnext["address"], vnext["port"]) == ("5.6.7.8", 8443)
    assert vnext["users"][0]["id"] == UUID
    assert outbound["streamSettings"]["network"] == "ws"
    assert outbound["streamSettings"]["tlsSettings"]["serverName"] == "cdn.example.com"


def test_vmess_port_as_number_and_alter_id():
    outbound = parse_outbound(vmess_link(port=443, aid="64", net="tcp", tls=""))
    vnext = outbound["settings"]["vnext"][0]
    assert vnext["port"] == 443
    assert vnext["users"][0]["alterId"] == 64
    assert outbound["streamSettings"]["security"] == "none"


def test_trojan_defaults_to_tls():
    link = "trojan://secret@example.com:443?sni=example.com#T"
    outbound = parse_outbound(link)
    assert outbound["protocol"] == "trojan"
    server = outbound["settings"]["servers"][0]
    assert server["password"] == "secret"
    assert outbound["streamSettings"]["security"] == "tls"


def test_shadowsocks_sip002_and_legacy():
    userinfo = base64.urlsafe_b64encode(b"aes-256-gcm:pass").decode().rstrip("=")
    sip002 = f"ss://{userinfo}@1.2.3.4:8388#SS"
    server = parse_outbound(sip002)["settings"]["servers"][0]
    assert server["method"] == "aes-256-gcm"
    assert server["password"] == "pass"

    legacy_body = base64.b64encode(b"aes-256-gcm:pass@5.6.7.8:8388").decode()
    legacy = parse_outbound(f"ss://{legacy_body}#Legacy")["settings"]["servers"][0]
    assert legacy["address"] == "5.6.7.8"
    assert legacy["port"] == 8388


def test_shadowsocks_plugin_rejected():
    userinfo = base64.urlsafe_b64encode(b"aes-256-gcm:pass").decode().rstrip("=")
    link = f"ss://{userinfo}@1.2.3.4:8388?plugin=obfs-local#SS"
    with pytest.raises(LinkError, match="plugin"):
        parse_outbound(link)


def test_ipv6_and_local_detection():
    link = f"vless://{UUID}@[2001:db8::1]:443?type=tcp&security=none"
    assert endpoint_of(link) == ("2001:db8::1", 443)

    local = f"vless://{UUID}@127.0.0.1:1080?type=tcp&security=none"
    assert is_local_link(local)


def test_json_outbound_passthrough():
    payload = json.dumps(
        {
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
            "streamSettings": {"network": "tcp", "security": "none"},
        }
    )
    outbound = parse_outbound(payload)
    assert outbound["tag"] == "proxy"
    assert endpoint_of(payload) == ("example.com", 443)


def test_invalid_inputs():
    with pytest.raises(LinkError):
        parse_outbound("")
    with pytest.raises(LinkError):
        parse_outbound("http://example.com")
    with pytest.raises(LinkError):
        parse_outbound("vless://@example.com:443")
    with pytest.raises(LinkError, match="port"):
        parse_outbound(f"vless://{UUID}@example.com:99999")


def test_plus_is_literal_in_userinfo():
    # A password containing '+' must survive URL decoding.
    link = "trojan://pa+ss@example.com:443?security=none#T"
    assert parse_outbound(link)["settings"]["servers"][0]["password"] == "pa+ss"
