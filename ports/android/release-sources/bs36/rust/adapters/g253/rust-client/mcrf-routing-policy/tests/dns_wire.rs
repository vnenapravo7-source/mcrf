use mcrf_bs_routing_policy::dns_wire::{question,addresses,Error};
fn name(text:&str)->Vec<u8>{let mut bytes=Vec::new();for label in text.split('.'){bytes.push(label.len()as u8);bytes.extend_from_slice(label.as_bytes())}bytes.push(0);bytes}
fn query()->Vec<u8>{let mut bytes=vec![0x12,0x34,1,0,0,1,0,0,0,0,0,0];bytes.extend(name("www.owned.invalid"));bytes.extend([0,1,0,1]);bytes}
fn rr(owner:&str,kind:u16,ttl:u32,data:&[u8])->Vec<u8>{let mut bytes=name(owner);bytes.extend(kind.to_be_bytes());bytes.extend(1u16.to_be_bytes());bytes.extend(ttl.to_be_bytes());bytes.extend((data.len()as u16).to_be_bytes());bytes.extend(data);bytes}
fn response(answer:Vec<Vec<u8>>,additional:Vec<Vec<u8>>)->Vec<u8>{let mut bytes=query();bytes[2]=0x81;bytes[3]=0x80;bytes[6..8].copy_from_slice(&(answer.len()as u16).to_be_bytes());bytes[10..12].copy_from_slice(&(additional.len()as u16).to_be_bytes());for record in answer.into_iter().chain(additional){bytes.extend(record)}bytes}
#[test]fn cname_chain_uses_original_domain_and_minimum_ttl_excluding_unrelated_addresses(){
    let reply=response(vec![rr("www.owned.invalid",5,20,&name("edge.owned.invalid")),rr("www.owned.invalid",5,5,&name("edge.owned.invalid")),rr("edge.owned.invalid",1,60,&[192,0,2,42]),rr("other.invalid",1,600,&[192,0,2,99])],vec![rr("edge.owned.invalid",1,600,&[192,0,2,100])]);
    let rows=addresses(&query(),&reply).unwrap();assert_eq!(rows.len(),1);assert_eq!(rows[0].domain,"www.owned.invalid");assert_eq!(rows[0].ip,"192.0.2.42".parse::<std::net::IpAddr>().unwrap());assert_eq!(rows[0].ttl_ms,5000);
}
#[test]fn zero_ttl_is_preserved_for_explicit_uncacheable_handling(){
    let reply=response(vec![rr("www.owned.invalid",1,0,&[192,0,2,42])],vec![]);assert_eq!(addresses(&query(),&reply).unwrap()[0].ttl_ms,0);
}
#[test]fn cycles_conflicting_cnames_and_question_mismatch_never_authorize_observations(){
    for answer in [vec![rr("www.owned.invalid",5,60,&name("edge.invalid")),rr("edge.invalid",5,60,&name("www.owned.invalid"))],vec![rr("www.owned.invalid",5,60,&name("one.invalid")),rr("www.owned.invalid",5,60,&name("two.invalid"))]]{assert_eq!(addresses(&query(),&response(answer,vec![])),Err(Error::Chain))}
    let mut wrong=response(vec![],vec![]);wrong[0]^=1;assert_eq!(addresses(&query(),&wrong),Err(Error::Question));
}
#[test]fn error_rcodes_and_duplicate_opt_records_are_not_successful_answers(){
    let mut nxdomain=response(vec![rr("www.owned.invalid",1,60,&[192,0,2,42])],vec![]);nxdomain[3]|=3;assert!(addresses(&query(),&nxdomain).unwrap().is_empty());
    let opt=vec![0,0,41,4,208,1,0,0,0,0,0];
    let badvers=response(vec![rr("www.owned.invalid",1,60,&[192,0,2,42])],vec![opt.clone()]);assert!(addresses(&query(),&badvers).unwrap().is_empty());
    assert_eq!(addresses(&query(),&response(vec![],vec![opt.clone(),opt])),Err(Error::Record));
}
#[test]fn valid_compression_and_malformed_pointer_bounds_are_distinct(){
    let mut reply=response(vec![],vec![]);reply[6..8].copy_from_slice(&1u16.to_be_bytes());reply.extend([0xc0,12,0,1,0,1,0,0,0,60,0,4,192,0,2,42]);assert_eq!(addresses(&query(),&reply).unwrap().len(),1);
    for pointer in [[0xc0,12],[0xc0,17],[0xff,0xff]]{let mut malformed=vec![0x12,0x34,1,0,0,1,0,0,0,0,0,0];malformed.extend(pointer);malformed.extend([0,1,0,1]);assert!(question(&malformed).is_err())}
}
#[test]fn malformed_datagrams_and_mutated_records_are_bounded_without_panics(){
    let valid=response(vec![rr("www.owned.invalid",1,60,&[192,0,2,42])],vec![]);
    for at in 0..valid.len(){for byte in [0,1,63,64,127,192,255]{let mut mutated=valid.clone();mutated[at]=byte;assert!(std::panic::catch_unwind(||{let _=addresses(&query(),&mutated);}).is_ok());}}
    let mut seed=771u32;for size in 0..256{let mut bytes=Vec::new();for _ in 0..size{seed=seed.wrapping_mul(1664525).wrapping_add(1013904223);bytes.push((seed>>24)as u8)}assert!(std::panic::catch_unwind(||{let _=question(&bytes);let _=addresses(&query(),&bytes);}).is_ok())}
}
