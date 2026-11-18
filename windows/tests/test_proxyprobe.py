"""The HTTP-inbound probe must speak absolute-URI GET and fall back to CONNECT."""

from __future__ import annotations

import socket
import threading

from schnellvpn.core.netprobe import probe_http_proxy


class FakeProxy:
    def __init__(self, connections: int, plain_status: str = "204 No Content"):
        self.plain_status = plain_status
        self.seen: list[str] = []
        self._sock = socket.socket()
        self._sock.bind(("127.0.0.1", 0))
        self._sock.listen(connections)
        self._sock.settimeout(0.3)
        self.port = self._sock.getsockname()[1]
        self._left = connections
        self._thread = threading.Thread(target=self._run, daemon=True)

    def __enter__(self) -> "FakeProxy":
        self._thread.start()
        return self

    def __exit__(self, *exc) -> None:
        self._sock.close()
        self._thread.join(timeout=2)

    def _run(self) -> None:
        while self._left > 0:
            try:
                conn, _ = self._sock.accept()
            except OSError:
                return
            self._left -= 1
            with conn:
                conn.settimeout(2)
                data = b""
                while b"\r\n\r\n" not in data:
                    chunk = conn.recv(1024)
                    if not chunk:
                        break
                    data += chunk
                line = data.split(b"\r\n", 1)[0].decode("ascii", "replace")
                self.seen.append(line)
                if line.startswith("CONNECT "):
                    conn.sendall(b"HTTP/1.1 200 Connection established\r\n\r\n")
                else:
                    conn.sendall(f"HTTP/1.1 {self.plain_status}\r\n\r\n".encode())


def test_absolute_uri_get_counts_as_success():
    with FakeProxy(connections=1) as proxy:
        latency = probe_http_proxy(proxy.port, timeout_ms=2000)
    assert latency is not None and latency >= 1
    assert proxy.seen and proxy.seen[0].startswith("GET http://")


def test_connect_is_tried_when_plain_http_fails():
    with FakeProxy(
        connections=4, plain_status="503 Service Unavailable"
    ) as proxy:
        # CONNECT gets a 200 but the fake proxy then closes, so no TLS session
        # can complete and the probe must report failure instead of a false pass.
        assert probe_http_proxy(proxy.port, timeout_ms=3000) is None
    assert any(line.startswith("CONNECT ") for line in proxy.seen)
