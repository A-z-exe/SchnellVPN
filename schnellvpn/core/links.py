"""Share-link parsers (vless / vmess / trojan / ss) and stream settings.

Ported from the Android client's XrayConfigBuilder so both platforms produce
the same Xray outbound. Pure Python: no network, no Qt.
"""

from __future__ import annotations

import base64
import binascii
import json
from dataclasses import dataclass, field
from typing import Any, Optional
from urllib.parse import unquote


class LinkError(ValueError):
    """Raised for a link that cannot be turned into an Xray outbound."""


def decode_url(value: str) -> str:
    return unquote(value, encoding="utf-8", errors="replace")


def b64decode_loose(text: str) -> Optional[str]:
    """Tolerant Base64: standard or URL-safe alphabet, optional padding."""
    cleaned = "".join(ch for ch in text if not ch.isspace())
    if not cleaned:
        return ""
    cleaned = cleaned.rstrip("=")
    cleaned = cleaned.replace("-", "+").replace("_", "/")
    if len(cleaned) % 4 == 1:
        return None
    cleaned += "=" * (-len(cleaned) % 4)
    try:
        return base64.b64decode(cleaned, validate=True).decode("utf-8", "replace")
    except (binascii.Error, ValueError):
        return None


def _decode_userinfo(value: str) -> str:
    # '+' is a literal in UUIDs/passwords, unlike in query strings.
    return decode_url(value.replace("+", "%2B"))


def _decode_pathpart(value: str) -> str:
    return decode_url(value)


def split_host_port(host_port: str) -> tuple[str, Optional[int]]:
    text = host_port.strip()
    if text.startswith("["):  # [IPv6]:port
        close = text.find("]")
        if close <= 0:
            raise LinkError("Bad IPv6 address")
        host = text[1:close]
        rest = text[close + 1 :]
        if rest.startswith(":"):
            rest = rest[1:]
        try:
            port = int(rest) if rest else None
        except ValueError:
            port = None
        return host, port
    first = text.find(":")
    if first < 0:
        return text, None
    if first != text.rfind(":"):
        return text, None  # bare IPv6, no port
    try:
        port = int(text[first + 1 :])
    except ValueError:
        port = None
    return text[:first], port


def parse_query(query: str) -> dict[str, str]:
    if not query:
        return {}
    params: dict[str, str] = {}
    for pair in query.split("&"):
        idx = pair.find("=")
        if idx <= 0:
            continue
        key = decode_url(pair[:idx])
        if key not in params:
            params[key] = decode_url(pair[idx + 1 :])
    return params


@dataclass
class ParsedUrl:
    user_info: Optional[str]
    host: str
    port: Optional[int]
    params: dict[str, str] = field(default_factory=dict)
    fragment: str = ""


def parse_url(link: str) -> ParsedUrl:
    scheme_end = link.find("://")
    if scheme_end <= 0:
        raise LinkError("Invalid link")
    rest = link[scheme_end + 3 :]

    fragment = ""
    if "#" in rest:
        rest, fragment = rest.split("#", 1)
        fragment = _decode_pathpart(fragment)

    query = ""
    if "?" in rest:
        rest, query = rest.split("?", 1)

    authority = rest.split("/", 1)[0]
    at = authority.rfind("@")
    user_info = _decode_userinfo(authority[:at]) if at >= 0 else None
    host, port = split_host_port(authority[at + 1 :] if at >= 0 else authority)
    if not host:
        raise LinkError("Missing host")

    return ParsedUrl(user_info, host, port, parse_query(query), fragment)


def valid_port(port: Optional[int], default: int = 443) -> int:
    value = default if port is None else port
    if not 1 <= value <= 65535:
        raise LinkError(f"Invalid port: {value}")
    return value


def default_mux() -> dict[str, Any]:
    return {"enabled": False, "concurrency": -1}


def _split_list(value: str) -> list[str]:
    return [part.strip() for part in value.split(",")]


