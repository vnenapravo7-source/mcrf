"""Actual ARM64 Reality test over owned USB reverse, no saved-server traffic."""
import json,os,re,subprocess,time,sys,queue,threading,shutil
from pathlib import Path
import p049_device as d
STAGE_OVERRIDE=None
BUILD_FILE='build.json'
MODES=['pq','short']
RECORD_FIRST=False
POLICY_NAME='hello-record49'
POLICY_HASH='recordTestSha256'
PEER_CURVE=''
def main():
 from connection49_source_gate import require_complete_source
 require_complete_source(d.ROOT)
 stage=STAGE_OVERRIDE or Path(json.loads((d.ROOT/'ports/android/coordination/p0-49-wire-stage.json').read_text('utf-8'))['stage'])
 build=json.loads((stage/BUILD_FILE).read_text('utf-8'));assert build['state']=='COMPILED_NOT_EXECUTED'
 assert d.sha(stage/'owned-reality49')==build['clientSha256'] and d.sha(stage/'owned-peer.exe')==build['peerSha256']
 if sys.argv[1:]==['--resume-peer-banner']:
  old=json.loads((stage/'runtime.json').read_text('utf-8'));assert not old['cases']
  assert not (stage/'runtime-attempt1.json').exists();shutil.copyfile(stage/'runtime.json',stage/'runtime-attempt1.json')
 else:assert not sys.argv[1:] and not (stage/'runtime.json').exists()
 d.ready(install=True)
 if RECORD_FIRST:
  record=stage/POLICY_NAME;assert d.sha(record)==build[POLICY_HASH]
  remote_record='/data/local/tmp/mcrf-p049-record49-steer10';record_owned=False
  try:
   d.adb('shell','test','!','-e',remote_record)
   d.adb('push',str(record),remote_record);record_owned=True;assert d.text('shell','sha256sum',remote_record).split()[0]==build[POLICY_HASH]
   d.adb('shell','chmod','700',remote_record);d.adb('shell',remote_record,timeout=15)
  finally:
   if record_owned:d.adb('shell','rm','-f',remote_record)
 assert PEER_CURVE in ['', 'p256']
 env=os.environ.copy();env['MCRF_P049_OWNED_WIRE']='1';env['MCRF_P049_CURVE']=PEER_CURVE
 args=[str(stage/'owned-peer.exe')]
 if build.get('peerKind')!='legacy-reality-main':
  assert not PEER_CURVE,'Curve fixture requires the owned legacy peer'
  args+=['-test.run=^TestP049OwnedRealityWire$','-test.timeout=95s']
 peer=subprocess.Popen(args,env=env,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True)
 remote='/data/local/tmp/mcrf-p049-reality-f4d44030819d4998';reverse=None;remote_owned=False;result={'state':'RUNNING','savedProfilesUsed':False,'p2Excluded':True,'peerCurve':PEER_CURVE,'cases':[]}
 try:
  peer_lines=queue.Queue()
  def read_peer():
   try:
    for line in peer.stdout:peer_lines.put(line)
   except (OSError,ValueError):return
  threading.Thread(target=read_peer,daemon=True).start()
  until=time.monotonic()+15;line=''
  while time.monotonic()<until:
   try:line=peer_lines.get(timeout=1)
   except queue.Empty:
    if peer.poll() is not None:break
    continue
   if line.startswith('OWNED_REALITY49='):break
  assert line.startswith('OWNED_REALITY49='),'Owned peer did not start'
  public=json.loads(line.partition('=')[2]);port=public['port'];target=int(public['target'].rsplit(':',1)[1]);assert public['target'].startswith('127.0.0.1:')
  assert public.get('curve','')==PEER_CURVE
  assert re.fullmatch('[A-Za-z0-9_-]{43}',public['publicKey']) and 1024<=port<=65535
  existing=d.text('reverse','--list');assert 'tcp:'+str(port) not in existing
  d.ready(install=True);d.adb('reverse','tcp:'+str(port),'tcp:'+str(port));reverse=port
  d.adb('shell','test','!','-e',remote)
  d.adb('push',str(stage/'owned-reality49'),remote);remote_owned=True;assert d.text('shell','sha256sum',remote).split()[0]==build['clientSha256'];d.adb('shell','chmod','700',remote)
  for mode in MODES:
   assert re.fullmatch(r'pq|short|badkey|empty|emptybadkey|edge|edgebadkey|ios|iosbadkey|qq|qqbadkey|safari|safaribadkey|firefox|firefoxbadkey',mode)
   d.ready(install=True);start=time.monotonic()
   p=subprocess.run([d.ADB,'-s',d.SERIAL,'shell',remote,str(port),public['publicKey'],str(target),mode],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=25)
   lines=[x for x in p.stdout.decode('ascii','replace').splitlines() if re.fullmatch(r'WIRE49 mode=(pq|short|badkey|empty|emptybadkey|edge|edgebadkey|ios|iosbadkey|qq|qqbadkey|safari|safaribadkey|firefox|firefoxbadkey) (handshake=-?[0-9]+( vless=[01] http204=[01])?|write=-?[0-9]+)',x)]
   result['cases'].append({'mode':mode,'exit':p.returncode,'milliseconds':round((time.monotonic()-start)*1000),'lines':lines})
  result['state']='EXECUTED_OWNED_REALITY_WIRE'
  if RECORD_FIRST:
   assert all(r['exit']==0 for r in result['cases'])
   assert any('handshake=0 vless=1 http204=1' in x for x in result['cases'][0]['lines'])
   assert result['cases'][1]['lines']==['WIRE49 mode='+MODES[1]+' handshake=-22']
   result.update(state='PASS_POLICY_AUTHENTICATED_REALITY_AND_WRONG_KEY_REFUSAL',policy=POLICY_NAME,policyPassed=True)
 finally:
  if reverse:d.adb('reverse','--remove','tcp:'+str(reverse))
  if remote_owned:d.adb('shell','rm','-f',remote)
  peer.stdin.close()
  try:peer.wait(timeout=5)
  except subprocess.TimeoutExpired:peer.kill();peer.wait()
  result['ownedReverseRemoved']=reverse is not None;result['ownedExecutableRemoved']=remote_owned;result['peerExited']=peer.returncode
  d.save(stage/'runtime.json',result)
 print(json.dumps(result))
if __name__=='__main__':main()
