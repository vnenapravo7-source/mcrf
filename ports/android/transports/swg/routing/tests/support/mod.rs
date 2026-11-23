pub fn checksum(data:&[u8])->u16{
    let mut sum=0u32;for part in data.chunks(2){sum+=u16::from_be_bytes([part[0],*part.get(1).unwrap_or(&0)])as u32}
    while sum>>16!=0{sum=(sum&65535)+(sum>>16)}!(sum as u16)
}
pub fn pseudo(packet:&[u8],at:usize)->Vec<u8>{
    let mut bytes=Vec::new();let size=packet.len()-at;
    if packet[0]>>4==4{bytes.extend_from_slice(&packet[12..20]);bytes.extend_from_slice(&[0,packet[9]]);bytes.extend_from_slice(&(size as u16).to_be_bytes())}
    else{bytes.extend_from_slice(&packet[8..40]);bytes.extend_from_slice(&(size as u32).to_be_bytes());bytes.extend_from_slice(&[0,0,0,packet[6]])}
    bytes.extend_from_slice(&packet[at..]);bytes
}
pub fn packet(v6:bool,tcp:bool)->Vec<u8>{
    let at=if v6{40}else{20};let header=if tcp{20}else{8};let size=at+header+7;let proto=if tcp{6}else{17};let mut p=vec![0;size];
    if v6{
        p[0]=0x60;p[4..6].copy_from_slice(&((size-40)as u16).to_be_bytes());p[6]=proto;p[7]=64;
        p[8..24].copy_from_slice(&"fd00::2".parse::<std::net::Ipv6Addr>().unwrap().octets());p[24..40].copy_from_slice(&"fd00::53".parse::<std::net::Ipv6Addr>().unwrap().octets());
    }else{p[0]=0x45;p[2..4].copy_from_slice(&(size as u16).to_be_bytes());p[8]=64;p[9]=proto;p[12..16].copy_from_slice(&[10,89,0,2]);p[16..20].copy_from_slice(&[198,19,255,53]);let c=checksum(&p[..20]);p[10..12].copy_from_slice(&c.to_be_bytes())}
    p[at..at+2].copy_from_slice(&41234u16.to_be_bytes());p[at+2..at+4].copy_from_slice(&53u16.to_be_bytes());
    if tcp{p[at+4..at+12].copy_from_slice(&[1,2,3,4,5,6,7,8]);p[at+12]=0x50;p[at+13]=0x18;p[at+14..at+16].copy_from_slice(&32768u16.to_be_bytes())}
    else{p[at+4..at+6].copy_from_slice(&((size-at)as u16).to_be_bytes())}
    p[at+header..].copy_from_slice(b"payload");let c=checksum(&pseudo(&p,at));let c_at=at+if tcp{16}else{6};p[c_at..c_at+2].copy_from_slice(&c.to_be_bytes());p
}