def build_stream(
    params: dict[str, str], address: str, default_security: str
) -> dict[str, Any]:
    network = (params.get("type") or "tcp").lower()
    if network == "splithttp":
        network = "xhttp"
    security = (params.get("security") or default_security).lower()
    host = params.get("host") or ""
    path = params.get("path") or "/"

    stream: dict[str, Any] = {"network": network, "security": security}

    if network == "tcp":
        if params.get("headerType") == "http":
            stream["tcpSettings"] = {
                "header": {
                    "type": "http",
                    "request": {
                        "version": "1.1",
                        "method": "GET",
                        "path": _split_list(path),
                        "headers": {
                            "Host": _split_list(host or address),
                            "User-Agent": [],
                            "Accept-Encoding": ["gzip, deflate"],
                            "Connection": ["keep-alive"],
                            "Pragma": ["no-cache"],
                        },
                    },
                }
            }
    elif network == "kcp":
        kcp: dict[str, Any] = {"header": {"type": params.get("headerType") or "none"}}
        if params.get("seed"):
            kcp["seed"] = params["seed"]
        stream["kcpSettings"] = kcp
    elif network == "ws":
        ws: dict[str, Any] = {"path": path}
        if host:
            ws["headers"] = {"Host": host}
        stream["wsSettings"] = ws
    elif network == "grpc":
        grpc: dict[str, Any] = {
            "serviceName": params.get("serviceName") or "",
            "multiMode": params.get("mode") == "multi",
        }
        if params.get("authority"):
            grpc["authority"] = params["authority"]
        stream["grpcSettings"] = grpc
    elif network in ("h2", "http"):
        stream["httpSettings"] = {
            "path": path,
            "host": [host or address],
        }
    elif network == "httpupgrade":
        hu: dict[str, Any] = {"path": path}
        if host:
            hu["host"] = host
        stream["httpupgradeSettings"] = hu
    elif network == "xhttp":
        xhttp: dict[str, Any] = {
            "path": path,
            **({"host": host} if host else {}),
            "mode": params.get("mode") or "auto",
        }
        if params.get("extra"):
            try:
                xhttp["extra"] = json.loads(params["extra"])
            except json.JSONDecodeError:
                pass
        stream["xhttpSettings"] = xhttp
    else:
        raise LinkError(f"Unsupported transport: {network}")

    if security == "tls":
        tls: dict[str, Any] = {
            "serverName": params.get("sni") or host or address,
            "allowInsecure": False,
            "fingerprint": params.get("fp") or "chrome",
        }
        alpn = params.get("alpn")
        if alpn and alpn.strip():
            tls["alpn"] = _split_list(alpn)
        stream["tlsSettings"] = tls
    elif security == "reality":
        public_key = params.get("pbk") or ""
        if not public_key:
            raise LinkError("Reality link is missing the public key (pbk)")
        stream["realitySettings"] = {
            "serverName": params.get("sni") or address,
            "fingerprint": params.get("fp") or "chrome",
            "publicKey": public_key,
            "shortId": params.get("sid") or "",
            "spiderX": params.get("spx") or "/",
        }
    elif security not in ("none", ""):
        raise LinkError(f"Unsupported security: {security}")

    return stream


def parse_outbound(link: str) -> dict[str, Any]:
    trimmed = (link or "").strip()
    if not trimmed:
        raise LinkError("Link is empty")

    try:
        if trimmed.startswith("{"):
            outbound = json.loads(trimmed)
            if not isinstance(outbound, dict):
                raise LinkError("Invalid server config: expected a JSON object")
            outbound["tag"] = "proxy"
            return outbound
        lowered = trimmed.lower()
        if lowered.startswith("vless://"):
            return parse_vless(trimmed)
        if lowered.startswith("vmess://"):
            return parse_vmess(trimmed)
        if lowered.startswith("trojan://"):
            return parse_trojan(trimmed)
        if lowered.startswith("ss://"):
            return parse_shadowsocks(trimmed)
        raise LinkError(f"Unsupported protocol: {trimmed[:20]}")
    except LinkError:
        raise
    except json.JSONDecodeError as exc:
        raise LinkError(f"Invalid server config: {exc.msg}") from exc
    except Exception as exc:  # noqa: BLE001 - mirror Android's readable error
        raise LinkError(f"Invalid link: {exc}") from exc


def _require_user_info(url: ParsedUrl, message: str) -> str:
    if url.user_info is None or not url.user_info.strip():
        raise LinkError(message)
    return url.user_info


def parse_vless(link: str) -> dict[str, Any]:
    url = parse_url(link)
    uuid = _require_user_info(url, "Missing UUID")
    port = valid_port(url.port)
    flow = url.params.get("flow") or ""

    user: dict[str, Any] = {
        "id": uuid,
        "encryption": url.params.get("encryption") or "none",
    }
    if flow:
        user["flow"] = flow

    return {
        "tag": "proxy",
        "protocol": "vless",
        "settings": {
            "vnext": [
                {"address": url.host, "port": port, "users": [user]},
            ]
        },
        "streamSettings": build_stream(url.params, url.host, "none"),
        "mux": default_mux(),
    }


def decode_vmess_json(link: str) -> dict[str, Any]:
    raw = link[len("vmess://") :].split("#", 1)[0].strip()
    decoded = b64decode_loose(decode_url(raw).replace(" ", "+"))
    if decoded is None:
        raise LinkError("Invalid vmess Base64")
    data = json.loads(decoded)
    if not isinstance(data, dict):
        raise LinkError("Invalid vmess JSON")
    return data


