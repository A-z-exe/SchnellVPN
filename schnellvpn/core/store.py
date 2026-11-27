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
    "smart_connect": False,
    "always_on": True,
    "seen_notices": [],
}


def _restrict(path: Path, mode: int) -> None:
    try:
        os.chmod(path, mode)
    except OSError:
        pass


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
        if os.name != "nt":
            # profiles.json holds server credentials; other local users must not read it.
            _restrict(self.path.parent, 0o700)
        payload = {
            "version": 1,
            "current_profile_id": self.current_profile_id,
            "profiles": [p.to_dict() for p in self.profiles],
            "settings": self.settings,
        }
        temp = self.path.with_suffix(".tmp")
        temp.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
        if os.name != "nt":
            _restrict(temp, 0o600)
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

    def add_subscription_profile(self, url: str, servers: list[VpnServer]) -> Profile:
        """One subscription URL owns exactly one profile.

        Mirrors the Android client: importing a subscription creates a fresh
        profile, stamps every server with its ``source`` URL and numbers the
        servers 1..n so refresh can replace only this profile's servers.
        """
        # The first subscription reuses the empty default profile (id 1), just
        # like Android; later ones each create their own profile.
        current = self.profile
        if current.servers or current.subscription_urls:
            new_id = max((p.id for p in self.profiles), default=0) + 1
            current = Profile(new_id, f"Profile {new_id}")
            self.profiles.append(current)
            self.current_profile_id = new_id
        profile = current
        profile.subscription_urls = [url]
        ordered = sorted(servers, key=lambda s: (not s.pinned, s.name.lower()))
        for index, server in enumerate(ordered, 1):
            server.id = index
            server.source = url
        profile.servers = ordered
        profile.selected_server_id = ordered[0].id if ordered else 0
        self.current_profile_id = profile.id
        self.save()
        return profile

    def update_profile(self, profile_id: int, name: str, urls: list[str]) -> None:
        """Edit a profile's name and subscription URL(s).

        When the URL changes, servers that came from the old subscription are
        re-stamped so the next refresh targets the new link.
        """
        profile = next((p for p in self.profiles if p.id == profile_id), None)
        if profile is None:
            return
        profile.name = name.strip() or profile.name
        clean_urls = [url.strip() for url in urls if url.strip()]
        profile.subscription_urls = clean_urls
        if len(clean_urls) == 1:
            for server in profile.servers:
                if server.source and server.source != "manual":
                    server.source = clean_urls[0]
        self.save()

    def delete_profile(self, profile_id: int) -> None:
        """Remove a profile and renumber the rest 1..n (profile 2 becomes 1)."""
        if len(self.profiles) <= 1:
            return
        index = next(
            (i for i, p in enumerate(self.profiles) if p.id == profile_id), None
        )
        if index is None:
            return
        current = self.profile
        was_current = current.id == profile_id
        self.profiles.pop(index)
        for number, profile in enumerate(self.profiles, 1):
            profile.id = number
        if was_current:
            self.current_profile_id = self.profiles[min(index, len(self.profiles) - 1)].id
        else:
            self.current_profile_id = current.id
        self.save()

    def add_servers(
        self,
        servers: list[VpnServer],
        subscription_urls: Optional[list[str]] = None,
    ) -> int:
        profile = self.profile
        start = profile.next_server_id()
        for offset, server in enumerate(servers):
            server.id = start + offset
            profile.servers.append(server)
        for url in subscription_urls or []:
            if url and url not in profile.subscription_urls:
                profile.subscription_urls.append(url)
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
