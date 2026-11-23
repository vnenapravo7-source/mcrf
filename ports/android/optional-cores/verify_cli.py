from pathlib import Path
import json, socket, subprocess, tempfile, sys

root=Path(__file__).resolve().parent
attempt=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else root/"build/attempt-04a69c6472424ef2872b865fa896cde9"
if root not in attempt.parents:
    raise SystemExit("CLI fixture outside optional cores")
checks=[]
outbounds={
 "xray":{"protocol":"vless","settings":{"vnext":[{"address":"127.0.0.1","port":443,"users":[{"id":"4b72af9e-32b4-4b14-a2fc-4a9100d2eb21","encryption":"none"}]}]},"streamSettings":{"network":"raw"}},
 "tls-core":{"type":"http","server":"127.0.0.1","server_port":443,"tls":{"enabled":True,"server_name":"fixture.example.test","tls_tricks":{"mixedcase_sni":True}},"tls_fragment":{"enabled":True,"size":"10-100","sleep":"2-5"}}
}
for kind,outbound in outbounds.items():
    with socket.socket() as reservation:
        reservation.bind(("127.0.0.1",0));port=reservation.getsockname()[1]
    config={"api":1,"socks":f"127.0.0.1:{port}","socks_user":"fixture-user-123456","socks_pass":"fixture-password-123456","outbound":outbound}
    with tempfile.TemporaryDirectory(dir=attempt) as private:
        file=Path(private)/"config.json"
        file.write_text(json.dumps(config))
        binary=attempt/f"optional-cores/build/{kind}.exe"
        result=subprocess.run([str(binary),"--mcrf-check",str(file)],capture_output=True,text=True,timeout=10)
        assert result.returncode==0 and result.stdout.strip()=="MCRF_VALID"
        with socket.socket() as probe:
            assert probe.connect_ex(("127.0.0.1",port))!=0
        checks.append({"kind":kind,"check":"native-preflight-valid-no-listener","passed":True})
        config["outbound"]["unknown-private-sentinel-123456"]=True
        file.write_text(json.dumps(config))
        result=subprocess.run([str(binary),"--mcrf-check",str(file)],capture_output=True,text=True,timeout=10)
        assert result.returncode==1 and result.stderr.strip()=="optional core request rejected"
        assert "sentinel" not in result.stdout+result.stderr and "fixture-password" not in result.stdout+result.stderr
        checks.append({"kind":kind,"check":"native-preflight-negative-private-errors-redacted","passed":True})
(attempt/"cli-verification.json").write_text(json.dumps(checks,indent=2))
print(json.dumps({"passed":len(checks)}))
