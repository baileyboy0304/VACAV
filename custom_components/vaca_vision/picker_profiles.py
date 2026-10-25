"""Shared, versioned picker snapshots. No device credentials or identity fields."""

import asyncio
import json
import math
import re
from copy import deepcopy
from datetime import UTC, datetime
from urllib.parse import unquote, urlsplit
from uuid import uuid4

from homeassistant.helpers.storage import Store

from .const import DOMAIN

MAX_BYTES = 524288
# This portable format is shared with VACAOverlay and the Vision Android app.
PROFILE_FORMAT = "vacaoverlay-picker"


def validate_profile(profile):
    def keys(value, allowed):
        if not isinstance(value, dict) or set(value) - set(allowed):
            raise ValueError("Profile contains unsupported fields")

    def text(value, limit, blank=False):
        if (
            not isinstance(value, str)
            or len(value) > limit
            or (not blank and not value.strip())
            or any(ord(c) < 32 for c in value)
        ):
            raise ValueError("Invalid profile text")
        return value

    def layout(value, view=False):
        keys(
            value,
            {"width", "height", "x", "y", "horizontal", "vertical", "durationMs"}
            | ({"durationEdited"} if view else set()),
        )
        for key in ("width", "height", "x", "y"):
            n = value.get(key)
            if (
                type(n) not in (float, int)
                or not math.isfinite(n)
                or not (0.1 if key in ("width", "height") else 0) <= n <= 1
            ):
                raise ValueError("Invalid layout size or position")
        if (
            value["x"] + value["width"] > 1.000001
            or value["y"] + value["height"] > 1.000001
        ):
            raise ValueError("Layout is outside the screen")
        if value.get("horizontal") not in (
            "FREE",
            "LEFT",
            "MIDDLE",
            "RIGHT",
        ) or value.get("vertical") not in ("FREE", "TOP", "MIDDLE", "BOTTOM"):
            raise ValueError("Invalid gravity")
        n = value.get("durationMs")
        if (
            type(n) not in (int, float)
            or not math.isfinite(n)
            or int(n) != n
            or not 0 <= n <= 86400000
        ):
            raise ValueError("Invalid duration")
        if view and value.get("durationEdited") is not True:
            raise ValueError("Profile durations must be explicit")

    keys(profile, {"format", "version", "views", "presets"})
    if (
        profile.get("format") not in (PROFILE_FORMAT, "vaca_vision-picker")
        or type(profile.get("version")) is not int
        or profile["version"] != 1
    ):
        raise ValueError("Unsupported picker profile version")
    if (
        len(json.dumps(profile, ensure_ascii=False, allow_nan=False).encode())
        > MAX_BYTES
    ):
        raise ValueError("Profile is too large (maximum 512 KiB)")
    views, presets = profile.get("views"), profile.get("presets")
    if (
        not isinstance(views, list)
        or not isinstance(presets, list)
        or len(views) > 256
        or len(presets) > 64
    ):
        raise ValueError("Too many views or presets")
    paths, ids, names = set(), set(), set()
    for row in views:
        keys(
            row,
            {
                "id",
                "path",
                "title",
                "icon",
                "subview",
                "hidden",
                "iconOverride",
                "overlay",
                "loading",
            },
        )
        path = text(row.get("path"), 512)
        uri = urlsplit(path)
        if (
            not path.startswith("/")
            or path.startswith("//")
            or "\\" in path
            or uri.scheme
            or uri.query
            or uri.fragment
            or "?" in path
            or "#" in path
            or "%" in path
            or "//" in path
            or any(c.isspace() for c in path)
            or any(p in (".", "..") for p in unquote(path).split("/"))
            or path in paths
            or row.get("id") != path
        ):
            raise ValueError("Invalid or duplicate dashboard path")
        paths.add(path)
        text(row.get("title"), 160)
        for key in ("icon", "iconOverride"):
            if key == "iconOverride" and key not in row:
                continue
            if not re.fullmatch(r"(?:mdi:[a-z0-9-]+)?", text(row.get(key), 120, True)):
                raise ValueError("Use an mdi icon name")
        if type(row.get("hidden")) is not bool or type(row.get("subview")) is not bool:
            raise ValueError("Invalid view visibility")
        if row.get("loading") not in ("selected", "picker", "keep_alive"):
            raise ValueError("Unknown loading behaviour")
        layout(row.get("overlay"), True)
    for row in presets:
        keys(row, {"id", "name", "layout"})
        identity, name = text(row.get("id"), 80), text(row.get("name"), 60)
        if identity in ids or name.lower() in names:
            raise ValueError("Duplicate preset")
        ids.add(identity)
        names.add(name.lower())
        layout(row.get("layout"))
    clean = deepcopy(profile)
    clean["format"] = PROFILE_FORMAT
    return clean


