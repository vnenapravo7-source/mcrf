//! Local traffic diagnostics. Byte counts describe TUN I/O, not peer delivery.
use std::{sync::atomic::{AtomicBool,AtomicU64,Ordering},time::{Duration,Instant}};
use mcrf_bs_routing_policy::tcp_lifetime::{Direction,Frame};

pub static UDP_WRITE_DROPS:AtomicU64=AtomicU64::new(0);
pub static TUN_QUEUE_DROPS:AtomicU64=AtomicU64::new(0);
pub static TUN_WRITE_DROPS:AtomicU64=AtomicU64::new(0);
static TURN_WAITS:AtomicU64=AtomicU64::new(0);
static TURN_WAIT_US:AtomicU64=AtomicU64::new(0);
static ENABLED:AtomicBool=AtomicBool::new(false);
static TCP_PAYLOAD:AtomicU64=AtomicU64::new(0);
static TCP_REPEAT:AtomicU64=AtomicU64::new(0);
static TCP_ACKED:AtomicU64=AtomicU64::new(0);
pub static CARRIER_REJECTS:AtomicU64=AtomicU64::new(0);
pub static WORKER_SET_CHANGES:AtomicU64=AtomicU64::new(0);

pub struct TcpCounters {
    sent_end:u32,
    acknowledged:u32,
}
impl TcpCounters {
    pub fn new(syn:Frame)->Option<Self>{
        let start=syn.sequence.wrapping_add(1);
        ENABLED.load(Ordering::Relaxed).then_some(Self{sent_end:start,acknowledged:start})
    }
    pub fn observe(&mut self,direction:Direction,frame:Frame){
        match direction {
            Direction::App if frame.payload>0&&frame.flags&2==0=>{
                TCP_PAYLOAD.fetch_add(u64::from(frame.payload),Ordering::Relaxed);
                let end=frame.sequence.wrapping_add(frame.payload);
                let repeated=(self.sent_end.wrapping_sub(frame.sequence)as i32).max(0)as u32;
                if repeated>0{TCP_REPEAT.fetch_add(u64::from(repeated.min(frame.payload)),Ordering::Relaxed);}
                if newer(end,self.sent_end){self.sent_end=end;}
            },
            Direction::Peer if frame.flags&16!=0=>{
                let ack=frame.acknowledgement;
                if newer(ack,self.acknowledged)&&!newer(ack,self.sent_end){
                    TCP_ACKED.fetch_add(u64::from(ack.wrapping_sub(self.acknowledged)),Ordering::Relaxed);
                    self.acknowledged=ack;
                }
            },
            _=>{},
        }
    }
}
fn newer(a:u32,b:u32)->bool{(a.wrapping_sub(b)as i32)>0}

pub struct TurnWait(Instant);
impl TurnWait {
    pub fn start()->Self {
        TURN_WAITS.fetch_add(1,Ordering::Relaxed);
        Self(Instant::now())
    }
}
impl Drop for TurnWait {
    fn drop(&mut self){
        TURN_WAIT_US.fetch_add(self.0.elapsed().as_micros().min(u64::MAX as u128) as u64,Ordering::Relaxed);
    }
}

pub struct Reporter {
    last:Instant,
    previous:[u64;12],
}
impl Reporter {
    pub fn from_env()->Option<Self>{
        let enabled=std::env::var("MCRF_BS_TRAFFIC_DIAGNOSTICS").as_deref()==Ok("1");
        ENABLED.store(enabled,Ordering::Relaxed);
        enabled.then(||Self{last:Instant::now(),previous:[0;12]})
    }
    pub fn tick(&mut self,active:i32,up:i64,down:i64){
        let elapsed=self.last.elapsed();
        if elapsed<Duration::from_secs(1){return}
        self.last=Instant::now();
        let current=[up.max(0) as u64,down.max(0) as u64,TURN_WAITS.load(Ordering::Relaxed),
            TURN_WAIT_US.load(Ordering::Relaxed),UDP_WRITE_DROPS.load(Ordering::Relaxed),
            TUN_QUEUE_DROPS.load(Ordering::Relaxed),TUN_WRITE_DROPS.load(Ordering::Relaxed),
            TCP_PAYLOAD.load(Ordering::Relaxed),TCP_REPEAT.load(Ordering::Relaxed),TCP_ACKED.load(Ordering::Relaxed),
            CARRIER_REJECTS.load(Ordering::Relaxed),WORKER_SET_CHANGES.load(Ordering::Relaxed)];
        let [tx,rx,waits,wait_us,udp_drops,tun_queue_drops,tun_write_drops,tcp_payload,tcp_repeat,tcp_acked,rejects,set_changes]=deltas(current,self.previous);
        self.previous=current;
        if tx<128*1024&&rx<128*1024&&waits==0&&udp_drops==0&&tun_queue_drops==0&&tun_write_drops==0&&rejects==0{return}
        crate::log_error!("MCRF_BS_TRAFFIC|{}|{}|{tx}|{rx}|{waits}|{wait_us}|{udp_drops}|{tun_queue_drops}|{tun_write_drops}|{tcp_payload}|{tcp_repeat}|{tcp_acked}|{rejects}|{set_changes}",
            elapsed.as_millis(),active.max(0));
    }
}
fn deltas<const N:usize>(current:[u64;N],previous:[u64;N])->[u64;N]{
    std::array::from_fn(|i|current[i].saturating_sub(previous[i]))
}

#[cfg(test)]
mod tests{
    use super::*;
    #[test]
    fn wait_counts_and_write_drops_are_separate_window_deltas(){
        assert_eq!(deltas([1000,2000,3,80,1,6,5],[100,1000,1,30,0,6,5]),[900,1000,2,50,1,0,0]);
    }
    #[test]
    fn tcp_sequence_comparison_handles_wrap_and_reordered_acks(){
        assert!(newer(5,u32::MAX-5));
        assert!(!newer(u32::MAX-5,5));
        assert!(!newer(5,5));
    }
    #[test]
    fn repeated_payload_and_peer_ack_are_counted_separately(){
        let before=[TCP_PAYLOAD.load(Ordering::Relaxed),TCP_REPEAT.load(Ordering::Relaxed),TCP_ACKED.load(Ordering::Relaxed)];
        let start=u32::MAX-3;
        let mut state=TcpCounters{sent_end:start,acknowledged:start};
        let payload=Frame{sequence:start,acknowledgement:0,flags:16,payload:8};
        state.observe(Direction::App,payload);
        state.observe(Direction::App,payload);
        state.observe(Direction::Peer,Frame{sequence:0,acknowledgement:4,flags:16,payload:0});
        state.observe(Direction::Peer,Frame{sequence:0,acknowledgement:start,flags:16,payload:0});
        let after=[TCP_PAYLOAD.load(Ordering::Relaxed),TCP_REPEAT.load(Ordering::Relaxed),TCP_ACKED.load(Ordering::Relaxed)];
        assert_eq!(deltas(after,before),[16,8,8]);
    }
}
