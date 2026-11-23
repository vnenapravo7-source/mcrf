//! Read-only IP metadata for the native TUN ingress. Does not terminate TCP,
//! change sequence numbers, resolve names or copy a packet's payload.
use std::net::{IpAddr,Ipv4Addr,Ipv6Addr};
use crate::{Flow,Observed};

#[derive(Clone,Copy,PartialEq,Eq,Debug)]
pub enum PacketError { Truncated, Invalid, UnsupportedExtension, ExtensionLimit, Transport }
#[derive(Clone,Copy,PartialEq,Eq)]
pub struct Fragment { pub id:u32,pub offset_bytes:u32,pub more:bool,pub protocol:u8 }
#[derive(Clone,Copy,PartialEq,Eq,Debug)]
pub struct TcpControl {pub sequence:u32,pub flags:u8}
#[derive(Clone,Copy)]
pub struct PacketInfo {
    pub source:IpAddr,pub destination:IpAddr,pub protocol:u8,
    pub ports:Option<(u16,u16)>,pub fragment:Option<Fragment>,
    pub tcp:Option<TcpControl>,
}
fn u16be(bytes:&[u8],offset:usize)->u16 {u16::from_be_bytes([bytes[offset],bytes[offset+1]])}
impl PacketInfo {
    pub fn read(bytes:&[u8])->Result<Self,PacketError> {
        match bytes.first().map(|v|v>>4) {
            Some(4)=>Self::ipv4(bytes),Some(6)=>Self::ipv6(bytes),_=>Err(PacketError::Invalid)
        }
    }
    fn ipv4(bytes:&[u8])->Result<Self,PacketError> {
        if bytes.len()<20{return Err(PacketError::Truncated)}
        let header=(bytes[0]&15) as usize*4;
        if header<20{return Err(PacketError::Invalid)}
        let total=u16be(bytes,2) as usize;
        if total<header||bytes[6]&0x80!=0{return Err(PacketError::Invalid)}
        if bytes.len()<header||bytes.len()<total{return Err(PacketError::Truncated)}
        if bytes.len()!=total{return Err(PacketError::Invalid)}
        let flags=u16be(bytes,6);let offset=(flags&0x1fff) as u32*8;let more=flags&0x2000!=0;
        if more&&(total-header)%8!=0{return Err(PacketError::Invalid)}
        if (offset!=0||more)&&flags&0x4000!=0{return Err(PacketError::Invalid)}
        let fragment=if offset!=0||more{Some(Fragment{id:u16be(bytes,4) as u32,offset_bytes:offset,more,protocol:bytes[9]})}else{None};
        if fragment.is_some()&&(total==header||offset+(total-header) as u32>65535-header as u32){return Err(PacketError::Invalid)}
        let source=IpAddr::V4(Ipv4Addr::new(bytes[12],bytes[13],bytes[14],bytes[15]));
        let destination=IpAddr::V4(Ipv4Addr::new(bytes[16],bytes[17],bytes[18],bytes[19]));
        Self::transport(source,destination,bytes[9],&bytes[header..],fragment)
    }
    fn ipv6(bytes:&[u8])->Result<Self,PacketError> {
        if bytes.len()<40{return Err(PacketError::Truncated)}
        let payload=u16be(bytes,4) as usize;
        // Jumbograms need a different bounded transport contract; do not read
        // alleged ports from an unvalidated zero-length payload.
        if payload==0{return Err(PacketError::UnsupportedExtension)}
        if bytes.len()<40+payload{return Err(PacketError::Truncated)}
        if bytes.len()!=40+payload{return Err(PacketError::Invalid)}
        let source=IpAddr::V6(Ipv6Addr::from(<[u8;16]>::try_from(&bytes[8..24]).unwrap()));
        let destination=IpAddr::V6(Ipv6Addr::from(<[u8;16]>::try_from(&bytes[24..40]).unwrap()));
        let mut protocol=bytes[6];let mut at=40;let mut extensions=0;let mut fragment=None;
        while matches!(protocol,0|43|44|51|60) {
            extensions+=1;if extensions>8||at-40>512{return Err(PacketError::ExtensionLimit)}
            if bytes.len()-at<8{return Err(PacketError::Truncated)}
            let next=bytes[at];
            if protocol==44 {
                if fragment.is_some()||bytes[at+1]!=0{return Err(PacketError::Invalid)}
                let bits=u16be(bytes,at+2);if bits&6!=0{return Err(PacketError::Invalid)}
                let offset=(bits&0xfff8) as u32;let more=bits&1!=0;
                let id=u32::from_be_bytes(bytes[at+4..at+8].try_into().unwrap());at+=8;
                if more&&(bytes.len()-at)%8!=0{return Err(PacketError::Invalid)}
                if offset+(bytes.len()-at) as u32>65535{return Err(PacketError::Invalid)}
                if offset!=0||more {
                    fragment=Some(Fragment{id,offset_bytes:offset,more,protocol:next});
                    if bytes.len()==at{return Err(PacketError::Invalid)}
                    // Later fragments do not contain the beginning of the
                    // fragmentable part. Never interpret their payload as ports
                    // or another extension header.
                    if offset!=0{return Ok(Self{source,destination,protocol:next,ports:None,fragment,tcp:None})}
                }else {
                    // An atomic fragment still counts as a seen fragment header.
                    // A second header is rejected below via a separate marker.
                    fragment=Some(Fragment{id,offset_bytes:0,more:false,protocol:next});
                }
                protocol=next;continue
            }
            let size=if protocol==51{(bytes[at+1] as usize+2)*4}else{(bytes[at+1] as usize+1)*8};
            if size<8||bytes.len()-at<size{return Err(PacketError::Truncated)}
            if protocol==0&&at!=40{return Err(PacketError::Invalid)}
            if protocol==43&&bytes[at+3]!=0{return Err(PacketError::UnsupportedExtension)}
            at+=size;protocol=next;
        }
        if at-40>512{return Err(PacketError::ExtensionLimit)}
        if matches!(protocol,50|59|135|139|140){return Err(PacketError::UnsupportedExtension)}
        let fragment=fragment.filter(|f|f.more||f.offset_bytes!=0);
        Self::transport(source,destination,protocol,&bytes[at..],fragment)
    }
    fn transport(source:IpAddr,destination:IpAddr,protocol:u8,body:&[u8],fragment:Option<Fragment>)->Result<Self,PacketError> {
        if fragment.is_some_and(|f|f.offset_bytes!=0){return Ok(Self{source,destination,protocol,ports:None,fragment,tcp:None})}
        let ports=match protocol {
            6=>{
                if body.len()<20{return Err(PacketError::Transport)}
                let header=(body[12]>>4) as usize*4;
                if header<20||body.len()<header{return Err(PacketError::Transport)}
                Some((u16be(body,0),u16be(body,2)))
            },
            17=>{
                if body.len()<8{return Err(PacketError::Transport)}
                let length=u16be(body,4) as usize;
                if length<8||length<body.len()||fragment.is_none()&&length!=body.len(){return Err(PacketError::Transport)}
                Some((u16be(body,0),u16be(body,2)))
            },
            1|58=>{if body.len()<8{return Err(PacketError::Transport)}None},
            _=>None,
        };
        let tcp=if protocol==6{Some(TcpControl{sequence:u32::from_be_bytes(body[4..8].try_into().unwrap()),flags:body[13]})}else{None};
        Ok(Self{source,destination,protocol,ports,fragment,tcp})
    }
    /// UDP payload from an already validated complete IP packet. IPv4 options
    /// and bounded IPv6 extensions must not be mistaken for a UDP header.
    pub fn udp_payload(bytes:&[u8])->Result<&[u8],PacketError>{
        let info=Self::read(bytes)?;if info.protocol!=17||info.fragment.is_some(){return Err(PacketError::Transport)}
        let at=if bytes[0]>>4==4{usize::from(bytes[0]&15)*4}else{
            let mut protocol=bytes[6];let mut at=40;
            while protocol!=17{let next=bytes[at];at+=if protocol==44{8}else if protocol==51{(usize::from(bytes[at+1])+2)*4}else{(usize::from(bytes[at+1])+1)*8};protocol=next;}at
        };
        Ok(&bytes[at+8..])
    }
    /// A complete validated TCP segment, including options and payload.
    pub fn tcp_segment(bytes:&[u8])->Result<&[u8],PacketError>{
        let info=Self::read(bytes)?;if info.protocol!=6||info.fragment.is_some(){return Err(PacketError::Transport)}
        let at=if bytes[0]>>4==4{usize::from(bytes[0]&15)*4}else{
            let mut protocol=bytes[6];let mut at=40;
            while protocol!=6{let next=bytes[at];at+=if protocol==44{8}else if protocol==51{(usize::from(bytes[at+1])+2)*4}else{(usize::from(bytes[at+1])+1)*8};protocol=next;}at
        };Ok(&bytes[at..])
    }
    /// Noninitial fragments need a separately authenticated first-fragment
    /// association; zero ports must never be substituted for unknown ports.
    /// The caller must replace pending observations with verified metadata.
    pub fn flow(&self)->Option<Flow<'static>> {
        if self.fragment.is_some_and(|f|f.offset_bytes!=0){return None}
        let (source_port,port)=self.ports.unwrap_or((0,0));
        Some(Flow{source:self.source,destination:self.destination,source_port,port,
            network:match self.protocol{6=>"tcp",17=>"udp",1=>"icmp",58=>"icmpv6",_=>"other"},
            domain:Observed::Pending,protocol:Observed::Pending,packages:None,underlay:Observed::Pending})
    }
}
