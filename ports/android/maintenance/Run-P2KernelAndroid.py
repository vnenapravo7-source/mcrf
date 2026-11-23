"""One owned kernel-fixture batch on Samsung; no APK, UI, VPN or app-data writes."""
import argparse, hashlib, json, pathlib, queue, re, subprocess, threading, uuid

ROOT = pathlib.Path('D:/MCRF/Projects/MCRF')
COORD = ROOT / 'ports/android/coordination'
ADB = pathlib.Path('C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe')
DEVICE = 'R5CX73QYN3D'
OWNER = '01a13a9b-6754-7b13-af52-422a6eb5579c'
AUTHORITY = 'human-20261021-samsung-owned-kernel-instead-of-wsl'

def sha(p): return hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()
def read(p): return json.loads(pathlib.Path(p).read_text(encoding='utf-8-sig'))
def save(p, value):
    target = pathlib.Path(p); temp = target.with_name(target.name+'.'+uuid.uuid4().hex+'.tmp')
    temp.write_text(json.dumps(value, indent=2), encoding='utf-8'); temp.replace(target)

class Batch:
    def __init__(self, source):
        self.source = source; self.package = read(source); self.pin = sha(source)
        self.adb_sha = sha(ADB); self.script_sha = sha(__file__)
        self.id = uuid.uuid4().hex; self.job = 'P2Kernel:'+self.id
        self.remote = '/data/local/tmp/mcrf-p2-kernel-'+self.id
        self.result = source.parent / 'android-kernel.json'
        if self.result.exists(): raise ValueError('Resume existing evidence, never silently rerun a completed package')
        self.state = dict(priority=2, passed=False, cleanupPending=False, cleanupComplete=False,
            package=str(source), packageSha256=self.pin, producerSha256=self.script_sha,
            adbSha256=self.adb_sha, device=DEVICE, uid=2000, environment='android-shell-kernel',
            compiledTarget='linux/arm64', androidAppAccepted=False, programs=[], failures=[])
        self.copied = []; self.made = False; self.pending = False

    def authority(self):
        auth = read(COORD/'p2-kernel-authority.json'); current = read(COORD/'current.json')
        lease = read(COORD/'device-lease.json'); ready = COORD/'p2-progress-2026-10-20.json'
        if (current.get('activeBatch') != ready.name or auth.get('authority') != AUTHORITY or
            auth.get('owner') != OWNER or auth.get('device') != DEVICE or
            auth.get('sourceReadySha256') != self.package['sourceReadySha256'] or
            sha(ready) != self.package['sourceReadySha256'] or lease.get('owner_thread') != OWNER or
            lease.get('device') != DEVICE or lease.get('state') != 'occupied_main_fixture_acceptance' or
            lease.get('kernel_check_authority') != AUTHORITY or
            lease.get('kernel_source_sha256') != self.package['sourceReadySha256']):
            raise ValueError('Fresh narrow main-owned kernel authority required')
        jobs = current['jobs']
        if jobs.get('device') not in (None,self.job) or any(jobs.get(k) for k in ('build','hostTest','emulator')):
            raise ValueError('Another common phase/device owner is active')
        if sha(self.source)!=self.pin or sha(ADB)!=self.adb_sha or sha(__file__)!=self.script_sha:
            raise ValueError('Owned controller/package changed')
        return current

    def adb(self, *args, cleanup=False, accepted=(0,)):
        if not cleanup: self.authority()
        value = subprocess.run([str(ADB),'-s',DEVICE,*args],capture_output=True,timeout=18)
        if value.returncode not in accepted: raise RuntimeError('ADB action failed; no permission bypass or unknown retry')
        return value.stdout.decode('utf-8',errors='replace').strip()

    def idle(self):
        self.authority()
        if self.adb('shell','getprop','ro.product.model')!='SM-F741B' or self.adb('shell','am','get-current-user')!='0':
            raise ValueError('Exact owner Samsung required')
        # No UI, app installation, TUN or private app data is used. The already
        # authorized unprivileged ADB shell can run this kernel-only fixture
        # with the screen off/behind keyguard; do not wake the screen, demand
        # a PIN, or change/bypass lock settings. VPN/user/UID guards still apply.
        vpn = self.adb('shell','dumpsys','vpn_management')
        if 'Active vpn type:' not in vpn or re.search(r'Active vpn type: [01]\b',vpn):
            raise ValueError('Active user VPN must remain untouched')
        if self.adb('shell','id','-u')!='2000': raise ValueError('Unprivileged shell UID required')

    def identity(self, pid, birth, remote, alive):
        if not re.fullmatch(r'[1-9][0-9]{0,9}',pid) or not re.fullmatch(r'[1-9][0-9]{0,19}',birth):
            raise ValueError('Malformed own process identity')
        value = self.adb('shell','-T',f'if [ -r /proc/{pid}/stat ]; then cat /proc/{pid}/stat; readlink /proc/{pid}/exe; cat /proc/{pid}/status; else echo OWN_GONE; fi',cleanup=True)
        if value=='OWN_GONE': return not alive
        lines=value.splitlines(); fields=lines[0].rsplit(')',1)[1].split()
        if fields[19]!=birth: return not alive
        if not alive: return False
        return lines[1]==remote and re.search(r'(?m)^Uid:\s+2000\s+2000\s+2000\s+2000\s*$',value) is not None

    def case(self, row, index):
        self.idle(); binary=pathlib.Path(row['binary'])
        if binary.parent!=self.source.parent or binary.is_symlink() or sha(binary)!=row['sha256']:
            raise ValueError('Exact compiled fixture required')
        name='p2'+str(index)+'_'+self.id[:8]; remote=self.remote+'/'+name
        self.copied.append(remote)
        self.adb('push',str(binary),remote)
        if self.adb('shell','sha256sum',remote).split()[0]!=row['sha256']: raise ValueError('Uploaded binary changed')
        self.adb('shell','chmod','700',remote)
        run_cases=row.get('runCases',row['expectedCases'])
        nonce=uuid.uuid4().hex; regex='^('+'|'.join(run_cases)+')$'
        command=f'cd {self.remote} && MCRF_P2_KERNEL_NONCE={nonce} exec ./{name} -test.v -test.count=1 -test.timeout=60s -test.run="{regex}"'
        self.authority(); self.pending=True; self.state['cleanupPending']=True; save(self.result,self.state)
        child=subprocess.Popen([str(ADB),'-s',DEVICE,'shell','-T',command],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
        first=queue.Queue(maxsize=1); output=bytearray(); overflow=[]
        def drain():
            initial=True
            for line in iter(child.stdout.readline,b''):
                if initial: first.put(line); initial=False
                if len(output)+len(line)>2097152: overflow.append(True)
                elif not overflow: output.extend(line)
            if initial: first.put(b'')
        reader=threading.Thread(target=drain,daemon=True); reader.start()
        pid=birth=None; granted=False
        try:
            header=first.get(timeout=15).decode('ascii',errors='strict').strip()
            match=re.fullmatch(r'P2_KERNEL_OWNER ([1-9][0-9]*) ([1-9][0-9]*) 2000 (\S+)',header)
            if not match or match[3]!=remote: raise ValueError('Own executable header not proven')
            pid,birth=match[1],match[2]
            if not self.identity(pid,birth,remote,True): raise ValueError('Own PID/birth/executable/UID not proven')
            self.authority(); child.stdin.write(('GO '+nonce+'\n').encode()); child.stdin.flush(); granted=True
            child.wait(timeout=100); reader.join(timeout=3)
        finally:
            child.stdin.close()
            try: child.wait(timeout=100)
            except subprocess.TimeoutExpired: raise RuntimeError('Own retirement unproven; retain device owner, no PID kill')
            reader.join(timeout=3)
            (self.source.parent/(row['name']+'-android.log')).write_bytes(output)
        text=output.decode('utf-8',errors='replace')
        done=not reader.is_alive() and not overflow and (not granted or re.search(r'(?m)^P2_KERNEL_DONE [01]\s*$',text))
        if pid and not self.identity(pid,birth,remote,False): done=False
        if self.adb('shell','pidof',name,cleanup=True,accepted=(0,1)): done=False
        if not done: raise RuntimeError('Actual own tree/reader retirement not proven; retain owner')
        self.pending=False; self.state['cleanupPending']=False
        cases=re.findall(r'(?m)^--- PASS: (Test\w+) \(',text)
        retained_cases=row.get('retainedCases',[])
        if retained_cases: self.retained(row,retained_cases)
        passed=(granted and child.returncode==0 and sorted(cases)==sorted(run_cases) and
            sorted(cases+retained_cases)==sorted(row['expectedCases']) and
            not re.search(r'(?m)^\s*(--- (FAIL|SKIP):|FAIL)',text))
        self.state['programs'].append(dict(name=row['name'],passed=bool(passed),cases=cases+retained_cases,
            executedCases=cases,retainedCases=retained_cases,retainedEvidence=row.get('retained'),
            binarySha256=row['sha256'],pid=pid,birth=birth,actualRetirement=True,
            logSha256=sha(self.source.parent/(row['name']+'-android.log'))))
        if not passed: self.state['failures'].append(row['name'])
        save(self.result,self.state)

    def retained(self, row, expected):
        ref=row['retained']; proof=read(ref['receipt']); package=read(ref['package'])
        if sha(ref['receipt'])!=ref['sha256'] or sha(ref['package'])!=ref['packageSha256'] or proof['packageSha256']!=ref['packageSha256']:
            raise ValueError('Retained actual receipt changed')
        old=next(p for p in proof['programs'] if p['name']==row['name'])
        binary=next(p for p in package['programs'] if p['name']==row['name'])
        log=pathlib.Path(ref['receipt']).parent/(row['name']+'-android.log')
        if (proof.get('device')!=DEVICE or proof.get('uid')!=2000 or not proof.get('cleanupComplete') or
            proof.get('cleanupPending') or not old.get('actualRetirement') or
            sha(binary['binary'])!=old['binarySha256'] or sha(log)!=old['logSha256'] or
            sorted(old['cases'])!=sorted(expected)):
            raise ValueError('Exact retired unchanged native cases required')
        return dict(old,retainedFrom=ref,newExecution=False,executedCases=[],retainedCases=old['cases'])

    def run(self):
        if self.package.get('state')!='COMPILED_UNRUN' or not self.package.get('compiled') or not self.package.get('sourceStable'):
            raise ValueError('Complete common frozen package required')
        if sorted(p['name'] for p in self.package['programs'])!=['dispatcher','group','wg']: raise ValueError('All three fixture groups required')
        if (sha(ROOT/'ports/android/maintenance/Prepare-P2KernelAndroid.ps1')!=self.package['producerSha256'] or
            sha(ROOT/'ports/android/maintenance/p2-kernel-owner.go.in')!=self.package['ownerTemplateSha256'] or
            sha(ROOT/'work/toolchains/go1.26.8/go/bin/go.exe')!=self.package['goSha256'] or
            sha(self.package['dispatcherSourceReceipt'])!=self.package['dispatcherSourceReceiptSha256']):
            raise ValueError('Compiled origin/controller/toolchain changed')
        for row in self.package['programs']:
            if row.get('target')!='linux/arm64': raise ValueError('Exact static ARM64 kernel target required')
            for path,expected in row['inputs'].items():
                if sha(path)!=expected: raise ValueError('Canonical fixture source changed')
            binary=pathlib.Path(row['binary']); header=binary.read_bytes()[:20]
            if header[:6]!=b'\x7fELF\x02\x01' or header[18:20]!=b'\xb7\x00': raise ValueError('Exact little-endian AArch64 ELF required')
        self.idle(); current=self.authority(); current['jobs']['device']=self.job; save(COORD/'current.json',current)
        try:
            save(self.result,self.state); self.adb('shell','mkdir','-m','700',self.remote); self.made=True
            for index,row in enumerate(self.package['programs']):
                if row.get('retained') and not row.get('runCases'):
                    self.state['programs'].append(self.retained(row,row['expectedCases'])); save(self.result,self.state)
                else: self.case(row,index)
            self.state['passed']=not self.state['failures'] and len(self.state['programs'])==3
        finally:
            if not self.pending:
                for remote in self.copied:
                    if not remote.startswith(self.remote+'/') or '/' in remote[len(self.remote)+1:]: raise ValueError('Invalid own cleanup path')
                    self.adb('shell','rm','-f',remote,cleanup=True)
                if self.made: self.adb('shell','rmdir',self.remote,cleanup=True)
                self.state['cleanupComplete']=True
                current=read(COORD/'current.json')
                if current['jobs'].get('device')==self.job: current['jobs']['device']=None; save(COORD/'current.json',current)
            save(self.result,self.state)
        print('P2_ANDROID_KERNEL_RECEIPT='+str(self.result))
        return 0 if self.state['passed'] else 1

if __name__=='__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('--package',required=True); args=parser.parse_args()
    source=pathlib.Path(args.package).absolute()
    if source.name!='receipt.json' or source.parent.parent!=pathlib.Path('D:/MCRF/Builds/Android') or not re.fullmatch(r'p2-kernel-[a-f0-9]{32}',source.parent.name):
        raise ValueError('Exact owned common stage required')
    raise SystemExit(Batch(source).run())
