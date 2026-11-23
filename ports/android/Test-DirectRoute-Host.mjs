// Owned loopback-only regression. Never starts TUN, touches Android, or contacts a provider.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import net from 'node:net';
import http from 'node:http';
import {spawn,spawnSync} from 'node:child_process';
import {once} from 'node:events';

const [xmlPath] = process.argv.slice(2);
if (!xmlPath) throw new Error('Pass the archived RouteRulesTest XML with actual compiler vectors');
const binary = 'D:/MCRF/Projects/MCRF/work/linux-tests/host-tools/sing-box.exe';
const xml = fs.readFileSync(xmlPath,'utf8');
const vectors = [...xml.matchAll(/MCRF_ROUTE_VECTOR:(ordinary|inverted|geoip|preset):(\{[^\r\n]+\})/g)]
  .map(([,name,json])=>({name,config:JSON.parse(json.replaceAll('&quot;','"').replaceAll('&lt;','<').replaceAll('&gt;','>').replaceAll('&amp;','&'))}));
if (vectors.length !== 4 || new Set(vectors.map(v=>v.name)).size !== 4) throw new Error('Ordinary, inverted, GeoIP and binary preset compiler vectors required');
for(const {config} of vectors){
  if(config.outbounds?.length!==2||config.outbounds.some(o=>!['direct','fixture-via'].includes(o.tag))||
     config.outbounds.find(o=>o.tag==='direct')?.type!=='direct'||config.outbounds.find(o=>o.tag==='fixture-via')?.type!=='http')
    throw new Error('Only the owned direct and loopback HTTP fixture outbounds are allowed');
}
const stage = path.join('D:/MCRF/Builds/Android','direct-host-'+crypto.randomUUID().replaceAll('-',''));
fs.mkdirSync(stage);
const digest = file=>crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
const receipt = {state:'ACTIVE',stage,scope:'Windows native/owned loopback HTTP+IPv4+IPv6; not Android/Internet acceptance',
  binarySha256:digest(binary),compilerXmlSha256:digest(xmlPath),results:[],childReaped:false,peersClosed:false};
