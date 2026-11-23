"""Exercise generated flow module layout without compiling or provider operations."""
from pathlib import Path
import tempfile
import unittest

from prepare_csqtt_flow_harness import prepare


class FlowHarnessLayoutTest(unittest.TestCase):
    def test_both_generated_auth_modules_resolve_their_sibling_backoff_module(self):
        auth = "\n".join(name + " {}" for name in (
            "pub async fn get_credentials()", "async fn mcrf_fetch_credentials()",
            "async fn vk_calls_with_retries()", "pub struct TurnCredentials",
            "pub struct CallUnavailable", "pub enum VkRateLimitType", "pub struct VkRateLimitError"))
        for name in ("post_empty", "post_form"):
            auth += f"\nasync fn {name}() {{ mcrf_vk_backoff::provider_allowed()?; }}"
        worker = "\n".join(name + " {}" for name in (
            "struct HashAvailability", "impl HashAvailability", "pub struct GroupCredentials",
            "struct CachedCredentials", "struct CredentialLease", "struct CredentialCache",
            "pub fn credential_failure_delay()", "fn quota_rotation_delay()",
            "fn credential_stream_id()", "fn same_turn_credentials()"))
        worker += "\nimpl GroupCredentials { fn is_unavailable() {} fn is_exhausted() {} async fn get() {} }"
        worker += "\nimpl CredentialCache { fn reserve() {} fn store() {} fn next_quota_delay() {} }"
        js = "\n".join(name + " {}" for name in (
            "async fn retry<T>()", "fn mcrf_api_error()", "fn check_api_error()", "fn json_scalar_string()"))
        js += "\nasync fn post_form() { crate::auth::mcrf_vk_backoff::provider_allowed()?; }"
        module = Path(__file__).with_name("mcrf_vk_backoff.rs").read_bytes()
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for generation in ("g251", "g253"):
                crate = root / "adapters" / generation / "rust-client"
                crate.mkdir(parents=True)
                for name, content in (("auth.rs", auth), ("worker.rs", worker), ("vk_js_calls.rs", js)):
                    (crate / name).write_text(content, encoding="utf-8")
                (crate / "mcrf_vk_backoff.rs").write_bytes(module)
            prepare(root)
            for generation in ("g251", "g253"):
                flow = root / "admission-tests/flows" / generation
                rendered = (flow / "auth.rs").read_text(encoding="utf-8")
                self.assertIn('#[path = "mcrf_vk_backoff.rs"]\npub(crate) mod mcrf_vk_backoff;', rendered)
                self.assertEqual(module, (flow / "mcrf_vk_backoff.rs").read_bytes())
                self.assertFalse((flow / "auth/mcrf_vk_backoff.rs").exists())
                self.assertNotIn("// MCRF_AUTH_METHODS", rendered)
            self.assertTrue((root / "admission-tests/extracted-flow-source.json").is_file())


if __name__ == "__main__":
    unittest.main()
