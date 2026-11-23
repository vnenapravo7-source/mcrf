use hmac::{Hmac, Mac};
use sha2::Sha256;
use std::{
    io,
    net::SocketAddr,
    sync::{Mutex, OnceLock, atomic::{AtomicU64, Ordering}},
};
use tokio::net::UdpSocket;

const DOMAIN: &[u8] = b"MCRF_CSQTT_PRIVATE_UDP_V1";
const KEY_ENV: &str = "MCRF_CSQTT_PRIVATE_UDP_KEY_V1";
const REQUIRED_ENV: &str = "MCRF_CSQTT_PRIVATE_UDP_REQUIRED";
// Leave room in every upstream recv buffer to reject a truncated oversized frame.
const MAX_PAYLOAD: usize = 1900;
const OVERHEAD: usize = 40;
const UP: u8 = 1;
const DOWN: u8 = 2;
type Tag = Hmac<Sha256>;

#[derive(Default)]
struct Replay {
    largest: u64,
    seen: u64,
}

impl Replay {
    fn accept(&mut self, sequence: u64) -> bool {
        if sequence == 0 {
            return false;
        }
        if sequence > self.largest {
            let distance = sequence - self.largest;
            self.seen = if distance < 64 { self.seen << distance } else { 0 } | 1;
            self.largest = sequence;
            return true;
        }
        let distance = self.largest - sequence;
        if distance >= 64 || self.seen & (1 << distance) != 0 {
            return false;
        }
        self.seen |= 1 << distance;
        true
    }
}

struct Auth {
    key: [u8; 32],
    output: AtomicU64,
    input: Mutex<Replay>,
}

static AUTH: OnceLock<Auth> = OnceLock::new();

pub fn configure() -> anyhow::Result<()> {
    let value = match (std::env::var_os(REQUIRED_ENV), std::env::var_os(KEY_ENV)) {
        (None, None) => return Ok(()), // Public standalone raw CLI.
        (Some(required), Some(key)) if required == "1" => key,
        _ => anyhow::bail!("CSQTT private UDP capability or key missing"),
    };
    let encoded = value.to_str().ok_or_else(|| anyhow::anyhow!("CSQTT private UDP key encoding"))?;
    let mut key = [0u8; 32];
    if hex::decode_to_slice(encoded, &mut key).is_err() {
        anyhow::bail!("CSQTT private UDP key encoding");
    }
    install(key)
}

pub(crate) fn install(key: [u8; 32]) -> anyhow::Result<()> {
    AUTH.set(Auth { key, output: AtomicU64::new(0), input: Mutex::new(Replay::default()) })
        .map_err(|_| anyhow::anyhow!("CSQTT private UDP already initialized"))
}

pub fn active() -> bool { AUTH.get().is_some() }

fn mac(key: &[u8; 32], direction: u8, body: &[u8]) -> Tag {
    let mut mac = <Tag as hmac::digest::KeyInit>::new_from_slice(key).expect("HMAC accepts a 32-byte key");
    mac.update(DOMAIN);
    mac.update(&[direction]);
    mac.update(body);
    mac
}

fn open(auth: &Auth, direction: u8, frame: &[u8]) -> Option<usize> {
    if frame.len() <= OVERHEAD || frame.len() > MAX_PAYLOAD + OVERHEAD {
        return None;
    }
    let (body, tag) = frame.split_at(frame.len() - 32);
    mac(&auth.key, direction, body).verify_slice(tag).ok()?;
    let sequence = u64::from_be_bytes(body[..8].try_into().ok()?);
    auth.input.lock().ok()?.accept(sequence).then_some(body.len() - 8)
}

pub fn accept(packet: &mut crate::packet::PacketBuf) -> bool {
    let Some(auth) = AUTH.get() else { return true };
    let Some(length) = open(auth, UP, packet.as_slice()) else { return false };
    packet.as_mut_slice().copy_within(8..8 + length, 0);
    packet.truncate(length).is_ok()
}

