"""Stage Android owner admission without editing the pinned libbox trees."""
import argparse
import hashlib
import json
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("stage", type=Path)
    parser.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    source = args.source.resolve()
    stage = args.stage.resolve()
    sources = {}

    def patch(name: str, *edits: tuple[str, str]) -> None:
        contents = (source / name).read_text(encoding="utf-8")
        for before, after in edits:
            if contents.count(before) != 1:
                raise ValueError(f"Owner admission anchor changed: {name}")
            contents = contents.replace(before, after)
        sources[name] = contents

    libbox = "experimental/libbox"
    listener = "common/listener"
    patch(f"{libbox}/platform.go", (
        "\tUseProcFS() bool",
        "\tUseProcFS() bool\n\tRequireVpnOwnerIsolation() (bool, error)",
    ))
    patch(f"{libbox}/service.go", (
        "\tmyTunAddress           []netip.Addr",
        "\townerTunAddressAccess  sync.RWMutex\n\tmyTunAddress           []netip.Addr",
    ), (
        "\tw.myTunAddress = myTunAddress(options)",
        "\tw.ownerTunAddressAccess.Lock()\n\tw.myTunAddress = myTunAddress(options)\n\tw.ownerTunAddressAccess.Unlock()",
    ), (
        "\treturn tun.New(*options)",
        """\tdevice, err := tun.New(*options)
\tif err != nil || runtime.GOOS != "android" {
\t\treturn device, err
\t}
\tstrict, err := w.RequireVpnOwnerIsolation()
\tif err != nil {
\t\treturn nil, E.Errors(err, device.Close())
\t}
\tif strict {
\t\treturn ownedAndroidTun(device, w.iif), nil
\t}
\treturn device, nil""",
    ), (
        "func (w *platformInterfaceWrapper) MyInterfaceAddress() []netip.Addr {",
        """// VpnOwnerIsolationContract covers TUN and native listener ingress.
func VpnOwnerIsolationContract() int32 { return 2 }

func (w *platformInterfaceWrapper) RequireVpnOwnerIsolation() (bool, error) {
\treturn w.iif.RequireVpnOwnerIsolation()
}

func (w *platformInterfaceWrapper) MyInterfaceAddress() []netip.Addr {""",
    ), (
        "\treturn w.myTunAddress",
        "\tw.ownerTunAddressAccess.RLock()\n\tdefer w.ownerTunAddressAccess.RUnlock()\n\treturn w.myTunAddress",
    ))
    patch(f"{listener}/listener.go", (
        "\tctx                      context.Context",
        "\townerPolicy              *mcrfOwnerPolicy\n\townerAuthenticatedLAN   bool\n\tctx                      context.Context",
    ), (
        "\tContext                  context.Context",
        "\tOwnerAuthenticatedLAN    bool\n\tContext                  context.Context",
    ), (
        "\t\tctx:                      options.Context,",
        "\t\townerAuthenticatedLAN:    options.OwnerAuthenticatedLAN,\n\t\tctx:                      options.Context,",
    ), (
        "func (l *Listener) Start() error {",
        """func (l *Listener) Start() error {
\townerPolicy, err := newMcrfOwnerPolicy(l.ctx, l.ownerAuthenticatedLAN)
\tif err != nil {
\t\treturn err
\t}
\tl.ownerPolicy = ownerPolicy""",
    ))
    patch(f"{listener}/listener_tcp.go", (
        "\t\tctx := log.ContextWithNewID(l.ctx)",
        """\t\tctx, allowed := l.ownerPolicy.TCP(log.ContextWithNewID(l.ctx), metadata.Source, metadata.OriginDestination)
\t\tif !allowed {
\t\t\tconn.Close()
\t\t\tcontinue
\t\t}""",
    ))
    patch(f"{listener}/listener_udp.go", (
        "\tbindAddr := M.SocksaddrFrom(l.listenOptions.Listen.Build(netip.AddrFrom4([4]byte{127, 0, 0, 1})), l.listenOptions.ListenPort)",
        """\tbindAddr := M.SocksaddrFrom(l.listenOptions.Listen.Build(netip.AddrFrom4([4]byte{127, 0, 0, 1})), l.listenOptions.ListenPort)
\tif l.ownerPolicy != nil && bindAddr.Addr.IsUnspecified() {
\t\treturn nil, errProxyUDPLocalAddressRequired
\t}""",
    ), (
        "\tl.udpAddr = bindAddr",
        """\tl.udpAddr = bindAddr
\tif l.ownerPolicy != nil {
\t\tl.udpAddr = M.SocksaddrFromNet(udpConn.LocalAddr()).Unwrap()
\t}""",
    ), (
        "\t\treturn listenConfig.ListenPacket(ctx, network, address)",
        """\t\tnetwork, address, err := l.ownerPolicy.associationBind(ctx, network, address)
\t\tif err != nil {
\t\t\treturn nil, err
\t\t}
\t\tconn, err := listenConfig.ListenPacket(ctx, network, address)
\t\tif err != nil {
\t\t\treturn nil, err
\t\t}
\t\treturn l.ownerPolicy.associate(ctx, conn)""",
    ), (
        "\tif l.oobPacketHandler == nil {",
        "\tif l.ownerPolicy == nil && l.oobPacketHandler == nil {",
    ), *tuple((
        f"\t\t\tbuffer.Truncate(n)\n\t\t\tl.{handler}.NewPacket(",
        f"""\t\t\tsource := M.SocksaddrFromNetIP(addr).Unwrap()
\t\t\tif !l.ownerPolicy.UDP(source, l.udpAddr) {{
\t\t\t\tif l.threadUnsafePacketWriter {{
\t\t\t\t\tbuffer.Release()
\t\t\t\t}}
\t\t\t\tcontinue
\t\t\t}}
\t\t\tbuffer.Truncate(n)
\t\t\tl.{handler}.NewPacket(""",
    ) for handler in ("oobPacketHandler", "packetHandler")))
    patch("protocol/mixed/inbound.go", (
        "\tinbound.listener = listener.New(listener.Options{",
        """\tauthenticatedLAN := tag == "mcrf-lan" && len(options.Users) > 0
\tfor _, user := range options.Users {
\t\tauthenticatedLAN = authenticatedLAN && user.Username != "" && user.Password != ""
\t}
\tinbound.listener = listener.New(listener.Options{
\t\tOwnerAuthenticatedLAN: authenticatedLAN,""",
    ))
    for name in ("mcrf_owner_tun", "mcrf_owner_gvisor"):
        sources[f"{libbox}/{name}.go"] = (here / f"{name}.go.in").read_text(encoding="utf-8")
    sources[f"{libbox}/mcrf_owner_gvisor_stub.go"] = """//go:build !with_gvisor

package libbox

import tun "github.com/sagernet/sing-tun"

func ownedAndroidGVisorTun(tun.Tun, *ownerGate) tun.Tun { return nil }
"""
    flow_source = here.parent / "transports/wg-backend/owner_gate.go"
    contents = flow_source.read_text(encoding="utf-8")
    if contents.count("package wgbackend") != 1:
        raise ValueError("Shared owner tuple parser changed")
    sources[f"{libbox}/mcrf_owner_flow.go"] = contents.replace("package wgbackend", "package libbox")
    sources[f"{listener}/mcrf_owner_listener.go"] = (here / "mcrf_owner_listener.go.in").read_text(encoding="utf-8")
    sources[f"{listener}/mcrf_owner_listener_test.go"] = (here / "mcrf_owner_listener_test.go.in").read_text(encoding="utf-8")

    hashes = {}
    for name, contents in sources.items():
        data = contents.encode("utf-8")
        destination = stage / "owner-sources" / name
        if args.verify:
            if data != destination.read_bytes():
                raise ValueError(f"Owner admission source changed: {name}")
        else:
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(data)
        hashes[name] = hashlib.sha256(data).hexdigest()

    print(json.dumps(hashes, sort_keys=True))


if __name__ == "__main__":
    main()
