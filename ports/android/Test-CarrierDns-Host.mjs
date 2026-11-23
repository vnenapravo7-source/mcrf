// Pinned native DNS semantics, owned UDP loopback only. Not Android/site acceptance.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import dgram from 'node:dgram';
import {spawn} from 'node:child_process';
import {once} from 'node:events';

const [xmlPath]=process.argv.slice(2);
if(!xmlPath)throw new Error('Pass completed CarrierAddressFamilyTest XML');
const xml=fs.readFileSync(xmlPath,'utf8');
if(!xml.includes('failures="0"')||!xml.includes('errors="0"'))throw new Error('Completed passing fixture XML required');
const vector=xml.match(/MCRF_CARRIER_FAMILY_VECTOR:([A-Za-z0-9+/=]+)/);
if(!vector)throw new Error('Actual production policy vector missing');
const {original,fixed}=JSON.parse(Buffer.from(vector[1],'base64').toString('utf8'));
const baseline=structuredClone(fixed);delete baseline.dns.strategy;
if(JSON.stringify(baseline)!==JSON.stringify(original)||fixed.dns.strategy!=='ipv4_only')throw new Error('Unexpected production transform');
if(original.dns.servers.length!==1||original.dns.servers[0].server!=='127.0.0.1'||original.inbounds.length!==1||original.inbounds[0].listen!=='127.0.0.1'||original.outbounds.length!==1||original.outbounds[0].type!=='direct')throw new Error('Owned loopback vector only');
const binary='D:/MCRF/Projects/MCRF/work/linux-tests/host-tools/sing-box.exe';
const digest=file=>crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
const sha=digest(binary);
if(sha!=='1bf6c329963fbb6e4e80079f28e2079c0a6c3a860e8bdecf9a4dfc7f5bfa6b0f')throw new Error('Pinned native binary differs');
const stage=path.join('D:/MCRF/Builds/Android','carrier-dns-host-'+crypto.randomUUID().replaceAll('-',''));
fs.mkdirSync(stage);
const receipt={state:'ACTIVE',scope:'Windows native pinned DNS/owned UDP; NOT Android, TUN or Internet acceptance',binarySha256:sha,compilerXmlSha256:digest(xmlPath),results:[],childrenReaped:false,peersClosed:false};
const save=()=>fs.writeFileSync(path.join(stage,'receipt.json'),JSON.stringify(receipt,null,2));save();
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const bind=async(socket)=>{socket.bind(0,'127.0.0.1');await once(socket,'listening');return socket.address().port;};
const close=socket=>new Promise(resolve=>socket.close(resolve));
let child=null,log='',upstream=null;
let counts={A:0,AAAA:0};
async function retire(){
  if(!child)return;
  const owned=child;child=null;
  if(owned.exitCode===null&&owned.signalCode===null){owned.kill('SIGTERM');await Promise.race([once(owned,'exit'),pause(5000).then(()=>{throw new Error('Owned native child did not retire');})]);}
  receipt.childrenReaped=true;
}
function question(name,type){
  const header=Buffer.alloc(12);header.writeUInt16BE(crypto.randomInt(1,65535),0);header.writeUInt16BE(0x100,2);header.writeUInt16BE(1,4);
  const labels=Buffer.concat([...name.split('.').map(label=>Buffer.concat([Buffer.from([label.length]),Buffer.from(label)])),Buffer.from([0])]);
  const tail=Buffer.alloc(4);tail.writeUInt16BE(type,0);tail.writeUInt16BE(1,2);return Buffer.concat([header,labels,tail]);
}
async function query(port,name,type){
  const socket=dgram.createSocket('udp4');
  try{
    await bind(socket);const message=question(name,type);
    const result=new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(new Error('Owned DNS response timeout')),3000);socket.once('error',error=>{clearTimeout(timer);reject(error);});socket.once('message',(response,peer)=>{clearTimeout(timer);if(peer.address!=='127.0.0.1'||peer.port!==port||response.length<12||response.readUInt16BE(0)!==message.readUInt16BE(0))reject(new Error('Owned DNS transaction mismatch'));else resolve({rcode:response.readUInt16BE(2)&15,answers:response.readUInt16BE(6)});});});
    socket.send(message,port,'127.0.0.1');return await result;
  }finally{await close(socket);}
}
try{
  upstream=dgram.createSocket('udp4');const upstreamPort=await bind(upstream);
  upstream.on('message',(query,peer)=>{
    if(peer.address!=='127.0.0.1'||query.length<17)return;
    const type=query.readUInt16BE(query.length-4);if(type!==1&&type!==28)return;
    counts[type===1?'A':'AAAA']++;
    const data=type===1?Buffer.from([127,0,0,1]):Buffer.concat([Buffer.alloc(15),Buffer.from([1])]);
    const answer=Buffer.alloc(12);answer.writeUInt16BE(0xc00c,0);answer.writeUInt16BE(type,2);answer.writeUInt16BE(1,4);answer.writeUInt32BE(30,6);answer.writeUInt16BE(data.length,10);
    const reply=Buffer.from(query);reply.writeUInt16BE(0x8180,2);reply.writeUInt16BE(1,6);
    upstream.send(Buffer.concat([reply,answer,data]),peer.port,peer.address);
  });
  for(const [mode,vectorConfig] of [['negative-original',original],['fixed',fixed]]){
    const config=structuredClone(vectorConfig),reserve=dgram.createSocket('udp4');const port=await bind(reserve);await close(reserve);
    config.dns.servers[0].server_port=upstreamPort;config.inbounds[0].listen_port=port;config.log={level:'info',timestamp:false};
    const file=path.join(stage,`${mode}.json`);fs.writeFileSync(file,JSON.stringify(config,null,2));
    log='';receipt.childrenReaped=false;child=spawn(binary,['run','-c',file],{windowsHide:true,stdio:['ignore','pipe','pipe']});
    let launchError=null;child.once('error',error=>launchError=error);child.stdout.on('data',chunk=>log+=chunk);child.stderr.on('data',chunk=>log+=chunk);
    for(let i=0;i<100&&!log.includes('sing-box started');i++){if(launchError||child.exitCode!==null)throw new Error('Owned native start failed');await pause(25);}
    if(!log.includes('sing-box started'))throw new Error('Owned native readiness timeout');
    counts={A:0,AAAA:0};
    for(const [name,type,rcode,answers] of [['dual.fixture.test',1,0,1],['dual.fixture.test',28,0,mode==='fixed'?0:1],['blocked.fixture.test',28,3,0]]){
      const result=await query(port,name,type);receipt.results.push({mode,name,type,...result});save();
      if(result.rcode!==rcode||result.answers!==answers)throw new Error('Owned DNS response semantic mismatch');
    }
    if(counts.A!==1||counts.AAAA!==(mode==='fixed'?0:1))throw new Error('Owned upstream counters mismatch');
    receipt.results.push({mode,upstreamCounts:{...counts}});await retire();
  }
  if(digest(xmlPath)!==receipt.compilerXmlSha256||digest(binary)!==sha)throw new Error('Inputs changed');
  receipt.state='PASS_HOST_NOT_ANDROID';
}catch(error){receipt.state='FAILED';receipt.error=String(error.message);process.exitCode=1;}
finally{
  try{await retire();}catch(error){receipt.state='FAILED';receipt.cleanupError=String(error.message);process.exitCode=1;}
  if(upstream){await close(upstream);receipt.peersClosed=true;}
  save();console.log(JSON.stringify({state:receipt.state,receipt:path.join(stage,'receipt.json'),checks:receipt.results.length,childrenReaped:receipt.childrenReaped,peersClosed:receipt.peersClosed}));
}
