//! Experimental TURN/TCP setup splitting. No byte changes, fake packets, low TTL,
//! or pacing of tunnel payload. TCP write boundaries are not a wire guarantee.
use crate::{Diagnostics, StrategyEvent};
use anyhow::{Context, Result};
use std::{io, sync::Arc, time::Duration};
use tokio::{
    io::{copy, AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt},
    net::TcpStream,
    time::{sleep, timeout},
};

const HEADER_LEN: usize = 20;
const SETUP_TIMEOUT: Duration = Duration::from_secs(10);
const MAX_ALLOCATES: usize = 2;

fn allocate_prefix(bytes: &[u8]) -> bool {
    bytes.len() >= 5 && bytes[..2] == [0, 3] && bytes[3] & 3 == 0 && bytes[4] == 0x21
}

fn allocate_header(bytes: &[u8]) -> bool {
    bytes.len() == HEADER_LEN && allocate_prefix(bytes) && bytes[4..8] == [0x21, 0x12, 0xa4, 0x42]
}

/// Read no further than this prefix; EOF preserves every already received byte.
async fn extend_prefix<R: AsyncRead + Unpin>(
    reader: &mut R,
    bytes: &mut Vec<u8>,
    len: usize,
) -> io::Result<()> {
    while bytes.len() < len {
        let mut buf = [0u8; HEADER_LEN];
        let n = reader.read(&mut buf[..len - bytes.len()]).await?;
        if n == 0 {
            break;
        }
        bytes.extend_from_slice(&buf[..n]);
    }
    Ok(())
}

async fn split_header<W: AsyncWrite + Unpin>(writer: &mut W, header: &[u8], preset: crate::Preset) -> io::Result<()> {
    // Separate message type and magic cookie. All writes contain authentic bytes.
    // NODELAY is set on the remote socket by the SOCKS handler.
    let mut start = 0;
    for &end in preset.turn_parts() {
        writer.write_all(&header[start..end]).await?;
        writer.flush().await?;
        if end != HEADER_LEN {
            sleep(Duration::from_millis(preset.turn_delay_ms())).await;
        }
        start = end;
    }
    Ok(())
}

async fn outbound<R: AsyncRead + Unpin, W: AsyncWrite + Unpin>(
    reader: &mut R,
    writer: &mut W,
    mut header: Vec<u8>,
    diagnostics: &Diagnostics,
    preset: crate::Preset,
) -> Result<()> {
    let mut split_any = false;
    // Initial unauthenticated Allocate and the authenticated retry after 401.
    // After two requests (or any different message) switch permanently to copy.
    for index in 0..MAX_ALLOCATES {
        if !allocate_prefix(&header) {
            break;
        }
        timeout(
            SETUP_TIMEOUT,
            extend_prefix(reader, &mut header, HEADER_LEN),
        )
        .await
        .context("Gooby TURN header timed out")??;
        if !allocate_header(&header) {
            break;
        }
        let body_len = u16::from_be_bytes([header[2], header[3]]) as u64;
        split_header(writer, &header, preset).await?;
        let copied = timeout(
            SETUP_TIMEOUT,
            copy(&mut (&mut *reader).take(body_len), writer),
        )
        .await
        .context("Gooby TURN body timed out")??;
        if copied != body_len {
            return Err(io::Error::from(io::ErrorKind::UnexpectedEof).into());
        }
        diagnostics.record(StrategyEvent::TurnAllocateSplit);
        split_any = true;
        header.clear();
        if index + 1 < MAX_ALLOCATES {
            // Inbound runs concurrently, so the 401 challenge reaches the client
            // before we wait for its retry. No periodic timer/idle polling.
            extend_prefix(reader, &mut header, 2).await?;
            if header == [0, 3] {
                timeout(SETUP_TIMEOUT, extend_prefix(reader, &mut header, 5))
                    .await
                    .context("Gooby TURN prefix timed out")??;
            }
        }
    }
    if !split_any {
        diagnostics.record(StrategyEvent::Passthrough);
    }
    writer.write_all(&header).await?;
    copy(reader, writer).await?;
    writer.shutdown().await?;
    Ok(())
}

pub(crate) async fn tunnel<C:AsyncRead+AsyncWrite+Unpin>(
    client: C,
    mut remote: TcpStream,
    first: Vec<u8>,
    diagnostics: Arc<Diagnostics>,
    preset: crate::Preset,
) -> Result<()> {
    let (mut cr, mut cw) = tokio::io::split(client);
    let (mut rr, mut rw) = remote.split();
    let inbound = async {
        copy(&mut rr, &mut cw).await?;
        cw.shutdown().await?;
        Ok::<_, anyhow::Error>(())
    };
    tokio::try_join!(outbound(&mut cr, &mut rw, first, &diagnostics, preset), inbound)?;
    Ok(())
}

#[cfg(test)]
mod tests;
