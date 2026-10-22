"""New SWG 2.5 boundary cases only; never runs retained provisioning suites."""
import base64
import hashlib
import json
import pathlib
import re
import sys
import types
import unittest
import urllib.parse

sys.dont_write_bytecode = True
root = pathlib.Path(__file__).resolve().parents[5]
source = root / 'ports/android/app/src/main/kotlin/org/mcrf/client/AndroidDeployScripts.kt'
literal = re.search(r'internal val provisioning = """\n(.*?)\n"""\.trimIndent\(\)', source.read_text(encoding='utf-8-sig'), re.S).group(1)
embedded = types.ModuleType('mcrf_provision')
exec(compile(literal.replace("${'$'}", '$'), 'AndroidDeployScripts.provisioning', 'exec'), embedded.__dict__)
fixture = (root / 'wpf/tests/ProvisioningChecks20261013.py').read_text(encoding='utf-8-sig')
fixture = re.sub(r'source = pathlib\.Path.*?spec\.loader\.exec_module\(p\)', 'p = embedded', fixture, count=1, flags=re.S)
fixtures = types.ModuleType('OwnedPanelFixtures')
fixtures.__dict__.update(embedded=embedded, __file__=str(root / 'wpf/tests/ProvisioningChecks20261013.py'))
exec(compile(fixture, 'OwnedPanelFixtures', 'exec'), fixtures.__dict__)

class Swg25Tests(unittest.TestCase):
    def calls(self, offset=0):
        return ['https://vk.ru/call/join/' + base64.urlsafe_b64encode(bytes([offset+i])*32).decode().rstrip('=') for i in range(1, 9)]

    def configure(self, api, **options):
        return embedded.configure_initial(fixtures.result(), 'swg', fixtures.options(**options), 'fixture.example', api, lambda _: None)

    def test_modern_line_and_encrypted_export_preserve_all_calls(self):
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        api = fixtures.FakePanel('swg')
        calls = self.calls()
        result = self.configure(api, relay_kind='csqtt25', vk_calls=calls, wg_port=None)
        self.assertTrue(result['provision_complete'], result['provision_error'])
        body = next(row[2] for row in api.requests if row[1] == '/api/csqtt/set')
        self.assertEqual(('csqtt', '2.5'), (body['fork'], body['line']))
        self.assertNotIn('wg_port', body)
        self.assertLessEqual(len(body['iface']), 15)
        link = result['connections'][0]
        sealed = link.removeprefix('csqtt://')
        data = base64.urlsafe_b64decode(sealed + '='*((-len(sealed)) % 4))
        plain = AESGCM(hashlib.sha256(b'csqtt-chiper-key').digest()).decrypt(data[:12], data[12:], None).decode()
        self.assertTrue(plain.startswith('connect?'))
        query = plain.split('?', 1)[1]
        raw = next(part.split('=', 1)[1] for part in query.split('&') if part.startswith('hashes='))
        self.assertEqual([call.rsplit('/', 1)[1] for call in calls], [urllib.parse.unquote(h) for h in raw.split('+')])

    def test_old_node_rejected_before_creation(self):
        api = fixtures.FakePanel('swg'); api.csqtt_lines = ['2.1']
        result = self.configure(api, relay_kind='csqtt25', vk_calls=self.calls())
        self.assertFalse(result['provision_complete'])
        self.assertFalse(any(row[0] == 'POST' and row[1] != '/api/login' for row in api.requests))

    def test_unsupported_mixed_selection_never_creates_first_protocol(self):
        api = fixtures.FakePanel('swg'); api.csqtt_lines = ['2.1']
        profiles = [dict(kind=kind, enabled=True, relay_port=19001+i, wg_port=19101+i, call_count=6, vk_calls=self.calls(i*10)) for i,kind in enumerate(['qwdtt', 'csqtt25'])]
        result = self.configure(api, swg_profiles=profiles)
        self.assertFalse(result['provision_complete'])
        self.assertFalse(any(row[0] == 'POST' and row[1] != '/api/login' for row in api.requests))

    def test_legacy_choice_remains_plain_and_does_not_request_modern_line(self):
        api = fixtures.FakePanel('swg'); api.csqtt_lines = ['2.1']; api.csqtt_line = '2.1'
        result = self.configure(api, relay_kind='csqtt', vk_calls=self.calls())
        self.assertTrue(result['provision_complete'], result['provision_error'])
        body = next(row[2] for row in api.requests if row[1] == '/api/csqtt/set')
        self.assertEqual('csqtt', body['fork']); self.assertNotIn('line', body)
        self.assertTrue(result['connections'][0].startswith('csqtt://connect?'))

if __name__ == '__main__':
    if sys.argv[1:] == ['--subnet-followup']:
        class SubnetFollowupTests(unittest.TestCase):
            def test_existing_fleet_subnets_are_not_reused(self):
                state = {'nodes': [{'wdtt_cfg': {'q': {'wg_addr': '10.88.0.1/24'}, 'w': {'wg_addr': '10.89.0.1/24'}},
                                    'csqtt_cfg': {'c': {'tun_addr': '10.90.0.1/24'}, 'modern': {'tun_addr': '10.91.0.1/24'}}}]}
                before = json.dumps(state)
                self.assertEqual('10.88.1.1/24', embedded.free_swg_subnet(state, '10.91.0.1/24'))
                self.assertEqual(before, json.dumps(state))

            def test_modern_add_uses_free_subnet_without_rewriting_existing_instance(self):
                api = fixtures.FakePanel('swg'); original = api.request
                def request(method, path, body=None, **kwargs):
                    result = original(method, path, body, **kwargs)
                    if method == 'GET' and path == '/api/state':
                        result['data']['nodes'][0]['csqtt_cfg'] = {'old': {'tun_addr': '10.91.0.1/24'}}
                    return result
                api.request = request
                calls = Swg25Tests().calls()
                result = embedded.configure_initial(fixtures.result(), 'swg', fixtures.options(relay_kind='csqtt25', vk_calls=calls), 'fixture.example', api, lambda _: None)
                self.assertTrue(result['provision_complete'], result['provision_error'])
                created = [row[2] for row in api.requests if row[1] == '/api/csqtt/set']
                self.assertEqual(1, len(created)); self.assertEqual('10.88.0.1/24', created[0]['tun_addr'])
                self.assertNotEqual('old', created[0]['iface'])

            def test_malformed_inventory_fails_closed_and_api_errors_do_not_echo_secrets(self):
                with self.assertRaises(embedded.ProvisionError):
                    embedded.free_swg_subnet({'nodes': [{'csqtt_cfg': {'old': {'tun_addr': 'invalid'}}}]}, '10.91.0.1/24')
                text = embedded.panel_api_error('POST', '/api/csqtt/set', 400, json.dumps({'error': 'subnet overlaps secret-fixture-password'}).encode())
                self.assertIn('HTTP 400', text); self.assertIn('/api/csqtt/set', text)
                self.assertNotIn('secret-fixture-password', text)
                self.assertIn('Подсеть', text)
        outcome = unittest.TextTestRunner(verbosity=1).run(unittest.defaultTestLoader.loadTestsFromTestCase(SubnetFollowupTests))
        sys.exit(0 if outcome.wasSuccessful() else 1)
    unittest.main()