class PickerProfiles:
    """One HA-wide library; save to disk before publishing success."""

    def __init__(self, hass):
        self.store = Store(hass, 1, f"{DOMAIN}.picker_profiles")
        self.profiles = {}
        self.lock = asyncio.Lock()

    async def load(self):
        data = await self.store.async_load()
        self.profiles = (data or {}).get("profiles", {})

    def list(self):
        return [
            {k: deepcopy(row[k]) for k in ("id", "name", "revision", "updated")}
            | {
                "views": len(row["profile"]["views"]),
                "presets": len(row["profile"]["presets"]),
            }
            for row in sorted(self.profiles.values(), key=lambda p: p["name"].lower())
        ]

    def get(self, identity):
        if identity not in self.profiles:
            raise ValueError("Profile no longer exists. Refresh the list.")
        row = deepcopy(self.profiles[identity])
        # Older Vision imports used a renamed marker the Android app rejects.
        row["profile"] = validate_profile(row["profile"])
        return row

    async def save(self, name, profile, identity=None, revision=None):
        clean = validate_profile(profile)
        if (
            not isinstance(name, str)
            or not name.strip()
            or len(name.strip()) > 60
            or any(ord(c) < 32 for c in name)
        ):
            raise ValueError("Use a profile name of 1–60 characters")
        name = name.strip()
        async with self.lock:
            previous = self.get(identity) if identity else None
            if previous and (
                type(revision) is not int or revision != previous["revision"]
            ):
                raise ValueError(
                    "Profile changed on another device. Refresh before overwriting."
                )
            if any(
                p["id"] != identity and p["name"].lower() == name.lower()
                for p in self.profiles.values()
            ):
                raise ValueError(
                    "That profile name already exists. Choose Update or another name."
                )
            if not previous and len(self.profiles) >= 100:
                raise ValueError("Profile library is full (100 profiles)")
            identity = identity or str(uuid4())
            row = {
                "id": identity,
                "name": name,
                "revision": (previous["revision"] + 1) if previous else 1,
                "updated": datetime.now(UTC).isoformat(),
                "profile": clean,
            }
            updated = {**self.profiles, identity: row}
            await self.store.async_save({"profiles": updated})
            self.profiles = updated
            return deepcopy(row)

    async def delete(self, identity, revision):
        async with self.lock:
            row = self.get(identity)
            if type(revision) is not int or revision != row["revision"]:
                raise ValueError("Profile changed. Refresh before deleting.")
            updated = {k: v for k, v in self.profiles.items() if k != identity}
            await self.store.async_save({"profiles": updated})
            self.profiles = updated

    async def request(self, operation, payload):
        if operation == "list":
            return {"profiles": self.list()}
        if operation == "get":
            return self.get(payload.get("id"))
        if operation == "save":
            return await self.save(
                payload.get("name"),
                payload.get("profile"),
                payload.get("id"),
                payload.get("revision"),
            )
        raise ValueError("Unknown picker profile operation")
