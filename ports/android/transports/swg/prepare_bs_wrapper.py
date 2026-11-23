"""Add a narrow BS selector to a freshly frozen production wrapper source.

Does not compile. The future native producer must record preparedSourceSha256,
bsTransportContract=1 and sourceManifestSha256 in its fresh receipt.
"""
import argparse
import json
import pathlib
import re
from bs_engine_inputs import regular, sha, require, bound, load, verify_multimode_preparation


def modern_profile_overlays():
    """Current reviewed source, never a relabelled frozen modern artifact."""
    root=pathlib.Path(__file__).resolve().parent
    return [{"canonical":"transports/swg/csqtt25/"+name,
             "destination":"internal/csqtt25/"+name,"sha256":sha(root / "csqtt25" / name)}
            for name in ("profile.go","profile_test.go","profile_gooby_test.go")]


def original_packet_sources(files):
    """Add the host-only mode to the shared CSQTT bridge, never public profiles."""
    bridge=files['csqtt_bridge.go'].decode('utf-8')
    start='\tmcrfNativeStreams.useChild()'
    require(bridge.count(start)==1 and 'mcrfReadOriginalPacketControl' not in bridge,
            'Original packet bridge preflight anchor changed')
    bridge=bridge.replace(start,'\toriginalControl, err := mcrfReadOriginalPacketControl(c.ProbeOnly)\n\tif err != nil { return err }\n'+start)
    child_start='\tif err = command.Start(); err != nil {'
    require(bridge.count(child_start)==1,"Private packet child start anchor changed")
    scan_start=('\tgo func() { defer close(scanDone); scanCSQTTOutput(ctx, c, reader, configs, send, cancel) }()\n'
                '\tdefer func() { cancel(); reader.Close(); <-scanDone }()\n')
    require(bridge.count(scan_start)==1,"Private child output start anchor changed")
    bridge=bridge.replace(scan_start,'')
    bridge=bridge.replace(child_start,
        '\tpacketAuth, err := csqtt.NewPacketAuth()\n\tif err != nil { return err }\n'
        '\tcommand.Env = packetAuth.ChildEnvironment(command.Env)\n'
        '\tpacketKey := mcrfChildPacketKey(command.Env)\n'
        +scan_start.replace('scanCSQTTOutput(ctx, c, reader, configs, send, cancel)',
                            'scanCSQTTChildOutput(ctx, c, reader, configs, send, packetKey, cancel)')
        +child_start+'\n'
        '\t\tif mcrfChildHasPrivatePacketData(err.Error(), packetKey) { return errors.New("cannot start bundled CSQTT transport") }')
    output_scan='func scanCSQTTOutput(ctx context.Context, c *bridgeConfig, reader io.Reader, configs chan<- string, send func(string), abort ...context.CancelFunc) {'
    require(bridge.count(output_scan)==1,"Private child output signature changed")
    bridge=bridge.replace(output_scan,output_scan+'\n'
        '\tscanCSQTTChildOutput(ctx, c, reader, configs, send, "", abort...)\n}\n\n'
        +output_scan.replace('scanCSQTTOutput','scanCSQTTChildOutput').replace('abort ...','packetKey string, abort ...'))
    output_line='\t\tline := scanner.Text()\n\t\tif mcrfForwardChildStreams'
    require(bridge.count(output_line)==1,"Private child output filter anchor changed")
    bridge=bridge.replace(output_line,
        '\t\tline := scanner.Text()\n\t\tif mcrfChildHasPrivatePacketData(line, packetKey) { continue }\n\t\tif mcrfForwardChildStreams')
    output_config='&event) == nil {\n\t\t\t\tpendingConfig = event.Config'
    require(bridge.count(output_config)==1,"Private child config filter anchor changed")
    bridge=bridge.replace(output_config,output_config.replace('== nil {',
        '== nil && !mcrfChildHasPrivatePacketData(event.Config, packetKey) {'))
    failure_line='if message := mcrfPublicChildFailure(c, line); message != "" {'
    require(bridge.count(failure_line)==1,"Private structured failure filter anchor changed")
    bridge=bridge.replace(failure_line,failure_line.replace('message != ""','message != "" && !mcrfChildHasPrivatePacketData(message, packetKey)'))
    relay='csqtt.RelayIP(ctx, device, socket)'
    require(bridge.count(relay)==1,"Ordinary CSQTT packet bridge anchor changed")
    bridge=bridge.replace(relay,'csqtt.RelayIP(ctx, device, packetAuth.Socket(socket))')
    anchor='\t// MTU is fixed at 1300 in the original Android VPN service.'
    require(bridge.count(anchor)==1,'Original packet bridge GETCONF anchor changed')
    bridge=bridge.replace(anchor,'\tif originalControl != nil {\n\t\treturn mcrfRunOriginalPacketBridge(ctx, *originalControl, tunnel, c.Listen, done, packetAuth)\n\t}\n'+anchor)
    files['csqtt_bridge.go']=bridge.encode('utf-8')
    filter_test=pathlib.Path(__file__).with_name('mcrf_child_packet_output_test.go')
    files[filter_test.name]=regular(filter_test).read_bytes()
    root=pathlib.Path(__file__).with_name('native-packet')
    for name in ('mcrf_original_packet_bridge_unix.go','mcrf_original_packet_bridge_other.go','mcrf_original_packet_bridge_unix_test.go'):
        files[name]=regular(root/name).read_bytes()
    for name in ('mcrf_packet_port_unix.go','mcrf_packet_port_unix_test.go'):
        files['internal/csqtt/'+name]=regular(root/name).read_bytes()
    for name in ('mcrf_authenticated_udp.go','mcrf_authenticated_udp_test.go'):
        files['internal/csqtt/'+name]=regular(root/name).read_bytes()
    relay=files['internal/csqtt/relay.go']
    anchor=b'socket *net.UDPConn'
    require(relay.count(anchor)==1,"Private packet relay socket signature changed")
    files['internal/csqtt/relay.go']=relay.replace(anchor,b'socket io.ReadWriteCloser')


