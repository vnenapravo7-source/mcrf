"""Fail-closed auth overlay for fresh 2.5.1 and 2.5.2/3 adapters only.

Never edits pinned inputs. Host cooldown IPC and per-native auth admission
complement each other; neither rotates IP/account or discards saved hashes.
"""
import hashlib
import re
from pathlib import Path

HERE = Path(__file__).resolve().parent


def once(source, old, new):
    if source.count(old) != 1:
        raise ValueError("VK backoff source anchor changed")
    return source.replace(old, new, 1)


def transform(auth, worker, js):
    entry = "    pub async fn get_credentials(&self, link: &str, stream_id: usize) -> Result<TurnCredentials> {"
    auth = once(auth, entry, entry + "\n        mcrf_vk_backoff::fetch(self, link, stream_id).await\n    }\n\n"
                "    async fn mcrf_fetch_credentials(&self, link: &str, stream_id: usize) -> Result<TurnCredentials> {")
    auth = once(auth, "impl VkAuth {", '#[path = "mcrf_vk_backoff.rs"]\npub(crate) mod mcrf_vk_backoff;\n\nimpl VkAuth {')
    # A typed provider limit is terminal for this attempt, NOT a reason to try
    # another auth path/account. Original successful manual paths stay intact.
    auth = once(auth, "                Err(auto_js_error) => {", "                Err(auto_js_error) if auto_js_error.downcast_ref::<VkRateLimitError>().is_some() => Err(auto_js_error),\n                Err(auto_js_error) => {")
    auth = once(auth, "                        Err(auto_error) => {", "                        Err(auto_error) if auto_error.downcast_ref::<VkRateLimitError>().is_some() => return Err(auto_error),\n                        Err(auto_error) => {")
    worker = once(worker, """        let _guard = tokio::select! {
            _ = self.context.cancel.cancelled() => return None,
            _ = self.availability.cancel.cancelled() => return None,
            guard = self.context.credential_pacer.lock() => guard,
        };""", """        // Admission is inside auth.get_credentials: two concurrent NEW
        // requests, a short shared cooldown lock and a bounded queue wait.
        // Do not hold the historical one-slot pacer across auth/retry sleeps.""")
    old = """let delay = if error.downcast_ref::<VkRateLimitError>().is_some() || failures >= 5 {
                    self.failures.store(0, Ordering::Relaxed);
                    Duration::from_secs(30)
                } else {"""
    new = """let delay = if let Some(limit) = error.downcast_ref::<VkRateLimitError>() {
                    self.failures.store(0, Ordering::Relaxed);
                    limit.cooldown.max(Duration::from_secs(30))
                } else if failures >= 5 {
                    self.failures.store(0, Ordering::Relaxed);
                    Duration::from_secs(30)
                } else {"""
    worker = once(worker, old, new)
    auth=once(auth,"async fn post_empty(client: &Client, endpoint: &str, profile: &Profile) -> Result<Value> {",
              "async fn post_empty(client: &Client, endpoint: &str, profile: &Profile) -> Result<Value> {\n    mcrf_vk_backoff::provider_allowed()?;")
    auth=once(auth,"    let mut headers = browser_headers(profile)?;",
              "    mcrf_vk_backoff::provider_allowed()?;\n    let mut headers = browser_headers(profile)?;")
    # These exact response classifications must publish BEFORE an already-
    # admitted peer can resume at its NEXT internal request boundary.
    pattern=r"return Err\(VkRateLimitError \{(.*?)\n(\s*)\}\n\s*\.into\(\)\);"
    auth,count=re.subn(pattern,lambda m:"return Err(mcrf_vk_backoff::limited(VkRateLimitError {"+m.group(1)+"\n"+m.group(2)+"}));",auth,flags=re.S)
    if count<6:
        raise ValueError("VK typed response classification boundaries changed")
    # The account broker formerly flattened provider limits into anyhow text,
    # so the auth layer could not distinguish them from a retryable failure.
    js = once(js, "use crate::auth::{TurnCredentials, parse_turn_credentials};",
              "use crate::auth::{TurnCredentials, parse_turn_credentials, VkRateLimitError, VkRateLimitType};")
    js = once(js, "            Err(error) => last_error = Some(error),",
              "            Err(error) if error.downcast_ref::<VkRateLimitError>().is_some() => return Err(error),\n            Err(error) => last_error = Some(error),")
    js = once(js, "    let body = form(fields);", "    crate::auth::mcrf_vk_backoff::provider_allowed()?;\n    let body = form(fields);")
    js = once(js, "    if !status.is_success() {", "    if status.as_u16() == 429 {\n        return Err(mcrf_api_error(\"429\", \"HTTP request limit\"));\n    }\n    if !status.is_success() {")
    if js.count('bail!("API {code}: {message}");') != 2:
        raise ValueError("VK JS error boundary changed")
    js = js.replace('bail!("API {code}: {message}");', 'return Err(mcrf_api_error(&code, message));')
    js = once(js, "fn check_api_error(value: &Value) -> Result<()> {", '''fn mcrf_api_error(code: &str, message: &str) -> anyhow::Error {
    let kind = match code {
        "9" => Some((VkRateLimitType::FloodControl, 60)),
        "6" | "29" | "429" => Some((VkRateLimitType::RateLimit, 30)),
        _ => None,
    };
    if let Some((limit_type, seconds)) = kind {
        // Bootstrap may fail before worker auth starts. Publish the same fixed
        // typed status there too; never expose provider/token payloads.
        return crate::auth::mcrf_vk_backoff::limited(VkRateLimitError { limit_type, code: code.parse().unwrap_or(0),
            message: "VK отклонил запрос; повтор отложен".to_owned(),
            cooldown: Duration::from_secs(seconds) });
    }
    anyhow::anyhow!("API {code}: {message}")
}

fn check_api_error(value: &Value) -> Result<()> {''')
    return auth, worker, js


def apply(crate):
    paths = [crate / name for name in ("auth.rs", "worker.rs", "vk_js_calls.rs")]
    original = [p.read_text(encoding="utf-8") for p in paths]
    updated = transform(*original)  # Validate ALL anchors before any writes.
    for path, source in zip(paths, updated):
        path.write_text(source, encoding="utf-8", newline="\n")
    module = HERE / "mcrf_vk_backoff.rs"
    (crate / module.name).write_bytes(module.read_bytes())
    return {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in (Path(__file__), module)}
