"""Builds complete Xray-core configs (main tunnel and delay probes)."""

from __future__ import annotations

import ipaddress
import json
from typing import Any, Optional

from .links import LinkError, is_local_host, parse_outbound

SOCKS_PORT = 10808
HTTP_PORT = 10809

# Xray only accepts plain VLESS/Trojan (no TLS/Reality, no VLESS encryption)
# for private, loopback or link-local addresses; anything else aborts the whole
# core at load time. These are the same ranges Xray itself accepts.
_PRIVATE_NETS = tuple(
    ipaddress.ip_network(cidr)
    for cidr in (
        "10.0.0.0/8",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "127.0.0.0/8",
        "169.254.0.0/16",
        "fc00::/7",
        "::1/128",
        "fe80::/10",
    )
)


def _rejected_plain_outbound(outbound: dict[str, Any], address: str) -> bool:
    """True when Xray-core will refuse this outbound at config load."""
    protocol = outbound.get("protocol")
    if protocol not in ("vless", "trojan"):
        return False
    stream = outbound.get("streamSettings") or {}
    if str(stream.get("security") or "none").lower() not in ("", "none"):
        return False
    if protocol == "vless":
        vnext = (outbound.get("settings") or {}).get("vnext") or [{}]
        users = vnext[0].get("users") or [{}]
        if str(users[0].get("encryption") or "none").lower() not in ("", "none"):
            return False
    try:
        ip = ipaddress.ip_address(address)
    except ValueError:
        # Domains are judged against Xray's private-domain list, which we do not
        # model; let the core report it instead of guessing.
        return False
    return not any(ip in net for net in _PRIVATE_NETS)


def _socks_inbound(port: int) -> dict[str, Any]:
    return {
        "tag": "socks",
        "port": port,
        "listen": "127.0.0.1",
        "protocol": "socks",
        "sniffing": {
            "enabled": True,
            "destOverride": ["http", "tls", "quic"],
            "routeOnly": False,
        },
        "settings": {"auth": "noauth", "udp": True, "allowTransparent": False},
    }


def _http_inbound(port: int) -> dict[str, Any]:
    return {
        "tag": "http",
        "port": port,
        "listen": "127.0.0.1",
        "protocol": "http",
        "sniffing": {"enabled": True, "destOverride": ["http", "tls"]},
        "settings": {"timeout": 300},
    }


def _api_inbound(port: int) -> dict[str, Any]:
    return {
        "tag": "api",
        "listen": "127.0.0.1",
        "port": port,
        "protocol": "dokodemo-door",
        "settings": {"address": "127.0.0.1"},
    }


def _direct_outbound() -> dict[str, Any]:
    return {
        "protocol": "freedom",
        "tag": "DIRECT",
        "settings": {"domainStrategy": "UseIPv4"},
    }


def _block_outbound() -> dict[str, Any]:
    return {"protocol": "blackhole", "tag": "BLOCK"}


def build_config(
    link: str,
    socks_port: int = SOCKS_PORT,
    http_port: int = HTTP_PORT,
    api_port: Optional[int] = None,
) -> dict[str, Any]:
    outbound = parse_outbound(link)
    outbound["tag"] = "proxy"
    address = _endpoint_host(outbound)
    if address and _rejected_plain_outbound(outbound, address):
        raise LinkError(
            "Xray refuses VLESS without TLS/Reality on a public IP; "
            "use the TLS or Reality version of this server link"
        )

    inbounds: list[dict[str, Any]] = [_socks_inbound(socks_port)]
    if 1 <= http_port <= 65535:
        inbounds.append(_http_inbound(http_port))

    config: dict[str, Any] = {
        "log": {"access": "", "error": "", "loglevel": "warning"},
        "inbounds": inbounds,
        "outbounds": [outbound, _direct_outbound(), _block_outbound()],
        "dns": {"servers": ["1.1.1.1", "8.8.8.8"]},
        "routing": {
            "domainStrategy": "AsIs",
            "rules": [
                # Some networks/servers drop QUIC; force clients back to TCP/TLS.
                {"type": "field", "network": "udp", "port": "443", "outboundTag": "BLOCK"},
            ],
        },
    }

    if api_port and 1 <= api_port <= 65535:
        config["stats"] = {}
        config["api"] = {"tag": "api", "services": ["StatsService"]}
        config["policy"] = {
            "system": {"statsInboundUplink": True, "statsInboundDownlink": True}
        }
        inbounds.append(_api_inbound(api_port))
        config["routing"]["rules"].insert(
            0, {"type": "field", "inboundTag": ["api"], "outboundTag": "api"}
        )
    return config


def build_delay_probe_config(
    links: list[str], base_port: int
) -> tuple[dict[str, Any], list[Optional[int]]]:
    """One SOCKS inbound per link so each config's real delay can be measured.

    Returns the config and per-link ports (None = local/invalid, nothing to probe).
    """
    inbounds: list[dict[str, Any]] = []
    outbounds: list[dict[str, Any]] = []
    rules: list[dict[str, Any]] = []
    ports: list[Optional[int]] = []
    port = base_port

    for index, link in enumerate(links):
        try:
            outbound = parse_outbound(link)
            address = _endpoint_host(outbound)
        except LinkError:
            outbound = None
            address = None

        if (
            outbound is None
            or address is None
            or is_local_host(address)
            or _rejected_plain_outbound(outbound, address)
        ):
            ports.append(None)
            continue

        in_tag = f"delay_in_{index}"
        out_tag = f"delay_out_{index}"
        outbound["tag"] = out_tag

        inbounds.append(
            {
                "tag": in_tag,
                "listen": "127.0.0.1",
                "port": port,
                "protocol": "socks",
                "settings": {"auth": "noauth", "udp": False},
            }
        )
        outbounds.append(outbound)
        rules.append(
            {"type": "field", "inboundTag": [in_tag], "outboundTag": out_tag}
        )
        ports.append(port)
        port += 1

    outbounds.append(_direct_outbound())
    outbounds.append(_block_outbound())

    return (
        {
            "log": {"loglevel": "warning"},
            "inbounds": inbounds,
            "outbounds": outbounds,
            "dns": {"servers": ["1.1.1.1", "8.8.8.8"]},
            "routing": {"domainStrategy": "AsIs", "rules": rules},
        },
        ports,
    )


def _endpoint_host(outbound: dict[str, Any]) -> Optional[str]:
    settings = outbound.get("settings") or {}
    if outbound.get("protocol") in ("vless", "vmess"):
        entries = settings.get("vnext") or []
    else:
        entries = settings.get("servers") or []
    if not entries:
        return None
    return str(entries[0].get("address") or "") or None


def dumps(config: dict[str, Any]) -> str:
    return json.dumps(config, indent=2, ensure_ascii=False)
