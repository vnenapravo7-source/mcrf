// Read-only verification: fetch into memory, never extract files or execute JS.
// Signature framing matches pinned transport/script/flux.go: three uint32-BE lengths.
import {readFile} from 'node:fs/promises';
import {createHash,createPublicKey,verify} from 'node:crypto';
import {inflateRawSync} from 'node:zlib';
import assert from 'node:assert/strict';

const lock=JSON.parse(await readFile(new URL('./scripts-lock.json',import.meta.url),'utf8'));
const publicKey=hex=>createPublicKey({key:Buffer.concat([Buffer.from('302a300506032b6570032100','hex'),Buffer.from(hex,'hex')]),format:'der',type:'spki'});
const key=publicKey(lock.officialKey);
const wrongKey=publicKey('00'.repeat(32));
const LIMIT=4*1024*1024;
function unzip(data){
  let end=-1;
  for(let i=data.length-22;i>=Math.max(0,data.length-65557);i--)if(data.readUInt32LE(i)===0x06054b50){end=i;break;}
  assert(end>=0,'ZIP directory absent');
  assert.equal(data.readUInt16LE(end+4),0);assert.equal(data.readUInt16LE(end+6),0);
  const count=data.readUInt16LE(end+10);assert(count>0&&count<=32);
  let offset=data.readUInt32LE(end+16),total=0;
  const files=new Map();
  for(let i=0;i<count;i++){
    assert.equal(data.readUInt32LE(offset),0x02014b50);
    const flags=data.readUInt16LE(offset+8),method=data.readUInt16LE(offset+10);
    const compressed=data.readUInt32LE(offset+20),size=data.readUInt32LE(offset+24);
    const nameLength=data.readUInt16LE(offset+28),extra=data.readUInt16LE(offset+30),comment=data.readUInt16LE(offset+32);
    const local=data.readUInt32LE(offset+42),name=data.subarray(offset+46,offset+46+nameLength).toString('utf8');
    assert(!(flags&1)&&[0,8].includes(method)&&size<=LIMIT&&compressed<=LIMIT);
    assert(!files.has(name)&&!name.includes('..')&&!name.startsWith('/'));
    assert.equal(data.readUInt32LE(local),0x04034b50);
    const start=local+30+data.readUInt16LE(local+26)+data.readUInt16LE(local+28);
    assert(start+compressed<=data.length);
    const packed=data.subarray(start,start+compressed);
    const raw=method===0?packed:inflateRawSync(packed,{maxOutputLength:LIMIT});
    assert.equal(raw.length,size);total+=raw.length;assert(total<=LIMIT);
    files.set(name,raw);offset+=46+nameLength+extra+comment;
  }
  return files;
}
function framed(parts){return Buffer.concat(parts.flatMap(part=>{const size=Buffer.alloc(4);size.writeUInt32BE(part.length);return [size,part];}));}
async function check(pkg){
  assert(pkg.url.startsWith('https://github.com/p1neappleXpress/OpenFluxTransports/releases/download/'));
  const response=await fetch(pkg.url,{signal:AbortSignal.timeout(30000)});
  assert.equal(response.status,200,`${pkg.id}: HTTP ${response.status}`);
  const chunks=[];let bytes=0;
  for await(const chunk of response.body){bytes+=chunk.length;assert(bytes<=LIMIT);chunks.push(chunk);}
  const data=Buffer.concat(chunks),sha256=createHash('sha256').update(data).digest('hex');
  assert.equal(sha256,pkg.sha256,`${pkg.id}: SHA mismatch`);
  const files=unzip(data),manifestBytes=files.get('manifest.json'),source=files.get('main.js'),signature=files.get('package.sig');
  assert(manifestBytes&&source&&signature);
  const manifest=JSON.parse(manifestBytes.toString('utf8'));
  assert.equal(manifest.id??manifest.name.toLowerCase(),pkg.id);
  assert.equal(manifest.version,pkg.version);assert.equal(manifest.api??1,pkg.api);assert.equal(manifest.wire??1,pkg.wire);assert(pkg.api<=1);
  const icon=manifest.icon?files.get(manifest.icon):Buffer.alloc(0);assert(icon);
  const payload=framed([manifestBytes,source,icon]);
  assert.equal(signature.length,64);assert(verify(null,payload,key,signature),`${pkg.id}: signature rejected`);
  const tampered=Buffer.from(payload);tampered[tampered.length-1]^=1;
  assert(!verify(null,tampered,key,signature),'tampered package accepted');
  assert(!verify(null,payload,wrongKey,signature),'foreign key accepted');
  return {id:pkg.id,version:pkg.version,bytes,sha256,signatureVerified:true,negativeControlsPassed:true,hostAPI:pkg.api};
}
const packages=[];
for(let i=0;i<lock.packages.length;i+=3)packages.push(...await Promise.all(lock.packages.slice(i,i+3).map(check)));
console.log(JSON.stringify({state:'OFFICIAL_PACKAGE_BYTES_AND_SIGNATURES_VERIFIED',checkedAt:new Date().toISOString(),indexCommit:lock.indexCommit,packages,pending:lock.pending,jsExecuted:false,nativeBuilt:false,runtimeAccepted:false}));
