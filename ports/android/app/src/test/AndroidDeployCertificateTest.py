"""Exercise the embedded HTTPS probe without network, servers or account changes."""
import base64
import hashlib
import io
import json
import os
import pathlib
import re
import unittest
from contextlib import redirect_stdout
from unittest.mock import patch, MagicMock

source = pathlib.Path(__file__).resolve().parents[1] / 'main/kotlin/org/mcrf/client/AndroidDeployScripts.kt'
probe = re.search(r'internal val probe = """\n(.*?)\n"""\.trimIndent\(\)', source.read_text(encoding='utf-8-sig'), re.S).group(1)


class CertificateGateTests(unittest.TestCase):
    def invoke(self, expected):
        calls = []
        tls = MagicMock()
        tls.getpeercert.return_value = b'fixture-certificate'
        tls.recv.return_value = b'HTTP/1.1 200 OK\r\n'
        context = MagicMock()
        context.wrap_socket.return_value.__enter__.return_value = tls
        env = dict(MCRF_PANEL_URL='https://fixture.invalid:8443/', MCRF_PANEL_PORT='8443',
                   MCRF_PANEL_USER='fixture', MCRF_PANEL_PASS='fixture-password',
                   MCRF_PROVISION_KIND='3xui', MCRF_PROVISION_HOST='fixture.invalid',
                   MCRF_PROVISION_OPTIONS=base64.b64encode(json.dumps({'create': True}).encode()).decode())
        if expected is not None:
            env['MCRF_EXPECTED_CERT'] = expected
        def configure(result, *args):
            calls.append(result)
            return result
        with patch.dict(os.environ, env, clear=True), patch('socket.create_connection', return_value=MagicMock()), patch('ssl.SSLContext', return_value=context), redirect_stdout(io.StringIO()):
            try:
                exec(compile(probe, 'embedded-probe', 'exec'), {'configure_initial': configure})
            except RuntimeError as error:
                return calls, str(error)
        return calls, None

    def test_changed_certificate_blocks_before_any_provisioning(self):
        calls, error = self.invoke('a' * 64)
        self.assertEqual([], calls)
        self.assertIn('no transport creation attempted', error)

    def test_matching_certificate_allows_exactly_one_provisioning_call(self):
        calls, error = self.invoke(hashlib.sha256(b'fixture-certificate').hexdigest())
        self.assertIsNone(error)
        self.assertEqual(1, len(calls))

    def test_initial_install_without_saved_certificate_remains_compatible(self):
        calls, error = self.invoke(None)
        self.assertIsNone(error)
        self.assertEqual(1, len(calls))


if __name__ == '__main__':
    unittest.main()
