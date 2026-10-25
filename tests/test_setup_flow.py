"""Setup behavior without requiring a running HA server."""
import importlib.util
import json
from pathlib import Path
import sys
from types import ModuleType, SimpleNamespace as NS
import unittest
from unittest.mock import AsyncMock, patch

ROOT = Path(__file__).resolve().parents[1] / 'custom_components/vaca_vision'

def load(name, file):
    spec = importlib.util.spec_from_file_location(name, ROOT / file)
    m = importlib.util.module_from_spec(spec); sys.modules[name] = m
    spec.loader.exec_module(m)
    return m

class Matching(unittest.TestCase):
    def setUp(self):
        self.m = load('setup_matching_test', 'setup_helpers.py')
    def test_identity_match_and_conflicting_mac(self):
        p=NS(device_info=NS(ip_address='192.0.2.1',mac_address='AA:BB'))
        self.assertTrue(self.m.player_matches(p,'192.0.2.1'))
        self.assertTrue(self.m.player_matches(p,'192.0.2.99','aa-bb'))
        self.assertFalse(self.m.player_matches(p,'192.0.2.1','cc:dd'))
        self.assertFalse(self.m.player_matches(p,'192.0.2.2'))
        self.assertFalse(self.m.player_matches(p,'unknown'))
    def test_excludes_groups_proxies_and_unavailable_players(self):
        providers={'ss':NS(domain='sendspin'),'ha':NS(domain='hass')}
        p=NS(provider='ss',type='player',available=True,enabled=True)
        self.assertTrue(self.m.physical_sendspin(p,providers))
        for key,value in [('type','group'),('provider','ha'),('available',False),('enabled',False)]:
            q=NS(**vars(p)); setattr(q,key,value)
            self.assertFalse(self.m.physical_sendspin(q,providers))

    def test_universal_player_uses_direct_sendspin_protocol(self):
        protocol=NS(protocol_domain='sendspin',available=True,derived_from=None,output_protocol_id='public-id')
        p=NS(provider='universal_player',type='player',available=True,enabled=True,needs_setup=False,output_protocols=[protocol],player_id='universal-id',device_info=NS(ip_address=None,mac_address=None))
        self.assertTrue(self.m.physical_sendspin(p,{}))
        self.assertTrue(self.m.player_matches(p,'192.0.2.1',client_id='public-id'))
        self.assertFalse(self.m.player_matches(p,'192.0.2.1',client_id='other-id'))
        p.type='group'
        self.assertFalse(self.m.physical_sendspin(p,{}))
        p.type='player'; protocol.derived_from='airplay'
        self.assertFalse(self.m.physical_sendspin(p,{}))

