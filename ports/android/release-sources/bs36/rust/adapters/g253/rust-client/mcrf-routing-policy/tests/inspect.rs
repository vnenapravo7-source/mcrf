use mcrf_bs_routing_policy::inspect::{tcp,Inspection};
fn hello(ech:bool)->Vec<u8>{
 let name=b"Owned.INVALID";let mut sni=vec![0];sni.extend((name.len()as u16).to_be_bytes());sni.extend(name);
 let mut list=(sni.len()as u16).to_be_bytes().to_vec();list.extend(sni);
 let mut ext=vec![0,0];ext.extend((list.len()as u16).to_be_bytes());ext.extend(list);if ech{ext.extend([0xfe,0x0d,0,1,0]);}
 let mut body=vec![3,3];body.extend([0;32]);body.extend([0,0,2,0x13,1,1,0]);body.extend((ext.len()as u16).to_be_bytes());body.extend(ext);
 let mut out=vec![1,0,(body.len()>>8)as u8,body.len()as u8];out.extend(body);out
}
fn record(bytes:&[u8])->Vec<u8>{let mut out=vec![22,3,3];out.extend((bytes.len()as u16).to_be_bytes());out.extend(bytes);out}
#[test]fn segmented_tls_handshake_stays_pending_until_complete_and_recovers_sni(){
 let hello=hello(false);let mut bytes=record(&hello[..17]);bytes.extend(record(&hello[17..]));
 for end in 0..bytes.len(){assert_eq!(tcp(&bytes[..end],false),Inspection::Pending)}
 assert_eq!(tcp(&bytes,false),Inspection::Known{protocol:"tls",domain:Some("owned.invalid".into())});
 assert_eq!(tcp(&bytes[..bytes.len()-1],true),Inspection::Unknown);
}
#[test]fn ech_outer_name_is_not_inner_routing_evidence_and_invalid_lengths_do_not_authorize(){
 assert_eq!(tcp(&record(&hello(true)),false),Inspection::Known{protocol:"tls",domain:None});
 let mut broken=hello(false);broken[3]-=1;assert_eq!(tcp(&record(&broken),false),Inspection::Unknown);
 let mut broken=hello(false);let end=broken.len();broken[end-1]=b'/';assert_eq!(tcp(&record(&broken),false),Inspection::Unknown);
}
#[test]fn http_complete_headers_support_case_ports_connect_and_reject_ambiguous_hosts(){
 for bytes in [b"GET / HTTP/1.1\r\nHost: Owned.INVALID:443\r\n\r\n".as_slice(),b"CONNECT Owned.INVALID:443 HTTP/1.1\r\n\r\n",b"GET http://Owned.INVALID/ HTTP/1.1\r\nHost: owned.invalid\r\n\r\n"]{
  assert_eq!(tcp(bytes,false),Inspection::Known{protocol:"http",domain:Some("owned.invalid".into())});
  assert_eq!(tcp(&bytes[..bytes.len()-1],false),Inspection::Pending);
 }
 for bytes in [b"GET / HTTP/1.1\r\nHost: one.invalid\r\nHost: two.invalid\r\n\r\n".as_slice(),b"GET http://one.invalid/ HTTP/1.1\r\nHost: two.invalid\r\n\r\n",b"GET / HTTP/1.1\r\n folded: bad\r\n\r\n"]{assert_eq!(tcp(bytes,false),Inspection::Unknown)}
}
#[test]fn bounds_eof_and_mutated_streams_never_panic(){
 assert_eq!(tcp(b"SSH-2.0-owned",false),Inspection::Pending);assert_eq!(tcp(b"",true),Inspection::Unknown);assert_eq!(tcp(&vec![22;131073],false),Inspection::Unknown);
 let seed=record(&hello(false));for at in 0..seed.len(){for byte in [0,0xff,22]{let mut changed=seed.clone();changed[at]=byte;let _=tcp(&changed,false);let _=tcp(&changed,true);}}
}

#[test]fn dns_tcp_fragmented_query_and_pipeline_do_not_authorize_question_domain(){
 let query=mcrf_bs_routing_policy::dns_wire::query(0x1603,"owned.invalid",1).unwrap();
 let mut frame=(query.len()as u16).to_be_bytes().to_vec();frame.extend(&query);
 for end in 0..frame.len(){assert_eq!(tcp(&frame[..end],false),Inspection::Pending,"prefix {end}");}
 assert_eq!(tcp(&frame,false),Inspection::Known{protocol:"dns",domain:None});
 let mut pipeline=frame.clone();pipeline.extend(&frame);assert_eq!(tcp(&pipeline,false),Inspection::Known{protocol:"dns",domain:None});
 assert_eq!(tcp(&frame[..frame.len()-1],true),Inspection::Unknown);
 let mut invalid=frame.clone();invalid[14]=0xff;assert_eq!(tcp(&invalid,false),Inspection::Unknown);
 let mut response=frame;response[4]|=0x80;assert_eq!(tcp(&response,false),Inspection::Unknown);
}

#[test]fn additional_default_tcp_protocols_retain_segmentation_and_do_not_invent_domains(){
 let mut rdp=vec![3,0,0,19,14,0xe0,0,0,0,0,0,1,0,8];rdp.extend([0;5]);
 let mut openvpn=vec![0,14,0x38];openvpn.extend([1;8]);openvpn.extend([0;5]);
 for(bytes,protocol)in [(b"\x13BitTorrent protocol".to_vec(),"bittorrent"),(b"SSH-2.0-owned\r\n".to_vec(),"ssh"),(rdp,"rdp"),(openvpn,"openvpn")]{
  assert_eq!(tcp(&bytes,false),Inspection::Known{protocol,domain:None},"{protocol}");
  for end in 0..bytes.len().min(if protocol=="rdp"{14}else{bytes.len()}){assert_eq!(tcp(&bytes[..end],false),Inspection::Pending,"{protocol} {end}")}
 }
 for(line,name)in [("INVITE sip:user@Owned.INVALID:5060;transport=tcp SIP/2.0\r\n",Some("owned.invalid")),("OPTIONS sip:192.0.2.1 SIP/2.0\r\n",None),("INVITE tel:+1234 SIP/2.0\r\n",None)]{assert_eq!(tcp(line.as_bytes(),false),Inspection::Known{protocol:"sip",domain:name.map(str::to_owned)})}
 assert_eq!(tcp(b"OPTIONS / HTTP/1.1\r\nHost: owned.invalid\r\n\r\n",false),Inspection::Known{protocol:"http",domain:Some("owned.invalid".into())});
 let mut bad=vec![0,14,0x38];bad.extend([1;13]);assert_eq!(tcp(&bad,false),Inspection::Unknown);
}
