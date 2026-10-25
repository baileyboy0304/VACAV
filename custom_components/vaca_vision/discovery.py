"""Discover running Vision displays without probing legacy VACA protocols."""

import asyncio
from contextlib import suppress

from .const import SERVICE_TYPE
from .protocol import probe


async def discover(hass):
    from homeassistant.components.zeroconf import async_get_async_instance
    from zeroconf import ServiceStateChange
    from zeroconf.asyncio import AsyncServiceBrowser, AsyncServiceInfo

    instance = await async_get_async_instance(hass)
    endpoints = set()
    tasks = set()

    async def resolve(name):
        info = AsyncServiceInfo(SERVICE_TYPE, name)
        if await info.async_request(instance.zeroconf, 1500):
            for address in info.parsed_scoped_addresses():
                endpoints.add((address, info.port))

    def changed(zeroconf, service_type, name, state_change):
        if state_change == ServiceStateChange.Removed:
            return
        task = hass.async_create_task(resolve(name))
        tasks.add(task)
        task.add_done_callback(tasks.discard)

    browser = AsyncServiceBrowser(instance.zeroconf, SERVICE_TYPE, handlers=[changed])
    try:
        await asyncio.sleep(2)
        if tasks:
            await asyncio.gather(*list(tasks), return_exceptions=True)
    finally:
        await browser.async_cancel()
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)

    found = {}

    async def check(host, port):
        with suppress(OSError, TimeoutError, ValueError, KeyError, TypeError):
            info = await probe(host, port)
            found[info.id] = (host, port, info.name)

    await asyncio.gather(*(check(host, port) for host, port in endpoints))
    return found
