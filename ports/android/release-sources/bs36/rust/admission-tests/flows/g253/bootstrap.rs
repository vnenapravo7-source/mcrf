use std::{future::Future,time::Duration};
use anyhow::{Result,bail};
use serde_json::Value;
use super::auth::{VkRateLimitError,VkRateLimitType};
// EXACT transformed JS bootstrap retry/API classification, with a fake closure.
async fn retry<F, Fut>(attempts: usize, mut request: F) -> Result<Value>
where
    F: FnMut() -> Fut,
    Fut: Future<Output = Result<Value>>,
{
    let mut last_error = None;
    for attempt in 0..attempts {
        match request().await {
            Ok(value) => return Ok(value),
            Err(error) if error.downcast_ref::<VkRateLimitError>().is_some() => return Err(error),
            Err(error) => last_error = Some(error),
        }
        if attempt + 1 < attempts {
            tokio::time::sleep(Duration::from_millis(250 * (attempt as u64 + 1))).await;
        }
    }
    Err(last_error.unwrap_or_else(|| anyhow::anyhow!("запрос не выполнен")))
}
fn mcrf_api_error(code: &str, message: &str) -> anyhow::Error {
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
fn check_api_error(value: &Value) -> Result<()> {
    if let Some(error) = value.get("error")
        && !error.is_null()
        && error != false
    {
        let code = error
            .get("error_code")
            .or_else(|| error.get("code"))
            .and_then(json_scalar_string)
            .unwrap_or_else(|| "unknown".to_owned());
        let message = error
            .get("error_msg")
            .or_else(|| error.get("message"))
            .and_then(Value::as_str)
            .unwrap_or("API error");
        return Err(mcrf_api_error(&code, message));
    }
    if let Some(code) = value.get("error_code").and_then(json_scalar_string)
        && code != "0"
    {
        let message = value
            .get("error_msg")
            .and_then(Value::as_str)
            .unwrap_or("API error");
        return Err(mcrf_api_error(&code, message));
    }
    Ok(())
}
fn json_scalar_string(value: &Value) -> Option<String> {
    value
        .as_str()
        .map(str::to_owned)
        .or_else(|| value.as_i64().map(|value| value.to_string()))
        .or_else(|| value.as_u64().map(|value| value.to_string()))
}
pub async fn fixture(calls:&std::sync::atomic::AtomicUsize)->Result<Value>{
    retry(3,||async {
        super::auth::mcrf_vk_backoff::provider_allowed()?;
        calls.fetch_add(1,std::sync::atomic::Ordering::SeqCst);
        let value=serde_json::json!({"error":{"error_code":9,"error_msg":"fixture"}});
        check_api_error(&value)?;
        Ok(value)
    }).await
}
