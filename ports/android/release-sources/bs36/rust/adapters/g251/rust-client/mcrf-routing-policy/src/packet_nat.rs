//! Packet reflection primitives for a worker-owned kernel DNS listener.
//! Changes only addresses/ports and their checksums: never TCP sequence/ACK,
//! flags, payload, or another user-space TCP stack. No route is granted here.
use std::net::IpAddr;
use crate::packet::{PacketInfo,PacketError};
#[derive(Debug,PartialEq,Eq)]pub enum Error {Packet(PacketError),Family,Transport,Authentication,Options,Ports}
fn word(bytes:&[u8],at:usize)->u16{u16::from_be_bytes([bytes[at],bytes[at+1]])}
fn octets_into(ip:IpAddr,out:&mut[u8]){match ip{IpAddr::V4(v)=>out.copy_from_slice(&v.octets()),IpAddr::V6(v)=>out.copy_from_slice(&v.octets())}}
fn delta(checksum:u16,before:&[u8],after:&[u8])->u16{
    let mut sum=(!checksum)as u32;
    for (old,new) in before.chunks_exact(2).zip(after.chunks_exact(2)){
        sum+=(!u16::from_be_bytes([old[0],old[1]]))as u32+u16::from_be_bytes([new[0],new[1]])as u32;
        sum=(sum&65535)+(sum>>16);
    }
    while sum>>16!=0{sum=(sum&65535)+(sum>>16)}!(sum as u16)
}
fn transport(bytes:&[u8])->Result<(usize,usize,usize),Error>{
    if bytes[0]>>4==4 {
        let header=(bytes[0]&15)as usize*4;let mut at=20;
        while at<header {
            match bytes[at]{0=>break,1=>{at+=1;continue},131|137=>return Err(Error::Options),_=>{}}
            if at+2>header{return Err(Error::Options)}let size=bytes[at+1]as usize;
            if size<2||at+size>header{return Err(Error::Options)}at+=size;
        }
        Ok((12,16,header))
    }else{
        let mut next=bytes[6];let mut at=40;
        while matches!(next,0|43|44|51|60){
            if next==51{return Err(Error::Authentication)}
            let size=if next==44{8}else{(bytes[at+1]as usize+1)*8};
            let old=next;next=bytes[at];at+=size;
            if old==44&&word(bytes,at-6)&0xfff8!=0{break}
        }
        Ok((8,24,at))
    }
}
pub fn rewrite(bytes:&[u8],source:IpAddr,destination:IpAddr,ports:Option<(u16,u16)>)->Result<Vec<u8>,Error>{
    let mut out=bytes.to_vec();rewrite_in_place(&mut out,source,destination,ports)?;Ok(out)
}
/// Changes a caller-owned packet without allocating or copying its payload.
/// All fallible checks finish before the first mutation.
pub fn rewrite_in_place(bytes:&mut[u8],source:IpAddr,destination:IpAddr,ports:Option<(u16,u16)>)->Result<(),Error>{
    let info=PacketInfo::read(bytes).map_err(Error::Packet)?;
    if info.source.is_ipv4()!=source.is_ipv4()||info.source.is_ipv4()!=destination.is_ipv4(){return Err(Error::Family)}
    if !matches!(info.protocol,6|17){return Err(Error::Transport)}
    let first=info.fragment.is_none_or(|v|v.offset_bytes==0);
    if first!=ports.is_some()||ports.is_some_and(|(a,b)|a==0||b==0){return Err(Error::Ports)}
    let (source_at,destination_at,at)=transport(bytes)?;
    let size=if source.is_ipv4(){4}else{16};let mut before=[0u8;36];let mut after=[0u8;36];
    before[..size].copy_from_slice(&bytes[source_at..source_at+size]);before[size..size*2].copy_from_slice(&bytes[destination_at..destination_at+size]);
    octets_into(source,&mut after[..size]);octets_into(destination,&mut after[size..size*2]);
    if let Some((source_port,destination_port))=ports {
        before[size*2..size*2+4].copy_from_slice(&bytes[at..at+4]);after[size*2..size*2+2].copy_from_slice(&source_port.to_be_bytes());after[size*2+2..size*2+4].copy_from_slice(&destination_port.to_be_bytes());
        let checksum_at=at+if info.protocol==6{16}else{6};let checksum=word(bytes,checksum_at);
        if info.protocol==17&&checksum==0&&size==16{return Err(Error::Transport)}
        if info.protocol!=17||checksum!=0 {
            let changed=delta(checksum,&before[..size*2+4],&after[..size*2+4]);let changed=if info.protocol==17&&changed==0{65535}else{changed};
            bytes[checksum_at..checksum_at+2].copy_from_slice(&changed.to_be_bytes());
        }
        bytes[at..at+2].copy_from_slice(&source_port.to_be_bytes());bytes[at+2..at+4].copy_from_slice(&destination_port.to_be_bytes());
    }
    bytes[source_at..source_at+size].copy_from_slice(&after[..size]);bytes[destination_at..destination_at+size].copy_from_slice(&after[size..size*2]);
    if size==4 {
        bytes[10]=0;bytes[11]=0;let header=(bytes[0]&15)as usize*4;
        let mut sum=0u32;for pair in bytes[..header].chunks_exact(2){sum+=u16::from_be_bytes([pair[0],pair[1]])as u32}
        while sum>>16!=0{sum=(sum&65535)+(sum>>16)}bytes[10..12].copy_from_slice(&(!(sum as u16)).to_be_bytes());
    }
    Ok(())
}
