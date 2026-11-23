"""Source-only overlay regressions. No provider access or native compiler."""
import unittest
from pathlib import Path
from prepare_csqtt_vk_backoff import transform
from prepare_csqtt_flow_harness import block


class VkBackoffOverlayTest(unittest.TestCase):
    auth = '''impl VkAuth {
    pub async fn get_credentials(&self, link: &str, stream_id: usize) -> Result<TurnCredentials> {
                Err(auto_js_error) => {
                        Err(auto_error) => {
}'''
    auth += '\nasync fn post_empty(client: &Client, endpoint: &str, profile: &Profile) -> Result<Value> { }\n    let mut headers = browser_headers(profile)?;\n'
    auth += ('return Err(VkRateLimitError {\n code: 9,\n}\n.into());\n')*6
    worker = '''        let _guard = tokio::select! {
            _ = self.context.cancel.cancelled() => return None,
            _ = self.availability.cancel.cancelled() => return None,
            guard = self.context.credential_pacer.lock() => guard,
        };
let delay = if error.downcast_ref::<VkRateLimitError>().is_some() || failures >= 5 {
                    self.failures.store(0, Ordering::Relaxed);
                    Duration::from_secs(30)
                } else {'''
    js = '''use crate::auth::{TurnCredentials, parse_turn_credentials};
            Err(error) => last_error = Some(error),
    if !status.is_success() {
fn check_api_error(value: &Value) -> Result<()> {
        bail!("API {code}: {message}");
        bail!("API {code}: {message}");'''
    js += '\n    let body = form(fields);\n'

    def test_all_layers_preserve_typed_limits_and_no_auth_fallback(self):
        auth, worker, js = transform(self.auth, self.worker, self.js)
        self.assertIn("mcrf_vk_backoff::fetch(self, link, stream_id).await", auth)
        self.assertIn("async fn mcrf_fetch_credentials", auth)
        self.assertIn("is_some() => Err(auto_js_error)", auth)
        self.assertIn("is_some() => return Err(auto_error)", auth)
        self.assertNotIn("credential_pacer.lock()", worker)
        self.assertIn("limit.cooldown.max(Duration::from_secs(30))", worker)
        self.assertIn("is_some() => return Err(error)", js)
        self.assertEqual(js.count("return Err(mcrf_api_error(&code, message))"), 2)
        self.assertIn('"9" => Some((VkRateLimitType::FloodControl, 60))', js)
        self.assertIn("status.as_u16() == 429", js)
        self.assertEqual(auth.count("mcrf_vk_backoff::provider_allowed()?"),2)
        self.assertIn("crate::auth::mcrf_vk_backoff::provider_allowed()?",js)
        self.assertEqual(auth.count("mcrf_vk_backoff::limited(VkRateLimitError"),6)

    def test_reapplication_or_source_drift_is_rejected(self):
        with self.assertRaises(ValueError):
            transform(*transform(self.auth, self.worker, self.js))
        for index in range(3):
            sources = [self.auth, self.worker, self.js]
            sources[index] = "unrecognized upstream"
            with self.assertRaises(ValueError):
                transform(*sources)

    def test_scope_module_checks_pause_before_fetch_and_keeps_cached_leases_outside(self):
        module = Path(__file__).with_name("mcrf_vk_backoff.rs").read_text(encoding="utf-8")
        self.assertLess(module.index("if let Some(limit) = remaining()"), module.index("let result = request().await"))
        self.assertIn("static SCOPE: OnceLock<Gate>", module)
        self.assertIn("Semaphore::new(2)", module)
        self.assertIn("tokio::time::timeout(Duration::from_secs(10)", module)
        predicate = block(module, "fn provider_reply(")
        self.assertIn('limit.code!=0 && !limit.message.starts_with("активна ")', predicate)
        gate = block(module, "async fn run<")
        guarded = block(gate, "if let Some(limit) = error.downcast_ref::<VkRateLimitError>().filter(|limit| provider_reply(limit))")
        self.assertIn(".record(limit, now())", guarded)
        self.assertIn('crate::log_error!("MCRF_VK_LIMIT|', guarded)
        limited = block(module, "pub(crate) fn limited(")
        guarded = block(limited, "if provider_reply(&limit)")
        self.assertIn(".record(&limit,Instant::now())", guarded)
        self.assertIn('crate::log_error!("MCRF_VK_LIMIT|', guarded)
        self.assertIn("async fn synthetic_pause_does_not_create_or_extend_provider_deadline()", module)
        self.assertNotIn("std::env::set_var", module)


if __name__ == "__main__":
    unittest.main()
