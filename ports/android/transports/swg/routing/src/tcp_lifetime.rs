//! Raw-flow retirement signals only. This is not a TCP endpoint: no ACKs,
//! retransmission, congestion control or payload copies are produced here.
use crate::packet::{PacketInfo,PacketError};
#[derive(Clone,Copy,Debug,PartialEq,Eq)]pub struct Frame{pub sequence:u32,pub acknowledgement:u32,pub flags:u8,pub payload:u32}
impl Frame{
 pub fn read(packet:&[u8])->Result<Self,PacketError>{let bytes=PacketInfo::tcp_segment(packet)?;Ok(Self{sequence:u32::from_be_bytes(bytes[4..8].try_into().unwrap()),acknowledgement:u32::from_be_bytes(bytes[8..12].try_into().unwrap()),flags:bytes[13],payload:(bytes.len()-usize::from(bytes[12]>>4)*4)as u32})}
 fn syn(self)->bool{self.flags&0x17==2}
}
#[derive(Clone,Copy,Debug,PartialEq,Eq)]pub enum Direction{App,Peer}
#[derive(Clone,Copy,Debug,PartialEq,Eq)]pub enum Signal{Continue,NewSyn,AppReset,PeerResetProbe,Closed}
pub struct Lifetime{initial:u32,established:bool,fin:[Option<u32>;2],ack:[Option<u32>;2]}
impl Lifetime{
 pub fn new(syn:Frame)->Result<Self,PacketError>{if !syn.syn(){return Err(PacketError::Transport)}Ok(Self{initial:syn.sequence,established:false,fin:[None,None],ack:[None,None]})}
 pub fn observe(&mut self,direction:Direction,frame:Frame)->Signal{
  let index=if direction==Direction::App{0}else{1};
  if index==0&&frame.syn()&&(self.established||frame.sequence!=self.initial){return Signal::NewSyn}
  if frame.flags&4!=0{return if index==0{Signal::AppReset}else{Signal::PeerResetProbe}}
  if index==0&&!frame.syn(){self.established=true}
  if frame.flags&16!=0{self.ack[index]=Some(frame.acknowledgement)}
  if frame.flags&1!=0{self.fin[index].get_or_insert(frame.sequence.wrapping_add(frame.payload).wrapping_add(1));}
  let acknowledged=|side:usize|self.fin[side].zip(self.ack[1-side]).is_some_and(|(end,ack)|ack.wrapping_sub(end)as i32>=0);
  if acknowledged(0)&&acknowledged(1){Signal::Closed}else{Signal::Continue}
 }
}
