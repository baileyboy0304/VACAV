"""Exercise matching against HA-shaped state and entity registries."""
import importlib.util
from pathlib import Path
import sys
from types import ModuleType, SimpleNamespace as NS
import unittest
from unittest.mock import patch
import voluptuous as vol

ROOT = Path(__file__).parents[1]/'custom_components/vaca_vision'

class Inspection(unittest.TestCase):
    def setUp(self):
        names = ['homeassistant','homeassistant.components','homeassistant.components.panel_custom',
                 'homeassistant.components.websocket_api','homeassistant.components.http',
                 'homeassistant.helpers','homeassistant.helpers.entity_registry','homeassistant.helpers.storage',
                 'paneltest']
        modules = {n:ModuleType(n) for n in names}
        modules['paneltest'].__path__ = [str(ROOT)]
        ws=modules['homeassistant.components.websocket_api']
        ws.websocket_command=lambda schema: lambda f:f
        ws.require_admin=ws.async_response=lambda f:f
        modules['homeassistant.components.http'].StaticPathConfig=lambda *a:a
        modules['homeassistant.helpers.storage'].Store=lambda *a,**k:None
        self.registry=NS(entities={})
        self.registry.async_get=self.registry.entities.get
        er=modules['homeassistant.helpers.entity_registry']
        er.async_get=lambda hass:self.registry
        er.async_entries_for_config_entry=lambda reg,eid:[r for r in reg.entities.values() if r.config_entry_id==eid]
        self.patch=patch.dict(sys.modules,modules);self.patch.start();self.addCleanup(self.patch.stop)
        for name in ('const','es8_setup','es8_panel'):
            spec=importlib.util.spec_from_file_location('paneltest.'+name,ROOT/(name+'.py'))
            mod=importlib.util.module_from_spec(spec);sys.modules[spec.name]=mod;spec.loader.exec_module(mod)
        self.m=sys.modules['paneltest.es8_panel']
        self.plan={'version':1,'name':'Garden','services':[]}
        self.entry=NS(entry_id='vision',data={'setup_plan':self.plan,'ma_entry_id':'ma','ma_player_id':'physical'},options={})
        self.states={}
        def add(eid,platform,entry,uid,state):
            self.registry.entities[eid]=NS(entity_id=eid,platform=platform,config_entry_id=entry,unique_id=uid,device_id='garden')
            self.states[eid]=NS(entity_id=eid,state=state,attributes={})
        add('assist_satellite.garden','vaca_vision','vision','mic','idle')
        add('sensor.garden_display','vaca_vision','vision','display','va-garden')
        add('media_player.garden','music_assistant','ma','physical','idle')
        add('media_player.other','music_assistant','ma','other','playing')
        add('weather.home','weather','weather','weather','sunny')
        self.hass=NS(data={'view_assist':{'va_browser_ids':{'va-garden':'Garden'}}},
                     states=NS(get=self.states.get,async_all=lambda domain:[s for e,s in self.states.items() if e.startswith(domain+'.')]),
                     config_entries=NS(async_entries=lambda domain:[]))

    def test_only_matching_official_physical_player_is_selected(self):
        result=self.m.inspect(self.hass,self.entry)
        self.assertEqual(result['player'],'media_player.garden')
        self.assertEqual(result['microphone'],'assist_satellite.garden')
        self.assertFalse(result['errors'])

    def test_display_sensor_without_browser_signin_is_not_ready(self):
        self.hass.data['view_assist']['va_browser_ids']={}
        result=self.m.inspect(self.hass,self.entry)
        self.assertTrue(any('browser' in e for e in result['errors']))

    def test_unavailable_player_is_not_replaced_with_other_device(self):
        self.states['media_player.garden'].state='unavailable'
        result=self.m.inspect(self.hass,self.entry)
        self.assertIsNone(result['player'])
        self.assertTrue(result['errors'])

    def test_unconnected_microphone_blocks_setup(self):
        self.states['assist_satellite.garden'].state='unavailable'
        self.assertTrue(self.m.inspect(self.hass,self.entry)['errors'])
