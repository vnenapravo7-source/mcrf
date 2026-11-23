use std::ops::Range;

/// Split a single TLS Handshake record so the SNI hostname is spread across
/// small TLS records. Returns None for non-ClientHello/malformed/no-SNI input.
pub fn fragment_tls_sni_record(record: &[u8], sni_chunk_size: usize) -> Option<Vec<Vec<u8>>> {
    if sni_chunk_size == 0 || record.len() < 5 || record[0] != 0x16 {
        return None;
    }

    let record_len = u16::from_be_bytes([record[3], record[4]]) as usize;
    if record.len() != record_len + 5 {
        return None;
    }

    let payload = &record[5..];
    let sni = find_sni_hostname(payload)?;
    let version = [record[1], record[2]];

    let mut chunks = Vec::new();
    push_record(&mut chunks, version, &payload[..sni.start]);
    for piece in payload[sni.clone()].chunks(sni_chunk_size) {
        push_record(&mut chunks, version, piece);
    }
    push_record(&mut chunks, version, &payload[sni.end..]);

    (chunks.len() > 1).then_some(chunks)
}

fn push_record(out: &mut Vec<Vec<u8>>, version: [u8; 2], payload: &[u8]) {
    if payload.is_empty() {
        return;
    }
    if payload.len() > u16::MAX as usize {
        return;
    }
    let mut record = Vec::with_capacity(5 + payload.len());
    record.push(0x16);
    record.extend_from_slice(&version);
    record.extend_from_slice(&(payload.len() as u16).to_be_bytes());
    record.extend_from_slice(payload);
    out.push(record);
}

fn find_sni_hostname(payload: &[u8]) -> Option<Range<usize>> {
    // Handshake header: type(1) + length(3)
    if payload.len() < 4 || payload[0] != 0x01 {
        return None;
    }
    let handshake_len = ((payload[1] as usize) << 16)
        | ((payload[2] as usize) << 8)
        | payload[3] as usize;
    if handshake_len + 4 > payload.len() {
        return None;
    }

    let mut p = 4usize;
    take(payload, &mut p, 2 + 32)?; // legacy_version + random

    let session_len = *payload.get(p)? as usize;
    p += 1;
    take(payload, &mut p, session_len)?;

    let cipher_len = read_u16(payload, &mut p)? as usize;
    take(payload, &mut p, cipher_len)?;

    let compression_len = *payload.get(p)? as usize;
    p += 1;
    take(payload, &mut p, compression_len)?;

    let extensions_len = read_u16(payload, &mut p)? as usize;
    let extensions_end = p.checked_add(extensions_len)?;
    if extensions_end > payload.len() {
        return None;
    }

    while p + 4 <= extensions_end {
        let ext_type = read_u16(payload, &mut p)?;
        let ext_len = read_u16(payload, &mut p)? as usize;
        let ext_end = p.checked_add(ext_len)?;
        if ext_end > extensions_end {
            return None;
        }

        if ext_type == 0x0000 {
            let mut q = p;
            let list_len = read_u16(payload, &mut q)? as usize;
            let list_end = q.checked_add(list_len)?;
            if list_end > ext_end {
                return None;
            }
            while q + 3 <= list_end {
                let name_type = *payload.get(q)?;
                q += 1;
                let name_len = read_u16(payload, &mut q)? as usize;
                let name_end = q.checked_add(name_len)?;
                if name_end > list_end {
                    return None;
                }
                if name_type == 0 && name_len > 0 {
                    return Some(q..name_end);
                }
                q = name_end;
            }
        }
        p = ext_end;
    }

    None
}

fn read_u16(data: &[u8], p: &mut usize) -> Option<u16> {
    let bytes = data.get(*p..(*p + 2))?;
    *p += 2;
    Some(u16::from_be_bytes([bytes[0], bytes[1]]))
}

fn take<'a>(data: &'a [u8], p: &mut usize, len: usize) -> Option<&'a [u8]> {
    let end = p.checked_add(len)?;
    let slice = data.get(*p..end)?;
    *p = end;
    Some(slice)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_non_tls() {
        assert!(fragment_tls_sni_record(b"GET /", 2).is_none());
    }

    #[test]
    fn fragments_sni_without_changing_handshake_bytes() {
        for preset in [crate::Preset::Current, crate::Preset::Gentle, crate::Preset::Extended] {
        let record = make_client_hello("www.youtube.com");
        let chunks = fragment_tls_sni_record(&record, preset.tls_chunk()).expect("SNI should be found");
        assert!(chunks.len() > 2);
        assert!(chunks.iter().all(|chunk| chunk[0] == 0x16));

        let rebuilt_payload: Vec<u8> = chunks
            .iter()
            .flat_map(|chunk| chunk[5..].iter().copied())
            .collect();
        assert_eq!(rebuilt_payload, record[5..]);
        }
    }

    fn make_client_hello(host: &str) -> Vec<u8> {
        let host = host.as_bytes();

        let mut sni = Vec::new();
        let server_name_list_len = 1 + 2 + host.len();
        sni.extend_from_slice(&(server_name_list_len as u16).to_be_bytes());
        sni.push(0);
        sni.extend_from_slice(&(host.len() as u16).to_be_bytes());
        sni.extend_from_slice(host);

        let mut extensions = Vec::new();
        extensions.extend_from_slice(&0u16.to_be_bytes());
        extensions.extend_from_slice(&(sni.len() as u16).to_be_bytes());
        extensions.extend_from_slice(&sni);

        let mut body = Vec::new();
        body.extend_from_slice(&[0x03, 0x03]);
        body.extend_from_slice(&[0u8; 32]);
        body.push(0);
        body.extend_from_slice(&2u16.to_be_bytes());
        body.extend_from_slice(&[0x13, 0x01]);
        body.push(1);
        body.push(0);
        body.extend_from_slice(&(extensions.len() as u16).to_be_bytes());
        body.extend_from_slice(&extensions);

        let mut handshake = Vec::new();
        handshake.push(0x01);
        let len = body.len();
        handshake.extend_from_slice(&[
            ((len >> 16) & 0xff) as u8,
            ((len >> 8) & 0xff) as u8,
            (len & 0xff) as u8,
        ]);
        handshake.extend_from_slice(&body);

        let mut record = Vec::new();
        record.extend_from_slice(&[0x16, 0x03, 0x01]);
        record.extend_from_slice(&(handshake.len() as u16).to_be_bytes());
        record.extend_from_slice(&handshake);
        record
    }
}
