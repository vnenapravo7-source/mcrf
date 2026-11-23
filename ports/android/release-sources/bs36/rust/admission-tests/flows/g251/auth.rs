#![allow(dead_code)]
use std::{sync::{Arc,atomic::{AtomicI64,AtomicUsize,Ordering}},time::{Duration,SystemTime,UNIX_EPOCH},collections::HashMap};
use anyhow::Result;
#[derive(Clone)]
pub struct TurnCredentials {
    pub username: Arc<str>,
    pub password: Arc<str>,
    pub server_addresses: Arc<[Arc<str>]>,
}
#[derive(Debug)]
pub struct CallUnavailable {
    pub code: i64,
    pub message: String,
}
#[derive(Clone,Copy,Debug)]
pub enum VkRateLimitType {
    FloodControl,
    RateLimit,
    CaptchaLimit,
}
#[derive(Debug)]
pub struct VkRateLimitError {
    pub limit_type: VkRateLimitType,
    pub code: i64,
    pub message: String,
    pub cooldown: Duration,
}
impl std::fmt::Display for CallUnavailable {fn fmt(&self,f:&mut std::fmt::Formatter<'_>)->std::fmt::Result{write!(f,"fixture unavailable")}}
impl std::error::Error for CallUnavailable {}
impl std::fmt::Display for VkRateLimitError {fn fmt(&self,f:&mut std::fmt::Formatter<'_>)->std::fmt::Result{write!(f,"fixture typed VK limit")}}
impl std::error::Error for VkRateLimitError {}
#[path = "mcrf_vk_backoff.rs"]
pub(crate) mod mcrf_vk_backoff;
#[derive(Clone,Default)] struct Profile;
#[derive(Default)] struct Jar;
struct Client;
struct VkCredentials {client_id:Arc<str>}
struct LegacyAttempt<'a>{client:&'a Client,captcha_client:&'a Client,profile:Profile,saved_profile:Option<&'a Profile>}
fn browser_client_for_profile(_:Arc<Jar>,_:&Profile)->Result<Client>{Ok(Client)}
fn captcha_client_for_profile(_:Arc<Jar>,_:&Profile)->Result<Client>{Ok(Client)}
async fn load_saved_profile()->Option<Profile>{None}
fn unix_seconds()->i64{SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs() as i64}
fn is_network_or_dns_error(_: &anyhow::Error)->bool{false}
async fn random_delay(_:u64,_:u64){tokio::task::yield_now().await;}
mod rand {pub fn random<T:Default>()->T{T::default()}}
const MAX_ACCOUNT_CREDENTIALS:usize=10;
struct Broker;
impl Broker {async fn get_credentials(&self,_:usize)->Result<TurnCredentials>{Err(anyhow::anyhow!("fixture Auto JS fallback"))}}
#[derive(Default)]
pub struct Script {
    pub before_second:tokio::sync::Notify,pub resume_second:tokio::sync::Notify,
    pub legacy:AtomicUsize,pub calls:std::sync::Mutex<HashMap<String,usize>>,
}
impl Script {
    pub fn count(&self,hash:&str)->usize{*self.calls.lock().unwrap().get(hash).unwrap_or(&0)}
    async fn operation(&self,hash:&str,step:usize)->Result<()> {
        if hash=="peer"&&step==2 {self.before_second.notify_one();self.resume_second.notified().await;}
        // Fake transport boundary, same production guard used by BOTH actual
        // auth post helpers and JS post_form. No HTTP implementation linked.
        mcrf_vk_backoff::provider_allowed()?;
        *self.calls.lock().unwrap().entry(hash.to_owned()).or_default()+=1;
        if hash=="flood" {return Err(mcrf_vk_backoff::limited(VkRateLimitError{limit_type:VkRateLimitType::FloodControl,code:9,message:String::new(),cooldown:Duration::from_secs(60)}));}
        if hash=="bad" {return Err(CallUnavailable{code:951,message:String::new()}.into());}
        Ok(())
    }
}
pub struct VkAuth {
    mode:Arc<str>,auto_js:Option<Broker>,profile:Profile,credentials:Vec<VkCredentials>,
    captcha_lockout_until:AtomicI64,rate_limit_lockout_until:AtomicI64,pub script:Arc<Script>,
}
impl VkAuth {
    pub fn fixture(script:Arc<Script>,automatic:bool)->Arc<Self> {
        Arc::new(Self{mode:Arc::from(if automatic{"auto_js"}else{"vkcalls"}),auto_js:Some(Broker),profile:Profile,
            credentials:vec![VkCredentials{client_id:Arc::from("fixture")}],captcha_lockout_until:AtomicI64::new(0),rate_limit_lockout_until:AtomicI64::new(0),script})
    }
    async fn vk_calls_path(&self,link:&str,_:usize)->Result<TurnCredentials>{
        for step in 1..=3 {self.script.operation(link,step).await?;}
        Ok(credentials())
    }
    async fn legacy_path(&self,_:&str,_:usize,_:&VkCredentials,_:LegacyAttempt<'_>)->Result<TurnCredentials>{
        self.script.legacy.fetch_add(1,Ordering::SeqCst);Err(anyhow::anyhow!("unexpected fixture legacy fallback"))
    }
    // EXACT transformed production auth entry, fallback and retry bodies:
    pub async fn get_credentials(&self, link: &str, stream_id: usize) -> Result<TurnCredentials> {
        mcrf_vk_backoff::fetch(self, link, stream_id).await
    }
async fn mcrf_fetch_credentials(&self, link: &str, stream_id: usize) -> Result<TurnCredentials> {
        if self.mode.as_ref() == "auto_js" {
            let credential_id = stream_id / 100;
            let auto_js_result = match self.auto_js.as_ref() {
                Some(broker) => broker.get_credentials(credential_id).await,
                None => Err(anyhow::anyhow!("Auto JS credential broker отсутствует")),
            };
            return match auto_js_result {
                Ok(credentials) => Ok(credentials),
                Err(auto_js_error) if auto_js_error.downcast_ref::<VkRateLimitError>().is_some() => Err(auto_js_error),
                Err(auto_js_error) => {
                    crate::log_error!(
                        "[VK JS] Креды аккаунта {credential_id}/{MAX_ACCOUNT_CREDENTIALS} недоступны: {auto_js_error:#} · переход на Авто"
                    );
                    match self.vk_calls_with_retries(link, stream_id).await {
                        Ok(credentials) => {
                            crate::log_error!("[VK JS] Переход на Авто #{credential_id} выполнен");
                            Ok(credentials)
                        }
                        Err(auto_error) if auto_error.downcast_ref::<VkRateLimitError>().is_some() => return Err(auto_error),
                        Err(auto_error) => {
                            crate::log_error!(
                                "[VK JS] Переход на Авто #{credential_id} не выполнен: {auto_error:#}"
                            );
                            Err(anyhow::anyhow!(
                                "Auto JS: {auto_js_error:#}; fallback Авто: {auto_error:#}"
                            ))
                        }
                    }
                }
            };
        }
        let now = unix_seconds();
        let rate_lockout = self.rate_limit_lockout_until.load(Ordering::Acquire);
        if now < rate_lockout {
            let remaining = (rate_lockout - now) as u64;
            return Err(mcrf_vk_backoff::limited(VkRateLimitError {
                limit_type: VkRateLimitType::RateLimit,
                code: 0,
                message: "активна глобальная пауза лимита запросов".to_string(),
                cooldown: Duration::from_secs(remaining.max(1)),
            }));
        }
        let captcha_lockout = self.captcha_lockout_until.load(Ordering::Acquire);
        if now < captcha_lockout {
            let remaining = (captcha_lockout - now) as u64;
            return Err(mcrf_vk_backoff::limited(VkRateLimitError {
                limit_type: VkRateLimitType::CaptchaLimit,
                code: 14,
                message: "активна глобальная пауза капчи".to_string(),
                cooldown: Duration::from_secs(remaining.max(1)),
            }));
        }
        if self.mode.as_ref() == "vkcalls" {
            match self.vk_calls_with_retries(link, stream_id).await {
                Ok(credentials) => return Ok(credentials),
                Err(error) if error.downcast_ref::<CallUnavailable>().is_some() => {
                    return Err(error);
                }
                Err(error) if error.downcast_ref::<VkRateLimitError>().is_some() => {
                    return Err(error);
                }
                Err(error) => {
                    if is_network_or_dns_error(&error) {
                        return Err(error);
                    }
                    crate::log_error!(
                        "[STREAM {stream_id}] [VK Auth] VK Calls path failed after 2 attempts ({error}), falling back to auto captcha"
                    );
                }
            }
        } else {
            crate::log_error!(
                "[STREAM {stream_id}] [VK Auth] Legacy mode selected, skipping VK Calls path"
            );
        }
        let cookie_jar = Arc::new(Jar::default());
        let mut last_error = None;
        let credential_count = self.credentials.len().max(1);
        for attempt in 0..4 {
            let Some(credential) = self.credentials.get(attempt % credential_count) else {
                break;
            };
            crate::log_error!(
                "[STREAM {stream_id}] [VK Auth] Trying credentials: client_id={} (attempt {}/4)",
                credential.client_id,
                attempt + 1
            );
            let profile = self.profile.clone();
            let client = browser_client_for_profile(cookie_jar.clone(), &profile)?;
            let captcha_client = captcha_client_for_profile(cookie_jar.clone(), &profile)?;
            let saved_profile = load_saved_profile().await;
            match self
                .legacy_path(
                    link,
                    stream_id,
                    credential,
                    LegacyAttempt {
                        client: &client,
                        captcha_client: &captcha_client,
                        profile,
                        saved_profile: saved_profile.as_ref(),
                    },
                )
                .await
            {
                Ok(value) => {
                    crate::log_error!(
                        "[STREAM {stream_id}] [VK Auth] Success with client_id={}",
                        credential.client_id
                    );
                    return Ok(value);
                }
                Err(error) if error.downcast_ref::<CallUnavailable>().is_some() => {
                    return Err(error);
                }
                Err(error) => {
                    crate::log_error!(
                        "[STREAM {stream_id}] [VK Auth] Failed with client_id={}: {error}",
                        credential.client_id
                    );
                    if let Some(limit) = error.downcast_ref::<VkRateLimitError>() {
                        self.rate_limit_lockout_until.store(
                            unix_seconds() + limit.cooldown.as_secs() as i64,
                            Ordering::Release,
                        );
                        return Err(error);
                    }
                    if error.to_string().contains("CAPTCHA_WAIT_REQUIRED") {
                        self.captcha_lockout_until
                            .store(unix_seconds() + 60, Ordering::Release);
                        return Err(mcrf_vk_backoff::limited(VkRateLimitError {
                            limit_type: VkRateLimitType::CaptchaLimit,
                            code: 14,
                            message: "captcha wait required".to_string(),
                            cooldown: Duration::from_secs(60),
                        }));
                    }
                    last_error = Some(error);
                }
            }
            if attempt % credential_count == credential_count - 1 && attempt < 3 {
                let wait = rand::random::<u64>() % 900 + 900;
                crate::log_error!(
                    "[STREAM {stream_id}] [VK Auth] Both VK credentials failed, retrying stable pair after {wait}ms..."
                );
                tokio::time::sleep(Duration::from_millis(wait)).await;
            }
        }
        Err(last_error.unwrap_or_else(|| anyhow::anyhow!("all VK credentials failed")))
    }
async fn vk_calls_with_retries(&self, link: &str, stream_id: usize) -> Result<TurnCredentials> {
        let mut last_error = None;
        for attempt in 1..=2 {
            match self.vk_calls_path(link, stream_id).await {
                Ok(credentials) => {
                    crate::log_error!(
                        "[STREAM {stream_id}] [VK Auth] Success via VK Calls path (attempt {attempt}/2)"
                    );
                    return Ok(credentials);
                }
                Err(error) if error.downcast_ref::<CallUnavailable>().is_some() => {
                    return Err(error);
                }
                Err(error) if error.downcast_ref::<VkRateLimitError>().is_some() => {
                    if let Some(limit) = error.downcast_ref::<VkRateLimitError>() {
                        self.rate_limit_lockout_until.store(
                            unix_seconds() + limit.cooldown.as_secs() as i64,
                            Ordering::Release,
                        );
                    }
                    return Err(error);
                }
                Err(error) => {
                    if is_network_or_dns_error(&error) {
                        return Err(error);
                    }
                    last_error = Some(error);
                    if attempt < 2 {
                        random_delay(300, 500).await;
                    }
                }
            }
        }
        Err(last_error.unwrap_or_else(|| anyhow::anyhow!("VK Calls path failed")))
    }
}
pub fn credentials()->TurnCredentials{TurnCredentials{username:Arc::from("fixture"),password:Arc::from("fixture"),server_addresses:vec![Arc::from("127.0.0.1:1")].into()}}