def bind_private_socks_udp(source, files):
    """Use the same authenticated source tuple gate for QWDTT and CSQTT."""
    from importlib.util import module_from_spec, spec_from_file_location
    overlay=pathlib.Path(__file__).resolve().parent.parent / "socks-owner/overlay.py"
    spec=spec_from_file_location("mcrf_socks_owner",overlay)
    owner=module_from_spec(spec)
    spec.loader.exec_module(owner)
    result=owner.qwdtt_worker(source)
    path="bridge_socks.go"
    marker="MCRF_SOCKS_UDP_SOURCE_V1_qwdtt"
    names=("csqtt","csqtt20","csqtt215","csqtt25")
    text=result[path]
    require(text.count(marker)==1,"Common SOCKS source tuple capability changed")
    files[path]=text.replace(marker, " ".join((marker, *("MCRF_SOCKS_UDP_SOURCE_V1_"+name for name in names)))).encode("utf-8")
    fixture="mcrf_socks_udp_owner_test.go"
    files[fixture]=result[fixture].encode("utf-8")
    files["mcrf_socks_udp_source_test.go"]=regular(pathlib.Path(__file__).with_name("mcrf_socks_udp_source_test.go")).read_bytes()

    # Keep the frozen bridge smoke test aligned with explicit UDP source binding.
    test = files["bridge_test.go"].decode("utf-8").replace("\r\n", "\n")
    old = ('\tucontrol := testControl(t, a, "secret")\n'
           '\tbound := testRequest(t, ucontrol, 3, "0.0.0.0:0")\n'
           '\trelay, err := net.Dial("udp", bound)')
    new = ('\trelay, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})\n'
           '\tif err != nil { t.Fatal(err) }\n'
           '\tucontrol := testControl(t, a, "secret")\n'
           '\tbound := testRequest(t, ucontrol, 3, relay.LocalAddr().String())\n'
           '\tboundAddress, err := net.ResolveUDPAddr("udp4", bound)')
    send = '\t_, _ = relay.Write(frame)'
    invalid_pool = 'strings.Replace(valid, `"hash"`, `"a","b","c","d","e"`, 1)'
    require(test.count(old) == test.count(send) == test.count(invalid_pool) == 1,
            "Bridge test adaptation anchors changed")
    test = test.replace(old, new).replace(send, '\t_, _ = relay.WriteToUDP(frame, boundAddress)')
    # Multiple hashes are valid. Use an empty hash to exercise rejection.
    files["bridge_test.go"] = test.replace(invalid_pool, 'strings.Replace(valid, `"hash"`, `""`, 1)').encode("utf-8")


