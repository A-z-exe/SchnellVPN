"""Subscription download/parsing: plain lists, Base64 lists, v2rayN-style JSON."""

from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Optional

from .links import (
    LinkError,
    b64decode_loose,
    decode_url,
    decode_vmess_json,
    endpoint_of,
    is_local_link,
    parse_outbound,
)
from .models import VpnServer

MAX_BYTES = 5 * 1024 * 1024
MAX_REDIRECTS = 5
SCHEMES = ("vless://", "vmess://", "trojan://", "ss://")


def _starts_with_known_scheme(text: str) -> bool:
    lowered = text.strip().lower()
    return any(lowered.startswith(scheme) for scheme in SCHEMES)


def is_direct_link(text: str) -> bool:
    return _starts_with_known_scheme(text)


def _contains_known_scheme(text: str) -> bool:
    return any(_starts_with_known_scheme(line) for line in text.splitlines())


def parse_content(raw_input: str) -> list[VpnServer]:
    raw = (raw_input or "").lstrip("\ufeff").strip()
    if not raw:
        return []
    if raw.startswith("[") or raw.startswith("{"):
        return _parse_json(raw)
    text = raw if _contains_known_scheme(raw) else (b64decode_loose(raw) or raw)
    return _parse_links(text)


def _parse_json(json_text: str) -> list[VpnServer]:
    try:
        data = json.loads(json_text)
    except json.JSONDecodeError:
        return []
    if isinstance(data, dict):
        items: list[Any] = [data]
    elif isinstance(data, list):
        items = data
    else:
        return []

    servers: list[VpnServer] = []
    for obj in items:
        if not isinstance(obj, dict):
            continue
        remarks = str(obj.get("remarks") or "")

        if "protocol" in obj:
            proxy: Optional[dict[str, Any]] = obj
        else:
            outbounds = obj.get("outbounds") or []
            proxy = None
            for candidate in outbounds:
                if isinstance(candidate, dict) and candidate.get("tag") == "proxy":
                    proxy = candidate
                    break
            if proxy is None and outbounds and isinstance(outbounds[0], dict):
                proxy = outbounds[0]
        if not proxy:
            continue
        if proxy.get("protocol") in ("freedom", "blackhole", "dns", ""):
            continue

        link = json.dumps(proxy, ensure_ascii=False)
        endpoint = endpoint_of(link)
        if not endpoint:
            continue
        name = remarks or endpoint[0]
        servers.append(
            VpnServer(len(servers) + 1, name, _label_of(proxy), link, None)
        )
    return servers


def _parse_links(text: str) -> list[VpnServer]:
    seen: set[str] = set()
    servers: list[VpnServer] = []

    for line in text.splitlines():
        link = line.strip()
        if not link or not _starts_with_known_scheme(link):
            continue
        try:
            outbound = parse_outbound(link)
        except LinkError:
            continue
        name = _remark_of(link)
        if not name:
            endpoint = endpoint_of(link)
            name = endpoint[0] if endpoint else f"Server {len(servers) + 1}"
        # Local configs may share a link but differ by name; show all of them.
        key = f"{link}|{name}" if is_local_link(link) else link
        if key in seen:
            continue
        seen.add(key)
        servers.append(
            VpnServer(len(servers) + 1, name, _label_of(outbound), link, None)
        )
    return servers


def _remark_of(link: str) -> str:
    if link.lower().startswith("vmess://"):
        try:
            from_json = str(decode_vmess_json(link).get("ps") or "").strip()
            if from_json:
                return from_json
        except (LinkError, json.JSONDecodeError):
            pass
    fragment = link.split("#", 1)[1] if "#" in link else ""
    return decode_url(fragment).strip()


def _label_of(outbound: dict[str, Any]) -> str:
    stream = outbound.get("streamSettings") or {}
    network = str(stream.get("network") or "tcp")
    security = str(stream.get("security") or "none")
    protocol = str(outbound.get("protocol") or "")
    if protocol == "vless":
        if security == "reality":
            return "VLESS \u00b7 Reality"
        if network == "ws":
            return "VLESS \u00b7 WS"
        if network == "grpc":
            return "VLESS \u00b7 gRPC"
        if network == "xhttp":
            return "VLESS \u00b7 XHTTP"
        return "VLESS"
    if protocol == "vmess":
        return "VMess"
    if protocol == "trojan":
        return "Trojan"
    if protocol == "shadowsocks":
        return "Shadowsocks"
    return protocol.upper()


def fetch(sub_url: str) -> list[VpnServer]:
    return parse_content(_download_text(sub_url))


def _download_text(url: str) -> str:
    opener = urllib.request.build_opener(
        urllib.request.ProxyHandler({}),  # subscription is fetched directly
        _NoRedirect(),
    )
    current = url.strip()
    for _ in range(MAX_REDIRECTS + 1):
        if not current.lower().startswith(("http://", "https://")):
            raise ValueError("Only http/https links are supported")
        request = urllib.request.Request(
            current, headers={"User-Agent": "SchnellVPN-Windows/0.1"}
        )
        try:
            response = opener.open(request, timeout=15)
        except urllib.error.HTTPError as exc:
            if 300 <= exc.code <= 399:
                location = exc.headers.get("Location")
                if not location:
                    raise ValueError("Redirect without Location") from exc
                current = urllib.parse.urljoin(current, location)
                continue
            raise ValueError(f"HTTP {exc.code}") from exc
        except urllib.error.URLError as exc:
            raise ValueError(f"Network error: {exc.reason}") from exc

        with response:
            if not 200 <= response.status < 300:
                raise ValueError(f"HTTP {response.status}")
            data = response.read(MAX_BYTES + 1)
        if len(data) > MAX_BYTES:
            raise ValueError("Subscription is too large")
        return data.decode("utf-8", "replace")
    raise ValueError("Too many redirects")


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: ANN001
        return None
