"""New management error boundaries only; no network and no old provisioning-suite replay."""
import hashlib
import importlib.util
import pathlib
import unittest
from unittest import mock

source = pathlib.Path(__file__).resolve().parents[1] / "main/assets/server-management-swg.py"
spec = importlib.util.spec_from_file_location("management_diagnostics_subject", source)
subject = importlib.util.module_from_spec(spec)
spec.loader.exec_module(subject)


class DiagnosticsTest(unittest.TestCase):
    def fixture(self):
        return dict(request_id="00000000-0000-0000-0000-000000000001", action="status",
                    parameters={}, confirmed=False, panel_url="https://fixture.invalid:8443",
                    certificate_sha256=hashlib.sha256(b"fixture-certificate").hexdigest(),
                    username="fixture-only", password="fixture-private-password")

    def run_response(self, status, certificate=b"fixture-certificate"):
        connection = mock.MagicMock()
        connection.getresponse.return_value.status = status
        connection.getresponse.return_value.read.return_value = b'{"private":"fixture-private-token"}'
        tls = mock.MagicMock()
        tls.getpeercert.return_value = certificate
        context = mock.MagicMock()
        context.wrap_socket.return_value = tls
        with mock.patch.object(subject.ssl, "SSLContext", return_value=context), \
             mock.patch.object(subject.socket, "create_connection", return_value=mock.MagicMock()), \
             mock.patch.object(subject.http.client, "HTTPSConnection", return_value=connection):
            with self.assertRaises(ValueError) as caught:
                subject.execute(self.fixture())
        self.assertNotIn("fixture-private", str(caught.exception))
        return str(caught.exception), connection

    def test_auth_missing_method_and_generic_http_errors_are_distinct(self):
        for status, code in ((401, "PANEL_HTTP_401"), (403, "PANEL_HTTP_403"),
                             (404, "PANEL_HTTP_404"), (500, "PANEL_HTTP")):
            with self.subTest(status=status):
                error, connection = self.run_response(status)
                self.assertEqual(code, error)
                self.assertEqual(1, connection.request.call_count)  # no automatic mutation/auth retry

    def test_changed_certificate_stops_before_sending_credentials(self):
        error, connection = self.run_response(200, b"different-certificate")
        self.assertEqual("TLS_PIN", error)
        connection.request.assert_not_called()

    def test_rejected_api_envelope_does_not_expose_response_body(self):
        error, connection = self.run_response(200)
        self.assertEqual("PANEL_REJECTED", error)
        self.assertEqual(1, connection.request.call_count)


if __name__ == "__main__":
    unittest.main()
