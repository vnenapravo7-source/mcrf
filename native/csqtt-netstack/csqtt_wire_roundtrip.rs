// Test-only harness: execute the exact pinned protocol, not a Go imitation.
#[allow(dead_code)]
mod wire { include!(concat!(env!("MCRF_CSQTT_PROTOCOL_ROOT"), "/shared/wire_protocol.rs")); }
use std::io::{Read,Write};
fn main(){
 let mut packet=Vec::new();std::io::stdin().read_to_end(&mut packet).unwrap();
 assert!(!packet.is_empty()&&packet.len()<=1400);
 let mut fragments=Vec::new();wire::fragment_packet(17,&packet,|f|fragments.push(f.to_vec()));
 assert!(fragments.iter().all(|f|f.len()<=1280));
 let mut receiver=wire::Reassembler::new();let mut complete=Vec::new();let now=std::time::Instant::now();
 // Tail first, duplicate tail while incomplete, then remaining fragments.
 let last=fragments.last().unwrap();receiver.process_fragment_from(7,last,now,|p|complete.push(p.to_vec()));
 if fragments.len()>1 {receiver.process_fragment_from(7,last,now,|p|complete.push(p.to_vec()));}
 for fragment in fragments.iter().rev().skip(1){receiver.process_fragment_from(7,fragment,now,|p|complete.push(p.to_vec()));}
 assert_eq!(complete.len(),1);assert_eq!(complete[0],packet);std::io::stdout().write_all(&complete[0]).unwrap();
}