pub struct Frame {
    bytes: [u8; MAX_PAYLOAD + OVERHEAD],
    len: usize,
}

impl Frame {
    fn new(auth: &Auth, direction: u8, payload: &[u8]) -> io::Result<Self> {
        if payload.is_empty() || payload.len() > MAX_PAYLOAD {
            return Err(io::ErrorKind::InvalidInput.into());
        }
        let previous = auth.output.fetch_update(Ordering::Relaxed, Ordering::Relaxed, |n| n.checked_add(1))
            .map_err(|_| io::ErrorKind::InvalidData)?;
        let mut bytes = [0u8; MAX_PAYLOAD + OVERHEAD];
        bytes[..8].copy_from_slice(&(previous + 1).to_be_bytes());
        let split = 8 + payload.len();
        bytes[8..split].copy_from_slice(payload);
        let tag = mac(&auth.key, direction, &bytes[..split]).finalize().into_bytes();
        bytes[split..split+32].copy_from_slice(&tag);
        Ok(Self { bytes, len: split + 32 })
    }

    pub fn down(payload: &[u8]) -> io::Result<Self> {
        Self::new(AUTH.get().ok_or(io::ErrorKind::InvalidData)?, DOWN, payload)
    }

    pub fn as_slice(&self) -> &[u8] { &self.bytes[..self.len] }
}

pub fn try_send(socket: &UdpSocket, payload: &[u8], address: SocketAddr) -> io::Result<usize> {
    if !active() { return socket.try_send_to(payload, address); }
    let frame = Frame::down(payload)?;
    let sent = socket.try_send_to(frame.as_slice(), address)?;
    if sent != frame.len { return Err(io::ErrorKind::WriteZero.into()); }
    Ok(payload.len())
}

pub async fn send(socket: &UdpSocket, payload: &[u8], address: SocketAddr) -> io::Result<usize> {
    if !active() { return socket.send_to(payload, address).await; }
    let frame = Frame::down(payload)?;
    let sent = socket.send_to(frame.as_slice(), address).await?;
    if sent != frame.len { return Err(io::ErrorKind::WriteZero.into()); }
    Ok(payload.len())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn auth(key: u8) -> Auth {
        Auth { key: [key; 32], output: AtomicU64::new(0), input: Mutex::new(Replay::default()) }
    }

    #[test]
    fn interop_forgery_direction_and_replay() {
        let alice = auth(3);
        let bob = auth(3);
        let wrong_key = auth(4);
        let first = Frame::new(&alice, UP, b"first").unwrap();
        let second = Frame::new(&alice, UP, b"second").unwrap();
        assert_eq!(hex::encode(first.as_slice()),
            "00000000000000016669727374d6a17979c1fbf00c95826df3d614076bd17d52f4917b8619efe4bf6701e89dee");
        let mut forged = *b"000000000000000000000000000000000000000000000";
        forged[..8].copy_from_slice(&9999u64.to_be_bytes());
        assert_eq!(open(&bob, UP, &forged), None);
        assert_eq!(open(&wrong_key, UP, first.as_slice()), None);
        assert_eq!(open(&bob, DOWN, first.as_slice()), None);
        assert_eq!(open(&bob, UP, second.as_slice()), Some(6));
        assert_eq!(open(&bob, UP, first.as_slice()), Some(5));
        assert_eq!(open(&bob, UP, first.as_slice()), None);
        let down = Frame::new(&bob, DOWN, b"reply").unwrap();
        assert_eq!(hex::encode(down.as_slice()),
            "00000000000000017265706c791010efbd8abb16decbf23f62809b6913ae020d063e8795e246043a7467353d03");
        assert_eq!(open(&alice, DOWN, down.as_slice()), Some(5));
        assert_eq!(open(&alice, DOWN, down.as_slice()), None);
    }
}
