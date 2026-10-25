"""VACA custom events over Wyoming TCP, without Wyoming voice services."""

from __future__ import annotations

import asyncio
from collections.abc import Callable
from contextlib import asynccontextmanager, suppress
from dataclasses import dataclass
from typing import Any

from wyoming.client import AsyncTcpClient
from wyoming.event import Event

from .const import PROTOCOL, PROTOCOL_VERSION


class WrongDevice(ValueError):
    """The endpoint is not a compatible Overlay device."""


@asynccontextmanager
async def open_client(host, port):
    client = AsyncTcpClient(host, port)
    try:
        async with asyncio.timeout(8):
            await client.connect()
        yield client
    finally:
        with suppress(OSError, TimeoutError):
            async with asyncio.timeout(3):
                await client.disconnect()


@dataclass(frozen=True)
class DeviceInfo:
    id: str
    name: str
    version: str
    capabilities: dict[str, Any]


def parse_info(data: dict) -> dict:
    overlay = data.get("vision")
    if (
        not isinstance(overlay, dict)
        or overlay.get("protocol") != PROTOCOL
        or overlay.get("protocol_version") != PROTOCOL_VERSION
        or not isinstance(overlay.get("id"), str)
        or not overlay["id"]
        or not isinstance(overlay.get("name"), str)
        or not overlay["name"]
        or data.get("satellite")
    ):
        raise WrongDevice("Use an Overlay app with protocol version 1")
    return overlay


async def read_expected(client: AsyncTcpClient, event_type: str) -> Event:
    while True:
        event = await client.read_event()
        if event is None:
            raise ConnectionError("Device closed the connection")
        if event.type == "ping":
            await client.write_event(Event(type="pong"))
        elif event.type == "error":
            raise WrongDevice("Device rejected the Overlay handshake")
        elif event.type == event_type:
            return event


async def describe(client: AsyncTcpClient) -> DeviceInfo:
    await client.write_event(Event(type="describe"))
    descriptor = parse_info((await read_expected(client, "info")).data or {})
    await client.write_event(Event(type="capabilities"))
    capabilities = (await read_expected(client, "capabilities")).data or {}
    if (
        capabilities.get("protocol") != PROTOCOL
        or capabilities.get("protocol_version") != PROTOCOL_VERSION
    ):
        raise WrongDevice("Capabilities do not identify an Overlay device")
    roles = capabilities.get("roles")
    from .const import ROLES

    if (
        not isinstance(roles, list)
        or not roles
        or any(role not in ROLES for role in roles)
    ):
        raise WrongDevice("Invalid Vision roles")
    return DeviceInfo(
        descriptor["id"],
        descriptor["name"],
        descriptor.get("version", "unknown"),
        capabilities,
    )


async def probe(host: str, port: int) -> DeviceInfo:
    async with asyncio.timeout(8), open_client(host, port) as client:
        return await describe(client)


class OverlayConnection:
    """One entry-owned connection, including reconnect and device heartbeats.

    Commands fail while offline. They are deliberately not replayed later: an old
    dashboard request or dismissed alarm must not reappear when Wi-Fi recovers.
    """

    def __init__(
        self,
        host: str,
        port: int,
        device_id: str,
        settings: Callable[[], dict],
        changed: Callable[[str, dict], None],
    ):
        self.host, self.port, self.device_id = host, port, device_id
        self.settings, self.changed = settings, changed
        self.client: AsyncTcpClient | None = None
        self.connected = False
        self._write_lock = asyncio.Lock()

    async def send(self, event: Event):
        async with self._write_lock:
            if self.client is None:
                raise ConnectionError("Overlay device is offline")
            async with asyncio.timeout(5):
                await self.client.write_event(event)

    async def action(self, action: str, payload: dict | None = None):
        if not self.connected:
            raise ConnectionError("Overlay device is offline")
        await self.send(
            Event(
                type="custom-event",
                data={
                    "event_type": "action",
                    "action": action,
                    "payload": payload or {},
                },
            )
        )

    async def run(self):
        retry = 1
        while True:
            try:
                async with open_client(self.host, self.port) as client:
                    self.client = client
                    async with asyncio.timeout(10):
                        info = await describe(client)
                        if info.id != self.device_id:
                            raise WrongDevice(
                                "The IP address now belongs to a different device"
                            )
                        await self.send(
                            Event(
                                type="run-vision",
                                data={
                                    "protocol": PROTOCOL,
                                    "protocol_version": PROTOCOL_VERSION,
                                },
                            )
                        )
                        # The device requests settings only after accepting this connection.
                        ready = await read_expected(client, "custom-event")
                        if (ready.data or {}).get("event_type") != "settings":
                            raise WrongDevice(
                                "Missing Overlay connection acknowledgement"
                            )
                    self.connected = True
                    retry = 1
                    self.changed(
                        "connected",
                        {"version": info.version, "capabilities": info.capabilities},
                    )
                    await self.send_settings()
                    while True:
                        async with asyncio.timeout(12):
                            event = await client.read_event()
                        if event is None:
                            raise ConnectionError("Device disconnected")
                        if event.type == "ping":
                            await self.send(Event(type="pong"))
                        elif event.type == "error":
                            self.changed("error", event.data or {})
                        elif event.type == "custom-event":
                            envelope = event.data or {}
                            body = envelope.get("data", envelope)
                            if not isinstance(body, dict):
                                continue
                            kind = envelope.get("event_type", "")
                            if kind == "settings" and "settings" not in body:
                                await self.send_settings()
                            else:
                                self.changed(kind, body)
            except (OSError, TimeoutError, ValueError, KeyError, TypeError):
                pass
            finally:
                self.client = None
                self.connected = False
                self.changed("disconnected", {})
            await asyncio.sleep(retry)
            retry = min(retry * 2, 30)

    async def send_settings(self):
        await self.send(
            Event(
                type="custom-event",
                data={"event_type": "settings", "settings": self.settings()},
            )
        )