class Flow(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        names=['homeassistant','homeassistant.config_entries','homeassistant.core',
               'homeassistant.helpers','homeassistant.helpers.selector','aioesphomeapi',
               'flow_test','flow_test.adb','flow_test.installer','flow_test.protocol',
               'flow_test.setup_helpers','flow_test.upstream']
        modules={n:ModuleType(n) for n in names}
        class Base:
            def __init_subclass__(cls,**kwargs): pass
            def async_show_form(self,**kw): return {'type':'form',**kw}
            def async_abort(self,**kw): return {'type':'abort',**kw}
            def async_create_entry(self,**kw): return {'type':'create_entry',**kw}
            def _abort_if_unique_id_configured(self,**kw): pass
        modules['homeassistant.config_entries'].ConfigFlow=Base
        modules['homeassistant.config_entries'].OptionsFlow=Base
        modules['homeassistant.core'].callback=lambda f:f
        selector=modules['homeassistant.helpers.selector']
        for name in ['SelectSelector','SelectSelectorConfig','TextSelector','TextSelectorConfig']:
            setattr(selector,name,lambda x=None,**kw:x if x is not None else kw)
        selector.SelectSelectorMode=NS(DROPDOWN='dropdown'); selector.TextSelectorType=NS(PASSWORD='password')
        modules['flow_test.adb'].AdbSetupError=RuntimeError
        for name in ['grant_overlay','require_adb']: setattr(modules['flow_test.adb'],name,lambda:None)
        for name in ['inspect_target','install_target','package_kind']: setattr(modules['flow_test.installer'],name,lambda:None)
        modules['flow_test.protocol'].WrongDevice=ValueError
        self.probe=AsyncMock(return_value=NS(id='uuid',name='AEOAT',capabilities={'roles':['standard_upstream_eda']}))
        modules['flow_test.protocol'].probe=self.probe
        self.choices=AsyncMock(return_value=({},[])); self.defaults=AsyncMock(return_value={})
        modules['flow_test.setup_helpers'].music_choices=self.choices
        modules['flow_test.setup_helpers'].setup_defaults=self.defaults
        self.validate=AsyncMock(return_value='aa:bb'); modules['flow_test.upstream'].validate_upstream=self.validate
        self.invalid=type('InvalidKey',(Exception,),{}); modules['aioesphomeapi'].InvalidEncryptionKeyAPIError=self.invalid
        self.patch=patch.dict(sys.modules,modules); self.patch.start(); self.addCleanup(self.patch.stop)
        load('flow_test.const','const.py'); m=load('flow_test.config_flow','config_flow.py')
        self.flow=m.VisionConfigFlow(); self.flow.hass=NS(data={}); self.flow.show_advanced_options=False
        self.flow._device={'id':'uuid','host':'192.0.2.1','port':26900,'name':'Athena','roles':['standard_upstream_eda'],'role':'standard_upstream_eda','dashboard':'/saved-home/home'}
        self.key=json.dumps(['server','speaker'],separators=(',',':'))
    async def test_confirmation_only_asks_name_and_uses_installer_defaults(self):
        self.defaults.return_value={'name':'Saved name','dashboard':'/saved-home/home','role':'standard_upstream_eda'}
        r=await self.flow.async_step_confirm()
        self.assertEqual(r['step_id'],'confirm_es8')
        self.assertEqual([str(k) for k in r['data_schema'].schema],['name'])
        self.assertEqual(self.flow._device['dashboard'],'/saved-home/home')
    async def test_exact_match_finishes_without_player_id_or_key_prompt(self):
        self.choices.return_value=({self.key:'Garden'},[self.key])
        r=await self.flow.async_step_confirm({'name':'Garden'})
        self.assertEqual(r['type'],'create_entry')
        self.assertEqual(r['data']['ma_player_id'],'speaker')
        self.assertEqual(r['data']['eda_mac'],'aa:bb')
        self.assertTrue(self.validate.call_args.kwargs['expose_music'])
    async def test_ambiguous_match_requires_named_choice(self):
        other=json.dumps(['other','speaker'],separators=(',',':'))
        self.choices.return_value=({self.key:'Garden',other:'Other'},[self.key,other])
        r=await self.flow.async_step_upstream()
        self.assertEqual(r['type'],'form'); self.validate.assert_not_called()
        r=await self.flow.async_step_upstream({'music_player':self.key})
        self.assertEqual(r['type'],'create_entry')
    async def test_no_player_has_actionable_retry(self):
        r=await self.flow.async_step_upstream()
        self.assertEqual(r['errors']['base'],'ma_pair_first')
        self.validate.assert_not_called()
    async def test_unknown_key_only_then_asks_for_key(self):
        self.choices.return_value=({self.key:'Garden'},[self.key]); self.validate.side_effect=self.invalid()
        r=await self.flow.async_step_upstream()
        self.assertEqual([str(k) for k in r['data_schema'].schema],['eda_noise_psk'])
        self.validate.side_effect=None
        r=await self.flow.async_step_upstream({'eda_noise_psk':'saved-key'})
        self.assertEqual(r['type'],'create_entry')
    async def test_stale_or_forged_player_choice_cannot_finish(self):
        self.choices.return_value=({self.key:'Garden'},[self.key])
        r=await self.flow.async_step_upstream({'music_player':'invalid'})
        self.assertEqual(r['type'],'form'); self.validate.assert_not_called()
    async def test_installer_defaults_are_bound_to_probed_uuid(self):
        r=await self.flow.async_step_user({'prepare_only':True,'host':'192.0.2.1','name':'Garden','dashboard':'/garden-home/home'})
        self.assertEqual(r['reason'],'setup_prepared')
        self.assertEqual(self.defaults.call_args.args[1],'uuid')
    async def test_installer_rejects_external_dashboard(self):
        r=await self.flow.async_step_user({'prepare_only':True,'host':'192.0.2.1','name':'Garden','dashboard':'//example.com'})
        self.assertEqual(r['reason'],'setup_prepare_failed'); self.probe.assert_not_called()

    async def test_every_displayed_form_has_a_submission_handler(self):
        result = await self.flow.async_step_confirm()
        handler = getattr(self.flow, 'async_step_' + result['step_id'])
        self.choices.return_value=({self.key:'Garden'},[self.key])
        self.assertEqual((await handler({'name':'Garden'}))['type'], 'create_entry')
        self.flow._device.pop('ma_player_id')
        self.choices.return_value=({},[])
        result=await self.flow.async_step_upstream()
        handler=getattr(self.flow,'async_step_'+result['step_id'])
        self.choices.return_value=({self.key:'Garden'},[self.key])
        self.assertEqual((await handler({}))['type'],'create_entry')

    async def test_installer_handoff_after_discovery_is_used_on_submit(self):
        await self.flow.async_step_confirm()
        self.defaults.return_value={'name':'Installer name','dashboard':'/later-home/home','role':'standard_upstream_eda'}
        self.choices.return_value=({self.key:'Garden'},[self.key])
        result=await self.flow.async_step_confirm_es8({'name':'User name'})
        self.assertEqual(result['data']['dashboard'],'/later-home/home')
        self.assertEqual(result['data']['name'],'User name')

    async def test_installer_public_identity_is_retained_for_matching(self):
        r=await self.flow.async_step_user({'prepare_only':True,'host':'192.0.2.1','name':'Garden','dashboard':'/home/home','sendspin_client_id':'A'*43})
        self.assertEqual(r['description_placeholders']['setup_protocol'],'2')
        self.assertEqual(self.defaults.call_args.args[2]['sendspin_client_id'],'A'*43)