def parse_vmess(link: str) -> dict[str, Any]:
    obj = decode_vmess_json(link)
    address = str(obj.get("add") or "")
    if not address:
        raise LinkError("Missing host")
    uuid = str(obj.get("id") or "")
    if not uuid:
        raise LinkError("Missing UUID")
    port_text = str(obj.get("port") or "443")
    try:
        port = valid_port(int(port_text))
    except ValueError as exc:
        raise LinkError(f"Invalid port: {port_text}") from exc

    network = str(obj.get("net") or "tcp") or "tcp"
    params: dict[str, str] = {
        "type": network,
        "security": "tls" if str(obj.get("tls") or "").lower() == "tls" else "none",
    }
    if obj.get("host"):
        params["host"] = str(obj["host"])
    path = str(obj.get("path") or "")
    if path:
        params["path"] = path
        if network == "grpc":
            params["serviceName"] = path
        if network == "kcp":
            params["seed"] = path
    for key, target in (("sni", "sni"), ("fp", "fp"), ("alpn", "alpn")):
        if obj.get(key):
            params[target] = str(obj[key])
    header_type = str(obj.get("type") or "")
    if header_type and header_type != "none":
        params["headerType"] = header_type
        if network == "grpc":
            params["mode"] = header_type

    try:
        alter_id = int(str(obj.get("aid") or "0"))
    except ValueError:
        alter_id = 0

    return {
        "tag": "proxy",
        "protocol": "vmess",
        "settings": {
            "vnext": [
                {
                    "address": address,
                    "port": port,
                    "users": [
                        {
                            "id": uuid,
                            "alterId": alter_id,
                            "security": str(obj.get("scy") or "auto") or "auto",
                        }
                    ],
                }
            ]
        },
        "streamSettings": build_stream(params, address, "none"),
        "mux": default_mux(),
    }


def parse_trojan(link: str) -> dict[str, Any]:
    url = parse_url(link)
    password = _require_user_info(url, "Missing password")
    port = valid_port(url.port)
    return {
        "tag": "proxy",
        "protocol": "trojan",
        "settings": {
            "servers": [
                {"address": url.host, "port": port, "password": password},
            ]
        },
        "streamSettings": build_stream(url.params, url.host, "tls"),
    }


def parse_shadowsocks(link: str) -> dict[str, Any]:
    body = link[len("ss://") :].split("#", 1)[0]
    query = ""
    if "?" in body:
        body, query = body.split("?", 1)
    if "plugin" in parse_query(query):
        raise LinkError("Shadowsocks plugins are not supported")

    if "@" in body:
        # SIP002: ss://BASE64(method:password)@host:port (or plain userinfo)
        raw_user = body.rsplit("@", 1)[0]
        user_info = _decode_userinfo(raw_user)
        if ":" not in user_info:
            user_info = b64decode_loose(user_info) or user_info
        host_port = body.rsplit("@", 1)[1].rstrip("/")
    else:
        decoded = b64decode_loose(decode_url(body))
        if decoded is None:
            raise LinkError("Invalid Shadowsocks link")
        if "@" not in decoded:
            raise LinkError("Invalid Shadowsocks link")
        user_info, host_port = decoded.rsplit("@", 1)

    method, _, password = user_info.partition(":")
    if not method or not password:
        raise LinkError("Invalid Shadowsocks credentials")
    host, port = split_host_port(host_port)
    if not host:
        raise LinkError("Missing host")

    return {
        "tag": "proxy",
        "protocol": "shadowsocks",
        "settings": {
            "servers": [
                {
                    "address": host,
                    "port": valid_port(port),
                    "method": method,
                    "password": password,
                }
            ]
        },
    }


def endpoint_of(link: str) -> Optional[tuple[str, int]]:
    try:
        outbound = parse_outbound(link)
    except LinkError:
        return None
    settings = outbound.get("settings") or {}
    if outbound.get("protocol") in ("vless", "vmess"):
        entries = settings.get("vnext") or []
    else:
        entries = settings.get("servers") or []
    if not entries:
        return None
    entry = entries[0]
    host = str(entry.get("address") or "")
    port = entry.get("port")
    if host and isinstance(port, int) and 1 <= port <= 65535:
        return host, port
    return None


def security_of(link: str) -> str:
    try:
        stream = parse_outbound(link).get("streamSettings") or {}
    except LinkError:
        return "none"
    return str(stream.get("security") or "none")


def server_name_of(link: str) -> Optional[str]:
    try:
        stream = parse_outbound(link).get("streamSettings")
    except LinkError:
        return None
    if not stream:
        return None
    tls = stream.get("tlsSettings") or stream.get("realitySettings") or {}
    name = str(tls.get("serverName") or "")
    if name:
        return name
    ws_host = str(((stream.get("wsSettings") or {}).get("headers") or {}).get("Host") or "")
    if ws_host:
        return ws_host
    http_host = (stream.get("httpSettings") or {}).get("host") or []
    if http_host:
        return str(http_host[0])
    return None


def is_local_host(host: str) -> bool:
    return host in ("127.0.0.1", "::1", "0.0.0.0") or host.lower() == "localhost"


def is_local_link(link: str) -> bool:
    endpoint = endpoint_of(link)
    return bool(endpoint and is_local_host(endpoint[0]))
