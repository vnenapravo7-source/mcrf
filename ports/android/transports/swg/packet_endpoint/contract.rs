//! Typed packet FD contract; never an alias or fallback for real TUN-FD v1.
pub const CAPABILITY: &str = "org.mcrf.engines.csqtt_packet_endpoint_fd";
pub const MARKER: &str = "MCRF_PACKET_ENDPOINT_FD_V1";
pub const INPUT_KIND: &str = "unix-seqpacket-ip-v1";
pub const MAX_PACKET: usize = 1984; // Pinned native PacketPool2048 - headroom64.

#[derive(Clone,Copy,Debug,PartialEq,Eq)]
pub enum Rejected { InputKind, EndpointIdentity, Generation, ParentIdentity, PacketLimit }

/// Immutable after validation. The control owner must bind this identity to the
/// actual FD/inode and child generation; a unique string alone is not ownership.
#[derive(Clone)]
pub struct Contract {
    uid:u32,
    parent_pid:i32,
    max_packet:usize,
    generation:u64,
    endpoint_id:String,
}
impl Contract {
    pub fn new(kind:&str,uid:u32,parent_pid:i32,max_packet:usize,generation:u64,endpoint_id:&str)->Result<Self,Rejected> {
        if kind!=INPUT_KIND {return Err(Rejected::InputKind)}
        if endpoint_id.len()!=32 || !endpoint_id.bytes().all(|b|b.is_ascii_digit()||(b'a'..=b'f').contains(&b)) {
            return Err(Rejected::EndpointIdentity)
        }
        if generation==0 || generation>i64::MAX as u64 {return Err(Rejected::Generation)}
        if parent_pid<=0 {return Err(Rejected::ParentIdentity)}
        if !(20..=MAX_PACKET).contains(&max_packet) {return Err(Rejected::PacketLimit)}
        Ok(Self{uid,parent_pid,max_packet,generation,endpoint_id:endpoint_id.to_owned()})
    }
    pub fn uid(&self)->u32 {self.uid}
    pub fn parent_pid(&self)->i32 {self.parent_pid}
    pub fn max_packet(&self)->usize {self.max_packet}
    pub fn generation(&self)->u64 {self.generation}
    pub fn endpoint_id(&self)->&str {&self.endpoint_id}
}

#[cfg(test)]
mod tests {
    use super::*;
    const ID:&str="0123456789abcdef0123456789abcdef";
    fn valid()->Contract {Contract::new(INPUT_KIND,10001,123,1500,1,ID).ok().unwrap()}
    #[test] fn explicit_packet_contract_preserves_typed_identity() {
        let c=valid();assert_eq!(c.endpoint_id(),ID);assert_eq!(c.generation(),1);
        assert_eq!((c.uid(),c.parent_pid(),c.max_packet()),(10001,123,1500));
    }
    #[test] fn tun_and_unknown_input_kinds_never_alias_packet_endpoint() {
        for kind in ["","tun","tun-fd-v1","unix-stream-ip-v1","UNIX-SEQPACKET-IP-V1"] {
            assert!(matches!(Contract::new(kind,10001,123,1500,1,ID),Err(Rejected::InputKind)));
        }
    }
    #[test] fn endpoint_id_is_exactly_32_lower_hex() {
        for id in ["","0123","0123456789ABCDEF0123456789ABCDEF","g123456789abcdef0123456789abcdef"] {
            assert!(matches!(Contract::new(INPUT_KIND,10001,123,1500,1,id),Err(Rejected::EndpointIdentity)));
        }
    }
    #[test] fn generation_matches_existing_positive_signed_63bit_owner() {
        for generation in [0,i64::MAX as u64+1,u64::MAX] {
            assert!(matches!(Contract::new(INPUT_KIND,10001,123,1500,generation,ID),Err(Rejected::Generation)));
        }
        assert!(Contract::new(INPUT_KIND,10001,123,1500,i64::MAX as u64,ID).is_ok());
    }
    #[test] fn parent_identity_is_required() {
        for pid in [0,-1,i32::MIN] {
            assert!(matches!(Contract::new(INPUT_KIND,10001,pid,1500,1,ID),Err(Rejected::ParentIdentity)));
        }
    }
    #[test] fn packet_limit_cannot_change_original_native_capacity() {
        for limit in [0,19,MAX_PACKET+1,usize::MAX] {
            assert!(matches!(Contract::new(INPUT_KIND,10001,123,limit,1,ID),Err(Rejected::PacketLimit)));
        }
        assert!(Contract::new(INPUT_KIND,10001,123,MAX_PACKET,1,ID).is_ok());
    }
}