def source_rows(source, manifest, expected):
    require(sha(manifest) == expected, "Frozen baseline manifest required")
    rows = load(manifest)
    require(len(rows) == len({r["path"] for r in rows}), "Duplicate source path")
    actual = {p.relative_to(source).as_posix() for p in source.rglob("*") if p.is_file() and
              (p.suffix == ".go" or p.name in ("go.mod", "go.sum"))}
    expected_go = {r["path"] for r in rows if pathlib.Path(r["path"]).suffix == ".go" or
                   pathlib.Path(r["path"]).name in ("go.mod", "go.sum")}
    require(actual == expected_go, "Baseline source set differs")
    for row in rows:
        bound(row, source)
    return rows


def selected_sources(source, manifest, expected, superset=None, duplicate=None):
    rows = source_rows(source, manifest, expected)
    if superset is None:
        require(duplicate is None, "Duplicate proof requires superset")
        return source, rows
    require(duplicate is not None, "Exact QWDTT source proof required")
    # Bound both receipts and every source byte before considering deduplication.
    modern = load(bound(superset))
    qwdtt = load(bound(duplicate))
    for receipt, kind in ((modern, "csqtt25"), (qwdtt, "qwdtt")):
        require(receipt["kind"] == kind and receipt["sourceStable"] is True and
                receipt["state"] == "COMPILED_NOT_EXECUTED", "Unadmitted superset/duplicate receipt")
    modern_source, duplicate_source = pathlib.Path(modern["source"]), pathlib.Path(qwdtt["source"])
    modern_rows = source_rows(modern_source, pathlib.Path(superset["path"]).parent / "source-manifest.json", modern["sourceManifestSha256"])
    duplicate_rows = source_rows(duplicate_source, pathlib.Path(duplicate["path"]).parent / "source-manifest.json", qwdtt["sourceManifestSha256"])
    go = lambda entries: {r["path"]: r["sha256"] for r in entries if pathlib.Path(r["path"]).suffix == ".go" or pathlib.Path(r["path"]).name in ("go.mod", "go.sum")}
    old, new = go(rows), go(modern_rows)
    require(old == go(duplicate_rows), "QWDTT is not an exact wrapper source duplicate")
    # Reviewed differences: modern entry point/bridge plus stream metrics and
    # cancellation fix. Wire protocol, dependencies, credentials are unchanged.
    changed = {"main.go", "csqtt_bridge.go", "bridge_config.go", "session.go",
               "mcrf_transport_performance.go", "mcrf_transport_performance_test.go"}
    require(set(old) <= set(new) and {p for p in old if old[p] != new[p]} <= changed,
            "Unreviewed common wrapper delta")
    require("mcrf_csqtt25.go" in new and "internal/csqtt25/profile.go" in new,
            "Modern bridge absent")
    return modern_source, modern_rows


def align_qwdtt_dispatcher_counters(content):
    text = content.decode("utf-8")

    def replace(old, new, count=1):
        nonlocal text
        require(text.count(old) == count, "QWDTT dispatcher counter sites changed: " + old)
        text = text.replace(old, new)

    # ARMv7 does not align plain uint64 struct fields for 64-bit atomics.
    for name in ("tunReadCount", "tunSentCount", "tunDroppedCount"):
        declaration = re.search(r"(?m)^\t" + name + r"[ \t]+uint64\b", text)
        require(declaration is not None, "QWDTT dispatcher counter declaration changed: " + name)
        text = text.replace(declaration.group(), "\t" + name + " atomic.Uint64", 1)
    for name in ("tunReadCount", "tunDroppedCount"):
        replace("atomic.AddUint64(&d." + name + ", 1)", "d." + name + ".Add(1)")
    replace("atomic.LoadUint64(&d.tunSentCount)", "d.tunSentCount.Load()")
    replace("atomic.LoadUint64(&d.tunDroppedCount)", "d.tunDroppedCount.Load()")
    replace("atomic.AddUint64(&d.tunSentCount, 1)", "d.tunSentCount.Add(1)", 2)
    return text.encode("utf-8")


