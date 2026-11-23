//! Captured per-document CA roots. The socket binding policy is preserved;
//! only the explicitly selected DNS TLS trust store changes.
use std::sync::Arc;
use serde_json::Value;
use base64::Engine as _;
use crate::mcrf_bs_dns_transport::{SocketPolicy,Failure,Direction};
struct Policy{inner:Arc<dyn SocketPolicy>,roots:Arc<turn_rustls::RootCertStore>}
impl SocketPolicy for Policy{
 fn prepare(&self,p:u8,fd:i32,port:u16,peer:std::net::SocketAddr,direction:Direction)->Result<Box<dyn Send>,Failure>{self.inner.prepare(p,fd,port,peer,direction)}
 fn tls_roots(&self)->Option<Arc<turn_rustls::RootCertStore>>{Some(self.roots.clone())}
}
fn roots(value:&Value)->Result<Arc<turn_rustls::RootCertStore>,Failure>{
 let row=value.as_object().ok_or(Failure::Invalid)?;
 if row.len()!=2||!row.keys().all(|k|matches!(k.as_str(),"store"|"roots"))||!matches!(row.get("store").and_then(Value::as_str),Some("system"|"mozilla")){return Err(Failure::Invalid)}
 let rows=row.get("roots").and_then(Value::as_array).ok_or(Failure::Invalid)?;if rows.is_empty()||rows.len()>1024{return Err(Failure::Invalid)}
 let mut roots=turn_rustls::RootCertStore::empty();let mut total=0usize;let mut unique=std::collections::HashSet::new();
 for row in rows{let text=row.as_str().ok_or(Failure::Invalid)?;if text.len()>43692{return Err(Failure::Invalid)}let bytes=base64::engine::general_purpose::STANDARD.decode(text).map_err(|_|Failure::Invalid)?;
  if bytes.is_empty()||bytes.len()>32768||base64::engine::general_purpose::STANDARD.encode(&bytes)!=text||!unique.insert(bytes.clone()){return Err(Failure::Invalid)}total=total.checked_add(bytes.len()).filter(|v|*v<=2*1024*1024).ok_or(Failure::Invalid)?;
  roots.add(turn_rustls::pki_types::CertificateDer::from(bytes)).map_err(|_|Failure::Invalid)?;
 }Ok(Arc::new(roots))
}
pub fn wrap(value:Option<&Value>,policy:Arc<dyn SocketPolicy>)->Result<Arc<dyn SocketPolicy>,Failure>{match value{None=>Ok(policy),Some(value)=>Ok(Arc::new(Policy{inner:policy,roots:roots(value)?}))}}
#[cfg(test)]#[path="mcrf_bs_certificates_tests.rs"]mod tests;
