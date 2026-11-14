"""High-level tunnel controller used by the UI."""

from __future__ import annotations

from pathlib import Path
from typing import Optional

from .config import HTTP_PORT, SOCKS_PORT, build_config
from .links import LinkError
from .netprobe import free_port, internet_available, probe_with_latency
from .runner import XrayError, XrayProcess
from .sysproxy import SystemProxy, restore_stale


class TunnelError(RuntimeError):
    def __init__(self, key: str, detail: str = ""):
        super().__init__(detail or key)
        self.key = key
        self.detail = detail


class TunnelClient:
    def __init__(self, data_dir: Optional[Path] = None):
        self.runner = XrayProcess(data_dir=data_dir)
        self.proxy = SystemProxy(SOCKS_PORT + 1, data_dir=data_dir)
        self.connected = False
        self.stats_port: Optional[int] = None

    @property
    def stats_server(self) -> str:
        return f"127.0.0.1:{self.stats_port}" if self.stats_port else ""

    def connect(
        self,
        link: str,
        socks_port: int = SOCKS_PORT,
        http_port: int = HTTP_PORT,
        use_system_proxy: bool = True,
        probe_timeout: int = 8000,
    ) -> int:
        if self.runner.running:
            self.disconnect()
        if not internet_available():
            raise TunnelError("no_internet")

        api_port = free_port(socks_port + 2)
        try:
            config = build_config(link, socks_port, http_port, api_port)
        except LinkError as exc:
            raise TunnelError("invalid_url", str(exc)) from exc

        try:
            self.runner.start(
                config,
                wait_ports=[socks_port, http_port],
                timeout=12,
            )
        except XrayError as exc:
            raise TunnelError("xray_error", str(exc)) from exc

        latency = probe_with_latency(socks_port, probe_timeout)
        if latency is None:
            detail = self.runner.logs(3000).strip()
            self.runner.stop()
            raise TunnelError("probe_failed", detail)

        self.stats_port = api_port
        self.proxy.port = http_port
        if use_system_proxy:
            self.proxy.enable()
        self.connected = True
        return latency

    def disconnect(self) -> None:
        self.stats_port = None
        try:
            self.proxy.disable()
        finally:
            self.runner.stop()
            self.connected = False

    def probe(self, socks_port: int = SOCKS_PORT, timeout_ms: int = 4000) -> Optional[int]:
        return probe_with_latency(socks_port, timeout_ms)

    def logs(self) -> str:
        return self.runner.logs()

    @staticmethod
    def restore_stale_proxy() -> bool:
        return restore_stale()
