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
        self.entry=NS(title='Garden',entry_id='vision',data={'setup_plan':self.plan,'ma_entry_id':'ma','ma_player_id':'physical'},options={})
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

    def test_browser_cache_is_not_required_for_an_existing_mapping(self):
        self.hass.data['view_assist']['va_browser_ids']={}
        result=self.m.inspect(self.hass,self.entry)
        self.assertEqual(result['display'],'va-garden')
        self.assertFalse(result['errors'])

    def test_unavailable_player_is_not_replaced_with_other_device(self):
        self.states['media_player.garden'].state='unavailable'
        result=self.m.inspect(self.hass,self.entry)
        self.assertIsNone(result['player'])
        self.assertTrue(result['errors'])

    def test_unconnected_microphone_blocks_setup(self):
        self.states['assist_satellite.garden'].state='unavailable'
        self.assertTrue(self.m.inspect(self.hass,self.entry)['errors'])

    def test_weather_is_read_from_this_va_device_only(self):
        self.registry.entities['sensor.va']=NS(entity_id='sensor.va',platform='view_assist',config_entry_id='va')
        self.states['sensor.va']=NS(entity_id='sensor.va',state='normal',attributes={'weather_entity':'weather.garden'})
        self.states['weather.garden']=NS(entity_id='weather.garden',state='sunny',attributes={})
        self.hass.config_entries.async_entries=lambda domain:[NS(entry_id='va',data={'display_device':'va-garden'})]
        result=self.m.inspect(self.hass,self.entry)
        self.assertEqual(result['suggested_weather'],'weather.garden')
        self.assertEqual(result['va_sensor'],'sensor.va')

    def test_missing_va_weather_is_not_inherited_from_another_device(self):
        self.registry.entities['sensor.other_va']=NS(entity_id='sensor.other_va',platform='view_assist',config_entry_id='other_va')
        self.states['sensor.other_va']=NS(entity_id='sensor.other_va',state='normal',attributes={'weather_entity':'weather.home'})
        self.assertIsNone(self.m.inspect(self.hass,self.entry)['suggested_weather'])

    def test_dashboard_preparation_preserves_custom_va_home(self):
        import asyncio
        from unittest.mock import AsyncMock, Mock
        self.entry.domain='vaca_vision';self.entry.data['role']='standard_upstream_eda'
        self.hass.config_entries.async_get_entry=lambda eid:self.entry
        self.hass.config_entries.async_update_entry=Mock()
        self.hass.async_add_executor_job=AsyncMock(return_value={})
        self.states['sensor.va']=NS(attributes={'mic_device':'assist_satellite.garden','display_device':'va-garden',
            'mediaplayer_device':'media_player.garden','musicplayer_device':'media_player.garden',
            'weather_entity':'weather.home','home_screen':'/my-existing-dashboard/home'})
        data=self.m.inspect(self.hass,self.entry);data['va_sensor']='sensor.va'
        data['plan'].update(home_slug='garden-home',va_slug='garden-va',tv_slug='garden-tv')
        connection=NS(send_result=Mock(),send_error=Mock())
        with patch.object(self.m,'inspect',return_value=data),patch.object(self.m,'render',return_value={k:{} for k in ['home','va','tv']}):
            asyncio.run(self.m.setup_command(self.hass,connection,{'id':1,'operation':'dashboards','entry_id':'vision','music_path':'/music-assistant'}))
        connection.send_error.assert_not_called();connection.send_result.assert_called_once()
        self.hass.config_entries.async_update_entry.assert_not_called()
        self.assertEqual(self.states['sensor.va'].attributes['home_screen'],'/my-existing-dashboard/home')
