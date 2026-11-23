//! Owned peers and certificates only; actual original stream queues/TLS path.
use super::*;
use mcrf_gooby_builtin::Preset;
use tokio::net::TcpListener;
fn allocate()->Vec<u8>{let mut b=vec![0,3,0,8,0x21,0x12,0xa4,0x42];b.extend(0u8..12);b.extend([0,0x19,0,4,17,0,0,0]);b}
const DATA:[u8;8]=[0x40,0,0,3,0xa1,0xb2,0xc3,0];
#[tokio::test]
async fn tcp_presets_preserve_two_allocate_challenges_and_original_data_queue(){
    for preset in [Preset::Current,Preset::Gentle,Preset::Extended]{timeout(Duration::from_secs(5),async{
        let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();let address=listener.local_addr().unwrap();let request=allocate();let expected=request.clone();let mut challenge=allocate();challenge[..2].copy_from_slice(&[1,0x13]);challenge[20..].copy_from_slice(&[0,9,0,4,0,0,4,1]);let reply=challenge.clone();
        let server=tokio::spawn(async move{let(mut s,_)=listener.accept().await.unwrap();for _ in 0..2{let mut b=vec![0;expected.len()];s.read_exact(&mut b).await.unwrap();assert_eq!(b,expected);s.write_all(&reply).await.unwrap()}
            let mut b=[0;8];s.read_exact(&mut b).await.unwrap();assert_eq!(b,DATA);s.write_all(&DATA).await.unwrap();});
        let endpoint=TurnEndpoint{host:Arc::from("owned-turn.test"),port:address.port(),transport:TurnWireTransport::Tcp};
        let(writer,mut reader,_failure)=connect_inner(&endpoint,address,Some(preset),None).await.unwrap();let pool=PacketPool::new(4);
        for _ in 0..2{writer.write_control(&request).await.unwrap();match reader.read_frame(&pool).await.unwrap(){TurnStreamFrame::Control(b)=>assert_eq!(b,challenge),_=>panic!("expected unchanged owned Allocate")}}
        let mut packet=pool.acquire();packet.extend_tail(DATA.len()).unwrap().copy_from_slice(&DATA);writer.write_data(packet).unwrap();
        match reader.read_frame(&pool).await.unwrap(){TurnStreamFrame::Data(b)=>assert_eq!(b.as_slice(),DATA),_=>panic!("expected unchanged ChannelData")};server.await.unwrap();
    }).await.unwrap()}
}
#[tokio::test]
async fn tls_presets_keep_sni_and_certificate_validation(){
    let _=turn_rustls::crypto::aws_lc_rs::default_provider().install_default();
    for preset in [Preset::Current,Preset::Gentle,Preset::Extended]{for (trust,correct_name) in [(true,true),(false,true),(true,false)]{
        timeout(Duration::from_secs(5),async{
            let host="owned-turn.test";let cert=rcgen::generate_simple_self_signed(vec![host.to_owned()]).unwrap();
            let key=turn_rustls::pki_types::PrivatePkcs8KeyDer::from(cert.key_pair.serialize_der());
            let server=turn_rustls::ServerConfig::builder().with_no_client_auth().with_single_cert(vec![cert.cert.der().clone()],key.into()).unwrap();
            let mut roots=RootCertStore::empty();if trust{roots.add(cert.cert.der().clone()).unwrap()}
            let client=Arc::new(ClientConfig::builder().with_root_certificates(roots).with_no_client_auth());
            let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();let address=listener.local_addr().unwrap();let success=trust&&correct_name;
            let server=tokio::spawn(async move{
                let(socket,_)=listener.accept().await.unwrap();let accepted=turn_tokio_rustls::TlsAcceptor::from(Arc::new(server)).accept(socket).await;
                if !success{assert!(accepted.is_err());return}
                let mut tls=accepted.unwrap();assert_eq!(tls.get_ref().1.server_name(),Some(host));let mut bytes=[0;8];tls.read_exact(&mut bytes).await.unwrap();assert_eq!(bytes,DATA);tls.write_all(&DATA).await.unwrap();
            });
            let endpoint=TurnEndpoint{host:Arc::from(if correct_name{host}else{"wrong-owned.test"}),port:address.port(),transport:TurnWireTransport::Tls};
            let result=connect_inner(&endpoint,address,Some(preset),Some(client)).await;
            if success{let(writer,mut reader,_failure)=result.unwrap();writer.write_control(&DATA).await.unwrap();match reader.read_frame(&PacketPool::new(2)).await.unwrap(){TurnStreamFrame::Data(p)=>assert_eq!(p.as_slice(),DATA),_=>panic!("expected ChannelData")}}
            else{let error=result.err().expect("Gooby must not bypass TLS validation");assert!(format!("{error:#}").contains("certificate"),"{error:#}")}
            server.await.unwrap();
        }).await.unwrap()
    }}
}
#[tokio::test]
async fn cancelling_a_stalled_tls_handshake_closes_the_actual_remote_socket(){
    timeout(Duration::from_secs(3),async{
        let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();let address=listener.local_addr().unwrap();
        let task=tokio::spawn(async move{let endpoint=TurnEndpoint{host:Arc::from("owned-turn.test"),port:address.port(),transport:TurnWireTransport::Tls};connect_inner(&endpoint,address,Some(Preset::Current),None).await});
        let(mut remote,_)=listener.accept().await.unwrap();let mut first=[0];remote.read_exact(&mut first).await.unwrap();assert_eq!(first,[0x16]);task.abort();assert!(task.await.is_err());
        let mut rest=Vec::new();remote.read_to_end(&mut rest).await.unwrap();assert!(rest.len()<128*1024);
    }).await.unwrap()
}
