"""Compatibility extends only VA's microphone filter, never a device entry."""
import importlib.util
from pathlib import Path
from types import ModuleType, SimpleNamespace as NS
import sys
import unittest
from unittest.mock import patch
import voluptuous as vol

class SelectorTest(unittest.TestCase):
    def setUp(self):
        class Selector:
            def __init__(self, config): self.config=config
            def __call__(self, value): return value
        modules={n:ModuleType(n) for n in ['homeassistant','homeassistant.helpers','homeassistant.helpers.selector','homeassistant.loader']}
        modules['homeassistant.helpers.selector'].EntitySelector=Selector
        modules['homeassistant.loader'].async_get_integration=None
        self.patch=patch.dict(sys.modules,modules);self.patch.start();self.addCleanup(self.patch.stop)
        spec=importlib.util.spec_from_file_location('selector_test',Path(__file__).parents[1]/'custom_components/vaca_vision/view_assist_selector.py')
        self.m=importlib.util.module_from_spec(spec);spec.loader.exec_module(self.m)
        self.Selector=Selector
    def test_preserves_other_fields_and_is_idempotent(self):
        mic=vol.Required('mic_device')
        f=NS(BASE_DEVICE_SCHEMA=vol.Schema({mic:self.Selector({'filter':[{'integration':'esphome','domain':'assist_satellite'}]}),vol.Required('name'):str}))
        self.assertTrue(self.m.extend_microphone_selector(f))
        self.assertFalse(self.m.extend_microphone_selector(f))
        self.assertEqual(len(f.BASE_DEVICE_SCHEMA.schema[mic].config['filter']),2)
        self.assertIs(f.BASE_DEVICE_SCHEMA.schema[vol.Required('name')],str)
    def test_unrestricted_selector_needs_no_change(self):
        f=NS(BASE_DEVICE_SCHEMA=vol.Schema({vol.Required('mic_device'):self.Selector({'domain':'assist_satellite'})}))
        self.assertFalse(self.m.extend_microphone_selector(f))
