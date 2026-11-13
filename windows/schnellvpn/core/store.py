"""Profiles, servers and settings persistence."""

from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any, Optional

from .models import Profile, VpnServer
from .runner import app_data_dir

DEFAULT_SETTINGS: dict[str, Any] = {
    "language": "fa",
    "theme": "dark",
    "socks_port": 10808,
    "http_port": 10809,
    "use_system_proxy": True,
    "minimize_to_tray": True,
    "probe_interval": 20,
}


class Store:
    def __init__(self, path: Optional[Path] = None, data_dir: Optional[Path] = None):
        root = Path(data_dir or app_data_dir())
        self.path = Path(path) if path else root / "profiles.json"
        self.profiles: list[Profile] = []
        self.current_profile_id = 1
        self.settings: dict[str, Any] = dict(DEFAULT_SETTINGS)
        self.load()

    def load(self) -> None:
        if not self.path.is_file():
            self.profiles = [Profile(1, "Profile 1")]
            self.current_profile_id = 1
            return
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            self.profiles = [Profile(1, "Profile 1")]
            self.current_profile_id = 1
            return
        self.profiles = [Profile.from_dict(p) for p in data.get("profiles", [])]
        if not self.profiles:
            self.profiles = [Profile(1, "Profile 1")]
        self.current_profile_id = int(data.get("current_profile_id", self.profiles[0].id))
        if not any(p.id == self.current_profile_id for p in self.profiles):
            self.current_profile_id = self.profiles[0].id
        merged = dict(DEFAULT_SETTINGS)
        merged.update(data.get("settings") or {})
        self.settings = merged

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "version": 1,
            "current_profile_id": self.current_profile_id,
            "profiles": [p.to_dict() for p in self.profiles],
            "settings": self.settings,
        }
        temp = self.path.with_suffix(".tmp")
        temp.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
        os.replace(temp, self.path)

    @property
    def profile(self) -> Profile:
        for profile in self.profiles:
            if profile.id == self.current_profile_id:
                return profile
        self.current_profile_id = self.profiles[0].id
        return self.profiles[0]

    def add_profile(self, name: str) -> Profile:
        profile = Profile(max(p.id for p in self.profiles) + 1, name)
        self.profiles.append(profile)
        self.current_profile_id = profile.id
        self.save()
        return profile

    def delete_profile(self, profile_id: int) -> None:
        if len(self.profiles) <= 1:
            return
        self.profiles = [p for p in self.profiles if p.id != profile_id]
        if self.current_profile_id == profile_id:
            self.current_profile_id = self.profiles[0].id
        self.save()

    def add_servers(self, servers: list[VpnServer], subscription_url: Optional[str] = None) -> int:
        profile = self.profile
        start = profile.next_server_id()
        for offset, server in enumerate(servers):
            server.id = start + offset
            profile.servers.append(server)
        if subscription_url and subscription_url not in profile.subscription_urls:
            profile.subscription_urls.append(subscription_url)
        if profile.selected_server_id == 0 and profile.servers:
            profile.selected_server_id = profile.servers[0].id
        self.save()
        return len(servers)

    def remove_server(self, server_id: int) -> None:
        profile = self.profile
        profile.servers = [s for s in profile.servers if s.id != server_id]
        if profile.selected_server_id == server_id:
            profile.selected_server_id = profile.servers[0].id if profile.servers else 0
        self.save()

    def select_server(self, server_id: int) -> None:
        self.profile.selected_server_id = server_id
        self.save()

    def get_setting(self, key: str, default: Any = None) -> Any:
        return self.settings.get(key, DEFAULT_SETTINGS.get(key, default))

    def set_setting(self, key: str, value: Any) -> None:
        self.settings[key] = value
        self.save()
