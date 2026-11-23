from pathlib import Path
import http.client, json, queue, socket, subprocess, sys, tempfile, threading, time

root=Path(__file__).resolve().parent
attempt=Path(sys.argv[1]).resolve()
assert root in attempt.parents
fixture=Path(sys.argv[2]).resolve() if len(sys.argv)>2 else attempt
assert root in fixture.parents
checks=[]
def start(binary,*arguments):
    p=subprocess.Popen([str(binary),*arguments],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True)
    events=queue.Queue()
    def reader():
        for line in p.stdout:
            events.put(line.strip())
    threading.Thread(target=reader,daemon=True).start()
    return p,events
def stop(p):
    if p.poll() is None:
        try:p.stdin.write("STOP\n");p.stdin.flush();p.stdin.close();p.wait(timeout=5)
        except Exception:p.kill();p.wait(timeout=3)
def event(events,kind):
    while True:
        line=events.get(timeout=15)
        if line.startswith(kind+" "):return json.loads(line.split(" ",1)[1])
def full(sock,n):
    data=b""
    while len(data)<n:
        chunk=sock.recv(n-len(data));assert chunk;data+=chunk
    return data
for mode in ["tls-std","tls-chrome","tls-mozilla","xray-raw","xray-mux","xray-xhttp","xray-reality","xray-vision","xray-ws","xray-grpc","xray-httpupgrade"]:
    peer,events=start(fixture/"optional-fixture.exe","tls" if mode.startswith("tls-") else mode)
    worker=None
    try:
        ready=event(events,"MCRF_FIXTURE_READY")
        if mode.startswith("tls-"):
            kind="tls-core";host,port=ready["address"].rsplit(":",1)
            tls={"enabled":True,"server_name":"fixture.example.test","certificate":[ready["certificate"]],"tls_tricks":{"mixedcase_sni":True}}
            if mode=="tls-chrome":tls["utls"]={"enabled":True,"fingerprint":"chrome"}
            out={"type":"http","server":host,"server_port":int(port),"tls":tls,"tls_fragment":{"enabled":True,"size":"32-32","sleep":"20-20"}}
            target="127.0.0.1:80"
        else:kind="xray";out=ready["outbound"];target=ready["target"]
        with socket.socket() as reserve:reserve.bind(("127.0.0.1",0));port=reserve.getsockname()[1]
        config={"api":1,"socks":f"127.0.0.1:{port}","socks_user":"fixture-user-123456","socks_pass":"fixture-password-123456","outbound":out}
        if mode=="tls-mozilla":config["certificate_store"]="mozilla"
        with tempfile.TemporaryDirectory(dir=attempt) as private:
            file=Path(private)/"config.json";file.write_text(json.dumps(config))
            worker,output=start(attempt/f"optional-cores/build/{kind}.exe","--mcrf-config",str(file))
            deadline=time.monotonic()+15
            while True:
                remaining=deadline-time.monotonic();assert remaining>0,"Native readiness timeout"
                message=output.get(timeout=remaining)
                if message=="MCRF_READY":break
                assert len(message)<=4096 and "[Warning] common/errors: The feature " in message,"Unexpected native output: "+message
            with socket.create_connection(("127.0.0.1",port),timeout=10) as sock:
                sock.sendall(bytes([5,1,2]));assert full(sock,2)==bytes([5,2])
                user=config["socks_user"].encode();password=config["socks_pass"].encode();sock.sendall(bytes([1,len(user)])+user+bytes([len(password)])+password);assert full(sock,2)==bytes([1,0])
                target_port=int(target.rsplit(":",1)[1]);sock.sendall(bytes([5,1,0,1,127,0,0,1,target_port>>8,target_port&255]));header=full(sock,4);assert header[1]==0
                full(sock,6 if header[3]==1 else 18)
                sock.sendall(b"GET / HTTP/1.1\r\nHost: fixture\r\nConnection: close\r\n\r\n");response=http.client.HTTPResponse(sock);response.begin();assert response.status==200 and response.read().decode()==ready["payload"]
            if mode.startswith("tls-"):
                hello=event(events,"MCRF_FIXTURE_TLS");assert hello["records"]>1 and hello["max_record"]<=32 and hello["sni"].lower()=="fixture.example.test" and hello["sni"]!="fixture.example.test"
                assert hello["hello_complete"] and hello["hello_ms"]>=20
            else:
                with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as udp, socket.create_connection(("127.0.0.1",port),timeout=10) as control:
                    udp.bind(("127.0.0.1",0));control.sendall(bytes([5,1,2]));assert full(control,2)==bytes([5,2])
                    control.sendall(bytes([1,len(user)])+user+bytes([len(password)])+password);assert full(control,2)==bytes([1,0])
                    source=udp.getsockname()[1];control.sendall(bytes([5,3,0,1,127,0,0,1,source>>8,source&255]));reply=full(control,10);assert reply[:4]==bytes([5,0,0,1])
                    relay=(socket.inet_ntoa(reply[4:8]),int.from_bytes(reply[8:10],"big"));assert relay[0]=="127.0.0.1"
                    destination=int(ready["udp_target"].rsplit(":",1)[1]);payload=bytes(i%251 for i in range(1152))
                    packet=bytes([0,0,0,1,127,0,0,1,destination>>8,destination&255])+payload
                    with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as foreign:
                        foreign.bind(("127.0.0.1",0));foreign.settimeout(.5);foreign.sendto(packet,relay)
                        try:foreign.recvfrom(2048);raise AssertionError("Foreign UDP source accepted")
                        except socket.timeout:pass
                    udp.settimeout(10);udp.sendto(packet,relay);echo,address=udp.recvfrom(2048);assert echo==packet and address==relay
                    control.close();time.sleep(.25);udp.settimeout(.5);udp.sendto(packet,relay)
                    try:udp.recvfrom(2048);raise AssertionError("UDP relay survived TCP close")
                    except (socket.timeout,ConnectionResetError):pass
            checks.append({"mode":mode,"traffic":True,"udp":not mode.startswith("tls-"),"tls_capture":hello if mode.startswith("tls-") else None,"peer":"native loopback fixture"})
    finally:
        if worker is not None:stop(worker)
        stop(peer)
(fixture/"feature-peer-verification.json").write_text(json.dumps(checks,indent=2))
print(json.dumps({"passed":len(checks),"android_device_tested":False}))
