"""Real data-flow probes and latency measurement.

A TCP connect only proves the server port is reachable; it says nothing about
whether the config actually carries traffic. These helpers push a real HTTP
request through the local SOCKS inbound, exactly like the Android client.
"""

from __future__ import annotations

import socket
import ssl
import struct
import time
from concurrent.futures import ThreadPoolExecutor
from typing import Optional
from urllib.parse import urlsplit

from .links import endpoint_of, is_local_host, security_of, server_name_of

HTTP_ENDPOINTS = (
    "https://www.gstatic.com/generate_204",
    "https://cp.cloudflare.com/generate_204",
    "https://1.1.1.1/",
)
TCP_TARGETS = (("1.1.1.1", 443), ("8.8.8.8", 53), ("9.9.9.9", 53))
MAX_PING_MS = 4000


def _recv_exact(sock: socket.socket, count: int) -> bytes:
    chunks = bytearray()
    while len(chunks) < count:
        chunk = sock.recv(count - len(chunks))
        if not chunk:
            raise OSError("connection closed")
        chunks.extend(chunk)
    return bytes(chunks)


def socks5_connect(
    host: str,
    port: int,
    socks_port: int = 10808,
    timeout: float = 3.0,
    socks_host: str = "127.0.0.1",
) -> socket.socket:
    sock = socket.create_connection((socks_host, socks_port), timeout=timeout)
    try:
        sock.settimeout(timeout)
        sock.sendall(b"\x05\x01\x00")
        if _recv_exact(sock, 2) != b"\x05\x00":
            raise OSError("SOCKS auth rejected")
        host_bytes = host.encode("idna")
        request = (
            b"\x05\x01\x00\x03"
            + bytes([len(host_bytes)])
            + host_bytes
            + struct.pack(">H", port)
        )
        sock.sendall(request)
        reply = _recv_exact(sock, 4)
        if reply[0] != 5 or reply[1] != 0:
            raise OSError(f"SOCKS connect failed (code {reply[1]})")
        address_type = reply[3]
        if address_type == 1:
            _recv_exact(sock, 4)
        elif address_type == 3:
            _recv_exact(sock, _recv_exact(sock, 1)[0])
        elif address_type == 4:
            _recv_exact(sock, 16)
        else:
            raise OSError("bad SOCKS address type")
        _recv_exact(sock, 2)
        return sock
    except Exception:
        sock.close()
        raise


def _http_get_through_socks(
    url: str, socks_port: int, timeout: float
) -> Optional[int]:
    parts = urlsplit(url)
    host = parts.hostname or ""
    port = parts.port or (443 if parts.scheme == "https" else 80)
    path = parts.path or "/"
    if parts.query:
        path += "?" + parts.query

    started = time.monotonic()
    sock = socks5_connect(host, port, socks_port, timeout)
    try:
        if parts.scheme == "https":
            context = ssl.create_default_context()
            sock = context.wrap_socket(sock, server_hostname=host)
        sock.settimeout(timeout)
        request = (
            f"GET {path} HTTP/1.1\r\nHost: {host}\r\n"
            "User-Agent: SchnellVPN-Windows\r\nConnection: close\r\n\r\n"
        )
        sock.sendall(request.encode("ascii"))
        status_line = b""
        while b"\r\n" not in status_line and len(status_line) < 256:
            chunk = sock.recv(1)
            if not chunk:
                break
            status_line += chunk
        fields = status_line.split()
        if len(fields) < 2 or not fields[0].startswith(b"HTTP/"):
            return None
        code = int(fields[1])
        if 200 <= code < 400:
            return max(1, int((time.monotonic() - started) * 1000))
        return None
    finally:
        sock.close()


def probe_with_latency(socks_port: int = 10808, timeout_ms: int = 3000) -> Optional[int]:
    """Real request through the tunnel; total time never exceeds timeout_ms."""
    deadline = time.monotonic() + timeout_ms / 1000

    for url in HTTP_ENDPOINTS:
        left = deadline - time.monotonic()
        if left < 0.35:
            break
        try:
            latency = _http_get_through_socks(url, socks_port, min(left, 1.5))
            if latency is not None:
                return latency
        except (OSError, ssl.SSLError, ValueError):
            continue

    for host, port in TCP_TARGETS:
        left = deadline - time.monotonic()
        if left < 0.25:
            break
        started = time.monotonic()
        try:
            sock = socks5_connect(host, port, socks_port, min(left, 1.5))
            sock.close()
            return max(1, int((time.monotonic() - started) * 1000))
        except OSError:
            continue
    return None


def probe(socks_port: int = 10808, timeout_ms: int = 3000) -> bool:
    return probe_with_latency(socks_port, timeout_ms) is not None


def _sanitize(ms: int) -> Optional[int]:
    return ms if 1 <= ms <= MAX_PING_MS else None


def tcp_ping(host: str, port: int, timeout: float = 3.0) -> Optional[int]:
    started = time.monotonic()
    try:
        with socket.create_connection((host, port), timeout=timeout):
            pass
        return _sanitize(max(1, int((time.monotonic() - started) * 1000)))
    except OSError:
        return None


def handshake_ping(
    host: str,
    port: int,
    server_name: Optional[str],
    timeout: float = 4.0,
) -> Optional[int]:
    started = time.monotonic()
    try:
        raw = socket.create_connection((host, port), timeout=timeout)
    except OSError:
        return None
    try:
        raw.settimeout(timeout)
        context = ssl.create_default_context()
        with context.wrap_socket(raw, server_hostname=server_name or host) as tls:
            tls.do_handshake()
        return _sanitize(int((time.monotonic() - started) * 1000))
    except (OSError, ssl.SSLError):
        return None
    finally:
        try:
            raw.close()
        except OSError:
            pass


def ping_link(link: str) -> Optional[int]:
    endpoint = endpoint_of(link)
    if not endpoint:
        return None
    host, port = endpoint
    if is_local_host(host):
        return 0
    security = security_of(link)
    if security in ("tls", "reality"):
        return handshake_ping(host, port, server_name_of(link)) or tcp_ping(host, port)
    return tcp_ping(host, port)


def ping_all(links: list[str], concurrency: int = 8) -> list[Optional[int]]:
    with ThreadPoolExecutor(max_workers=max(1, concurrency)) as pool:
        return list(pool.map(ping_link, links))


def internet_available(timeout: float = 3.0) -> bool:
    for host, port in (("1.1.1.1", 443), ("8.8.8.8", 53)):
        try:
            with socket.create_connection((host, port), timeout=timeout):
                return True
        except OSError:
            continue
    return False


def can_connect(port: int, host: str = "127.0.0.1", timeout: float = 0.25) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def free_port(preferred: int, attempts: int = 20) -> Optional[int]:
    """First free port at or above `preferred` that can be bound on loopback."""
    for port in range(preferred, preferred + attempts):
        if port > 65535:
            break
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            sock.bind(("127.0.0.1", port))
            return port
        except OSError:
            continue
        finally:
            sock.close()
    return None


def free_base_port(count: int, attempts: int = 20) -> Optional[int]:
    for candidate in range(attempts):
        base = 22000 + candidate * 1000
        if base + count + 8 > 65535:
            break
        sockets: list[socket.socket] = []
        try:
            for port in range(base, base + count):
                probe_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                probe_socket.bind(("127.0.0.1", port))
                sockets.append(probe_socket)
            return base
        except OSError:
            continue
        finally:
            for probe_socket in sockets:
                probe_socket.close()
    return None