def add_qwdtt_underlay(files):
    files["dispatcher.go"] = align_qwdtt_dispatcher_counters(files["dispatcher.go"])

    def replace(path, old, new):
        text = files[path].decode("utf-8")
        if "\n" in old and old not in text:
            old = old.replace("\n", "\r\n")
            new = new.replace("\n", "\r\n")
        require(text.count(old) == 1, "QWDTT underlay source boundary changed: " + path)
        files[path] = text.replace(old, new).encode("utf-8")

    launch = '\tif err := mcrfBSValidateLaunch(os.Args); err != nil { log.Print(err); os.Exit(1) }\n'
    replace("main.go", launch, launch+'\tif err := mcrfConfigureUnderlay(); err != nil { log.Print(err); os.Exit(1) }\n')
    replace("session.go", 'conn, err := (&net.Dialer{}).DialContext(ctx, "udp", turnAddr)',
            'dialer := mcrfUnderlayDialer(net.Dialer{})\n\t\tconn, err := dialer.DialContext(ctx, "udp", turnAddr)')
    replace("session.go", "d := net.Dialer{Timeout: 10 * time.Second}", "d := mcrfUnderlayDialer(net.Dialer{Timeout: 10 * time.Second})")
    for path in ("creds.go", "creds_vkcalls.go"):
        replace(path, "tlsclient.NewHttpClient(tlsclient.NewNoopLogger(),", "mcrfNewUnderlayHTTPClient(tlsclient.NewNoopLogger(),")
    replace("creds.go", 'dialer := &net.Dialer{\n\t\tTimeout:   3 * time.Second,\n\t\tKeepAlive: 30 * time.Second,\n\t}',
            'dialer := mcrfUnderlayDialer(net.Dialer{Timeout: 3 * time.Second, KeepAlive: 30 * time.Second})')
    replace("doh.go", 'func newDoHBootstrapDialer() *net.Dialer {\n\treturn &net.Dialer{',
            'func newDoHBootstrapDialer() *net.Dialer {\n\tdialer := mcrfUnderlayDialer(net.Dialer{')
    replace("doh.go", '\t}\n}\n\nfunc newDoHHTTPClient()', '\t})\n\treturn &dialer\n}\n\nfunc newDoHHTTPClient()')
    replace("captcha_proxy.go", 'return startCaptchaProxyWithDial(parent, (&net.Dialer{Timeout: 15 * time.Second}).DialContext)',
            'dialer := mcrfUnderlayDialer(net.Dialer{Timeout: 15 * time.Second})\n\treturn startCaptchaProxyWithDial(parent, dialer.DialContext)')
    for name in ("mcrf_underlay.go", "mcrf_underlay_android.go", "mcrf_underlay_test.go", "mcrf_underlay_android_test.go"):
        files[name] = regular(pathlib.Path(__file__).with_name(name)).read_bytes()


