"""Expose VACAV satellites in older View Assist microphone selectors."""
import logging

from homeassistant.helpers.selector import EntitySelector
from homeassistant.loader import async_get_integration

_LOGGER = logging.getLogger(__name__)


def extend_microphone_selector(flow):
    """Extend the form's integration filter; leave VA entries and files untouched."""
    schema = getattr(flow, 'BASE_DEVICE_SCHEMA', None)
    if schema is None:
        return False
    for key, selector in schema.schema.items():
        if str(key) != 'mic_device' or not isinstance(selector, EntitySelector):
            continue
        config = dict(selector.config)
        filters = config.get('filter')
        if not filters:
            return False  # An unrestricted selector already includes VACAV.
        filters = list(filters) if isinstance(filters, list) else [filters]
        if any(f.get('integration') == 'vaca_vision' for f in filters):
            return False
        config['filter'] = [*filters, {'integration': 'vaca_vision', 'domain': 'assist_satellite'}]
        flow.BASE_DEVICE_SCHEMA = schema.extend({key: EntitySelector(config)})
        return True
    return False


async def async_prepare_selector(hass):
    if not hass.config_entries.async_entries('view_assist'):
        return
    try:
        integration = await async_get_integration(hass, 'view_assist')
        flow = await integration.async_get_platform('config_flow')
        extend_microphone_selector(flow)
    except (ImportError, AttributeError, TypeError, ValueError):
        _LOGGER.warning('View Assist microphone selector could not be extended; check the installed View Assist version')