const save = ()=>fs.writeFileSync(path.join(stage,'receipt.json'),JSON.stringify(receipt,null,2));
save();
const sockets = new Set();
const track = socket=>{sockets.add(socket);socket.once('close',()=>sockets.delete(socket));};
const hosts = {'domain.ru.fixture.test':'127.0.0.1','ip4.fixture.test':'127.0.0.2','ip6.fixture.test':'::1','foreign.fixture.test':'127.0.0.1'};
let via = 0, child = null, target = null, proxy = null, currentLog = '';
const listen = async(server,host)=>{server.listen(0,host);await once(server,'listening');return server.address().port;};
const pause = ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function retireChild() {
  if(!child)return;
  const owned=child;child=null;
  if(owned.exitCode===null&&owned.signalCode===null){owned.kill('SIGTERM');await Promise.race([once(owned,'exit'),pause(5000).then(()=>{throw new Error('Owned native child did not retire');})]);}
  receipt.childReaped=true;
}
function request(port,host,targetPort) {
  return new Promise((resolve,reject)=>{
    const authority=host.includes(':')?`[${host}]`:host;
    const req=http.get({host:'127.0.0.1',port,path:`http://${authority}:${targetPort}/owned`,agent:false},res=>{
      let body='';res.setEncoding('utf8');res.on('data',chunk=>body+=chunk);res.once('end',()=>res.statusCode===200&&body==='owned-loopback-ok'?resolve():reject(new Error('Owned HTTP response mismatch')));
    });
    req.setTimeout(4000,()=>req.destroy(new Error('Owned HTTP timeout')));req.once('error',reject);
  });
}
try {
  target=http.createServer((req,res)=>{res.writeHead(200,{'content-type':'text/plain','connection':'close'});res.end('owned-loopback-ok');});
  target.on('connection',track);
  const targetPort=await listen(target,'::');
  proxy=http.createServer((req,res)=>{res.writeHead(405);res.end();});
  proxy.on('connection',track);
  proxy.on('connect',(req,client,head)=>{
    let url;try{url=new URL(`http://${req.url}`);}catch{client.destroy();return;}
    const hostname=url.hostname.replace(/^\[|\]$/g,'');
    const destination=hosts[hostname]??hostname;
    if(!['127.0.0.1','127.0.0.2','::1'].includes(destination)||Number(url.port)!==targetPort){client.destroy();return;}
    via++;
    const upstream=net.connect({host:destination,port:targetPort});track(upstream);
    upstream.once('connect',()=>{client.write('HTTP/1.1 200 Connection Established\r\n\r\n');if(head.length)upstream.write(head);upstream.pipe(client);client.pipe(upstream);});
    upstream.on('error',()=>client.destroy());client.on('error',()=>upstream.destroy());client.once('close',()=>upstream.destroy());
  });
  const proxyPort=await listen(proxy,'127.0.0.1');
  for(const {name,config:compiled} of vectors){
    for(const negative of [false,true]){
      const config=structuredClone(compiled);
      if(negative)config.route.rules=config.route.rules.filter(rule=>rule.action!=='resolve');
      const reservation=net.createServer();const inboundPort=await listen(reservation,'127.0.0.1');await new Promise(resolve=>reservation.close(resolve));
      config.inbounds=[{type:'mixed',tag:'owned-input',listen:'127.0.0.1',listen_port:inboundPort}];
      config.outbounds.find(o=>o.tag==='fixture-via').server_port=proxyPort;
      config.dns={servers:[{type:'hosts',tag:'owned-hosts',predefined:Object.fromEntries(Object.entries(hosts).map(([h,ip])=>[h,[ip]]))}],final:'owned-hosts'};
      config.route.default_domain_resolver='owned-hosts';
      if(name==='geoip'||name==='preset'){
        const setFile=path.join(stage,'owned-geoip.json');
        fs.writeFileSync(setFile,JSON.stringify({version:3,rules:[{ip_cidr:['127.0.0.2/32','::1/128']}]}));
        const sets=config.route.rule_set;
        const expectedTag=name==='geoip'?'mcrf-geo-0':'mcrf-preset-0123456789abcdef';
        if(sets?.length!==1||sets[0].tag!==expectedTag||sets[0].type!=='local'||sets[0].format!==(name==='geoip'?'source':'binary'))throw new Error('Expected one owned typed IP fixture');
        if(name==='preset'){
          const output=path.join(stage,'owned-preset.srs');
          const compiled=spawnSync(binary,['rule-set','compile','--output',output,setFile],{windowsHide:true,encoding:'utf8',timeout:5000});
          if(compiled.status!==0||compiled.error)throw new Error('Owned binary preset compilation failed');
          sets[0].path=output;
        }else sets[0].path=setFile;
      }
      config.log={level:'info',timestamp:false};
      const file=path.join(stage,`${name}-${negative?'negative':'fixed'}.json`);fs.writeFileSync(file,JSON.stringify(config,null,2));
      currentLog='';receipt.childReaped=false;
      child=spawn(binary,['run','-c',file],{windowsHide:true,stdio:['ignore','pipe','pipe']});
      let launchError=null;child.once('error',error=>{launchError=error;});
      child.stdout.on('data',chunk=>currentLog+=chunk);child.stderr.on('data',chunk=>currentLog+=chunk);
      for(let i=0;i<100&&!currentLog.includes('sing-box started');i++){
        if(launchError||child.exitCode!==null)throw new Error('Owned native start failed');
        await pause(25);
      }
      if(!currentLog.includes('sing-box started'))throw new Error('Owned native readiness timeout');
      const checks=negative?[['ip4.fixture.test',1]]:[['domain.ru.fixture.test',0],['ip4.fixture.test',0],['ip6.fixture.test',0],['::1',0],['foreign.fixture.test',1]];
      for(const [host,expectedVia] of checks){
        const before=via;await request(inboundPort,host,targetPort);const actualVia=via-before;
        receipt.results.push({name,negativeControl:negative,host,expectedVia,actualVia,http:true,pass:actualVia===expectedVia});save();
        if(actualVia!==expectedVia)throw new Error(`Routing mismatch ${name}/${negative}/${host}`);
      }
      await retireChild();
      fs.writeFileSync(path.join(stage,`${name}-${negative?'negative':'fixed'}.log`),currentLog);
    }
  }
  if(digest(binary)!==receipt.binarySha256||digest(xmlPath)!==receipt.compilerXmlSha256)throw new Error('Native or archived compiler inputs changed');
  receipt.state='PASS_HOST_NOT_ANDROID';receipt.inputsStable=true;
}catch(error){receipt.state='FAILED';receipt.failure=String(error.message);throw error;}
finally {
  try{await retireChild();}catch(error){receipt.state='FAILED';receipt.retireFailure=String(error.message);}
  for(const socket of sockets)socket.destroy();
  await Promise.all([target,proxy].filter(Boolean).map(server=>new Promise(resolve=>server.close(resolve))));
  receipt.peersClosed=true;save();console.log(`DIRECT_HOST_RECEIPT=${path.join(stage,'receipt.json')}`);
}