def adapted_sources(source, rows, multimode, rust=None):
    files = {r["path"]: regular(source / r["path"]).read_bytes() for r in rows if
             pathlib.Path(r["path"]).suffix == ".go" or pathlib.Path(r["path"]).name in ("go.mod", "go.sum")}
    bridge = files["csqtt_bridge.go"].decode("utf-8")
    anchor = '\tcommand := exec.Command(filepath.Join(filepath.Dir(exe), name))'
    require(bridge.count(anchor) == 1 and "mcrfBSTransport" not in bridge and 'name = "libcsqtt_transport.so"' in bridge,
            "Frozen production wrapper anchor changed")
    selection = '\tname, err = mcrfBSTransport(name)\n\tif err != nil { return err }\n'
    if multimode:
        require("func runCSQTTBridgePrepared(" in bridge, "Superset bridge required")
        selection = '\tif launch == nil {\n' + selection + '\t}\n'
        main = files["main.go"].decode("utf-8")
        require(main.count("func main() {\n") == 1 and '"-csqtt25-config"' in main and '"-csqtt-config"' in main,
                "Superset CLI changed")
        files["main.go"] = main.replace("func main() {\n", 'func main() {\n\tif err := mcrfBSValidateLaunch(os.Args); err != nil { log.Print(err); os.Exit(1) }\n').encode()
    files["csqtt_bridge.go"] = bridge.replace(anchor, selection + anchor).encode()
    if rust is not None:
        from csqtt_multicall_producer import verify_compiled_proof
        proof=verify_compiled_proof(rust,all_abis=True)
        bridge=files["csqtt_bridge.go"].decode()
        env='\tcommand.Env = append(os.Environ(), "CSQTT_EVENTS=1", "CSQTT_CONFIG_FILE="+path)'
        require(bridge.count(env)==1,"Legacy child env anchor changed")
        files["csqtt_bridge.go"]=bridge.replace(env,env+'\n\tcommand.Env, err = mcrfBSChildEnv(command.Env)\n\tif err != nil { return err }').encode()
        modern=files["mcrf_csqtt25.go"].decode()
        name='\t\tname, err := csqtt25.NativeFilename(profile)'
        require(modern.count(name)==1,"Modern launch anchor changed")
        modern=modern.replace(name,name+'\n\t\tif os.Getenv("MCRF_BS_LAYOUT") == "rust-multicall-v1" {\n\t\t\tif e := mcrfBSModernImplementation(profile.Implementation); e != nil { return nil, "", e }; name = "libcsqtt_transport.so"\n\t\t}')
        files["mcrf_csqtt25.go"]=modern.encode()
        runtime=files["internal/csqtt25/runtime.go"].decode()
        begin='func LoadRuntime(path string) (*Runtime, error) {'
        require(runtime.count(begin)==1,"Modern runtime boundary changed")
        runtime=runtime.replace(begin,begin+'\n\tif filepath.Base(path)=="libcsqtt_transport.so" { return loadBSMulticallRuntime(path) }')
        marker='case "CSQTT_CONFIG_FILE",'
        require(runtime.count(marker)==1,"Modern env boundary changed")
        runtime=runtime.replace(marker,'case "MCRF_BS_RUST_MODE", "MCRF_BS_MODE", "MCRF_BS_LAYOUT", "MCRF_BS_TRANSPORT", "MCRF_CSQTT_LEGACY_UNDERLAY", "CSQTT_CONFIG_FILE",')
        marker='\treturn command, nil'
        require(runtime.count(marker)==1,"Modern command boundary changed")
        runtime=runtime.replace(marker,'\tif filepath.Base(r.path)=="libcsqtt_transport.so" { mode,err:=bsMulticallMode(); if err!=nil{return nil,err}; command.Env=append(command.Env,"MCRF_BS_RUST_MODE="+mode) }\n'+marker)
        files["internal/csqtt25/runtime.go"]=runtime.encode()
        template=regular(pathlib.Path(__file__).with_name("bs_multicall_runtime.go.in")).read_text()
        pins='\n'.join('    '+json.dumps(r['abi'])+': {'+json.dumps(r['sha256'])+', '+str(r['bytes'])+'},' for r in proof['artifacts'] if r['target']!='host')
        require(template.count('// MCRF_NATIVE_PINS')==1,"Native pin template changed")
        files['internal/csqtt25/mcrf_multicall.go']=template.replace('// MCRF_NATIVE_PINS',pins).encode()
    # Source overlay, NOT an old receipt/binary refreeze: compile the reviewed
    # current failure filter and tests into the FRESH wrapper. verify_preparation
    # reconstructs these exact canonical bytes before and after compilation.
    for name in ("mcrf_bs_transport.go", "mcrf_bs_transport_test.go", "mcrf_child_failure.go", "mcrf_child_failure_test.go"):
        files[name] = regular(pathlib.Path(__file__).with_name(name)).read_bytes()
    if multimode:
        for row in modern_profile_overlays():
            files[row["destination"]]=regular(pathlib.Path(__file__).resolve().parents[2] / row["canonical"]).read_bytes()
        original_packet_sources(files)
        bind_private_socks_udp(source, files)
        add_qwdtt_underlay(files)
    return files


