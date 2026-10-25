"""Reserve EDA's ESPHome discovery identity without creating another device."""

from homeassistant.config_entries import SOURCE_IGNORE

from .const import ROLE_UPSTREAM

OWNER = "vaca_vision_owner"


async def async_prepare_discovery(hass, entry, mac=None):
    """Use HA's normal Ignore flow; preserve ignores created by the owner."""
    values = {**entry.data, **entry.options}
    desired = mac.lower() if mac and values.get("role") == ROLE_UPSTREAM else None
    await async_release_discovery(hass, entry, except_mac=desired)
    if desired is None:
        return
    existing = hass.config_entries.async_entry_for_domain_unique_id("esphome", desired)
    if existing is not None:
        if existing.source != SOURCE_IGNORE:
            raise ValueError("eda_already_owned")
        return
    result = await hass.config_entries.flow.async_init(
        "esphome",
        context={"source": SOURCE_IGNORE},
        data={"unique_id": desired, "title": f"{entry.title} — managed by Vision"},
    )
    if result["type"] != "create_entry":
        raise ValueError("Unable to reserve EDA discovery identity")
    ignored = result["result"]
    hass.config_entries.async_update_entry(ignored, data={OWNER: entry.entry_id})


async def async_release_discovery(hass, entry, *, except_mac=None):
    """Release only our own Ignore marker when unpairing or changing roles."""
    for ignored in hass.config_entries.async_entries("esphome"):
        if (
            ignored.source == SOURCE_IGNORE
            and ignored.data.get(OWNER) == entry.entry_id
            and ignored.unique_id != except_mac
        ):
            await hass.config_entries.async_remove(ignored.entry_id)
