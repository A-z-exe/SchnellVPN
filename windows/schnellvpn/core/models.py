from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Optional


@dataclass
class VpnServer:
    id: int
    name: str
    label: str
    link: str
    ping: Optional[int] = None
    icon: str = "\u25CF"

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "name": self.name,
            "label": self.label,
            "link": self.link,
            "ping": self.ping,
            "icon": self.icon,
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "VpnServer":
        return cls(
            id=int(data.get("id", 0)),
            name=str(data.get("name", "")),
            label=str(data.get("label", "")),
            link=str(data.get("link", "")),
            ping=data.get("ping"),
            icon=str(data.get("icon", "\u25CF")),
        )


@dataclass
class Profile:
    id: int
    name: str
    servers: list[VpnServer] = field(default_factory=list)
    selected_server_id: int = 0
    subscription_urls: list[str] = field(default_factory=list)

    @property
    def selected_server(self) -> Optional[VpnServer]:
        for server in self.servers:
            if server.id == self.selected_server_id:
                return server
        return self.servers[0] if self.servers else None

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "name": self.name,
            "servers": [s.to_dict() for s in self.servers],
            "selected_server_id": self.selected_server_id,
            "subscription_urls": list(self.subscription_urls),
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "Profile":
        return cls(
            id=int(data.get("id", 1)),
            name=str(data.get("name", "Profile")),
            servers=[VpnServer.from_dict(s) for s in data.get("servers", [])],
            selected_server_id=int(data.get("selected_server_id", 0)),
            subscription_urls=[str(u) for u in data.get("subscription_urls", [])],
        )

    def next_server_id(self) -> int:
        return max((s.id for s in self.servers), default=0) + 1