def prepare(source, manifest, expected_sha, output, superset=None, duplicate=None, rust=None):
    source, output = pathlib.Path(source).absolute(), pathlib.Path(output).absolute()
    selected, rows = selected_sources(source, manifest, expected_sha, superset, duplicate)
    require(not output.exists(), "Fresh output required")
    helper = pathlib.Path(__file__).with_name("mcrf_bs_transport.go")
    files = adapted_sources(selected, rows, superset is not None, rust)
    for name, content in files.items():
        dest = output / name
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(content)
    inputs = [{"path": p.relative_to(output).as_posix(), "sha256": sha(p)} for p in sorted(output.rglob("*")) if p.is_file()]
    proof = {"state": "BS_WRAPPER_PREPARED_NOT_COMPILED", "root": str(output), "baselineManifestSha256": expected_sha,
             "adapterSha256": sha(__file__), "helperSha256": sha(helper), "sources": inputs,
             "modeContract": 3 if rust else 2 if superset else 1, "baselineSource": str(source), "baselineManifest": str(pathlib.Path(manifest).absolute()),
             "superset": superset, "duplicate": duplicate, "rust":rust}
    if superset is not None:proof["canonicalProfileOverlays"]=modern_profile_overlays()
    if superset is not None:proof["originalPacketContract"]=1
    if rust is not None and superset is not None:
        proof["privatePacketBridgeContract"]=1
        proof["socksUdpSource"]={name:1 for name in ("csqtt","csqtt20","csqtt215","csqtt25","qwdtt")}
    (output / "bs-wrapper-source.json").write_text(json.dumps(proof, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--source")
    parser.add_argument("--manifest")
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--output")
    parser.add_argument("--verify-proof")
    parser.add_argument("--superset-receipt")
    parser.add_argument("--superset-sha256")
    parser.add_argument("--duplicate-receipt")
    parser.add_argument("--duplicate-sha256")
    parser.add_argument("--rust-receipt")
    parser.add_argument("--rust-sha256")
    parser.add_argument("--rust-post-build")
    parser.add_argument("--rust-post-build-sha256")
    args = parser.parse_args()
    if args.verify_proof:
        require(sha(args.verify_proof) == args.sha256, "Pinned prepared proof required")
        verify_multimode_preparation(load(args.verify_proof))
        print("Multi-mode prepared source: PASS")
        raise SystemExit(0)
    require(args.source and args.manifest and args.output, "Source/manifest/output required")
    require(bool(args.superset_receipt) == bool(args.superset_sha256) == bool(args.duplicate_receipt) == bool(args.duplicate_sha256), "Complete superset/duplicate pins required")
    require(bool(args.rust_receipt)==bool(args.rust_sha256) and
            bool(args.rust_post_build)==bool(args.rust_post_build_sha256) and
            (not args.rust_post_build or args.rust_receipt),"Complete Rust receipt and optional post-build pins required")
    rust={"path":args.rust_receipt,"sha256":args.rust_sha256} if args.rust_receipt else None
    if args.rust_post_build:
        rust["postBuildEvidence"]={"path":args.rust_post_build,"sha256":args.rust_post_build_sha256}
    prepare(args.source, args.manifest, args.sha256, args.output,
            {"path": args.superset_receipt, "sha256": args.superset_sha256} if args.superset_receipt else None,
            {"path": args.duplicate_receipt, "sha256": args.duplicate_sha256} if args.duplicate_receipt else None,
            rust)
