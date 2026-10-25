import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('es8_setup', Path(__file__).parents[1] / 'custom_components/vaca_vision/es8_setup.py')
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)

class Setup(unittest.TestCase):
    def setUp(self):
        self.plan = {'version':1, 'name':'Garden', 'services':['iplayer'],
                     'home_slug':'garden-home', 'va_slug':'garden-va', 'tv_slug':'garden-tv',
                     'sendspin_client_id':'A'*43}

    def test_only_allowlisted_nonsecret_choices_survive(self):
        value = m.validate_plan({**self.plan, 'password':'secret', 'template':'untrusted', 'url':'https://attacker.invalid'})
        self.assertEqual(value, self.plan)

    def test_reject_traversal_duplicate_paths_and_unknown_services(self):
        for extra in ({'home_slug':'../private'}, {'tv_slug':'garden-home'}, {'services':['shell']},
                      {'sendspin_client_id':'bad'}, {'version':2}):
            with self.assertRaises(ValueError): m.validate_plan({**self.plan, **extra})

    def test_render_selected_services_and_all_dashboard_paths(self):
        result = m.render(self.plan, 'sensor.garden', 'weather.garden', '/abc_music_assistant', m.load_templates())
        self.assertEqual(result['home']['views'][1]['cards'][0]['addon'], 'abc_music_assistant')
        cards = result['tv']['views'][0]['cards'][0]['custom_fields']['content']['card']['cards']
        self.assertEqual([c['name'] for c in cards], ['Home', 'BBC iPlayer'])
        import json
        text = json.dumps(result)
        for old in ('sensor.es8_setup', 'weather.home', '/view-assist-athena', '/local/es8-athena/'):
            self.assertNotIn(old, text)
        self.assertIn('/vaca_vision/es8-assets/', text)
        self.assertIn('sensor.garden', text)

    def test_remote_music_url_rejected(self):
        for path in ('https://other.invalid', '//other.invalid', '/path?script=x'):
            with self.assertRaises(ValueError):
                m.render(self.plan, 'sensor.garden', 'weather.garden', path, m.load_templates())
