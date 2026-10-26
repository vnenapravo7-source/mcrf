# FluxFast: the UDP carrier

FluxFast is a native carrier that moves the tunnel's framed records over
UDP between a client and a self-hosted exit. It is the datagram twin of
[`direct`](transports.md): the same `host:port` address, the same role
split, the same Session - only the socket under it is UDP instead of TCP.

Why it exists: `direct` runs the whole tunnel inside one TCP connection.
On a lossy or moving path, TCP's retransmission and the tunnel's inner TCP
work against each other (TCP-in-TCP meltdown): one lost segment stalls
both. FluxFast leaves the link lossy on purpose and lets the inner TCP do
the recovery it already does. It also handles a client whose address
changes (NAT rebinding, Wi-Fi to LTE) without a new listener and without
dropping the exit.

## Quick start

Exit:

```bash
./openflux --role=exit --mode=l3 --transport=fluxfast \
    --direct-listen=0.0.0.0:8445 --encryption-key-file=secret.txt
```

Client, as a single classic transport:

```bash
./openflux --role=client --transport=fluxfast \
    --direct-dial=203.0.113.7:8445 --encryption-key-file=secret.txt
```

Client, in a Session with the document carriers as a fallback:

```bash
./openflux --role=client --transports=fluxfast:100,yandex:50 \
    --direct-dial=203.0.113.7:8445 --url=$DOCUMENT \
    --encryption-key-file=secret.txt
```

The address flags are the ones `direct` uses: `--direct-dial` on the
client, `--direct-listen` on the exit. A `.conf` section spells them
`Dial` and `Listen`:

```ini
[Transport "fluxfast"]
Type = fluxfast
Dial = 203.0.113.7:8445
Listen = 0.0.0.0:8445
# Chunk = 1180
# MTU = 1280
# Prefer = no
```

FluxFast has no document, so it contributes no KDF context: like `direct`
and `oneme` it leaves the session context at its fallback (`http://#`
unless `--session-context` says otherwise). The core's mobile layer treats
it exactly like `direct`: a profile keeps one address, and both ends need
the same `--encryption-key-file`. The carrier itself neither encrypts nor
authenticates anything; the AES-256-GCM wrapper (classic mode) or the
Session (see [sessions.md](sessions.md)) is what protects the traffic.

A share link carries FluxFast inside a negotiated Session, like `direct`:
the link holds the exit's address and the client connects to it. One
socket carries one active client at a time.

## Wire format

Every datagram starts with a 20-byte header, big-endian:

| Offset | Size | Field |
|-------:|-----:|-------|
| 0 | 1 | magic `0xF1` |
| 1 | 1 | magic `0xFA` |
| 2 | 1 | version (high nibble, `1`) \| type (low nibble) |
| 3 | 1 | flags: bit 0 - the record is fragmented; all other bits must be 0 |
| 4 | 8 | connection id |
| 12 | 4 | record sequence number |
| 16 | 1 | fragment index (from 0) |
| 17 | 1 | fragment count (1..255) |
| 18 | 2 | payload length |
| 20 | N | payload |

Types:

| Value | Type | Payload |
|------:|------|---------|
| 0 | `DATA` | a piece of one framed tunnel record |
| 1 | `PING` | empty |
| 2 | `PONG` | empty |
| 3 | `PATH_REQ` | 8-byte challenge token |
| 4 | `PATH_RES` | the same 8-byte token |

A datagram is dropped unread unless all of the magic, version, type,
flags, length and fragment fields agree with each other: the length must
equal the rest of the datagram and stay under 1200 bytes; `1 <= total <=
255`, `frag < total`; a single-fragment datagram must have flag 0 and
index 0; a multi-fragment one must have flag 1. `PING`/`PONG` must carry
no payload, `PATH_REQ`/`PATH_RES` exactly 8 bytes.

## Records and fragmentation

The carrier is handed already-framed records (the batched codec's frames,
AES-GCM envelopes included) and puts one record into one or more
datagrams: `Chunk` payload bytes each, up to 255 fragments. The default
`Chunk` is 1180, so a datagram is 1200 bytes and fits the 1280-byte
carrier MTU. A record larger than `MaxRecordBytes` (64 KiB by default) is
refused with `ErrRecordTooLarge`; a record whose fragments would exceed
255 is refused the same way.

The receiver reassembles by `(connection id, sequence number)`. Fragments
may arrive out of order; duplicates and repeated pieces are ignored. An
incomplete record is dropped after `ReassemblyTimeout` (3 s), and the
reassembly table is capped at 256 records / 8 MiB, so a flood of first
fragments cannot grow memory without bound. A successful path change
clears the table.

## Peer, paths and roaming

One socket is one exit port for one active peer. The exit learns the
client from the first valid datagram it accepts; the client dials a fixed
address.

The active peer may change its address (NAT rebinding) or its connection
id (a restarted client) - but never silently:

- A datagram for the active connection id from a different address, or a
  datagram with a different connection id after the active peer has been
  silent for `QuietTakeover` (25 s), does **not** move the peer.
- Instead the carrier sends `PATH_REQ` with a fresh random 8-byte token to
  that address, repeating it every 100 ms for 3 s.
- Only a `PATH_RES` from that address echoing the token, with the expected
  connection id, moves the peer and resets reassembly. Data resumes after
  the peer re-sends or the inner TCP retransmits.

A spoofed source address therefore cannot steal a busy port, and a
reconnecting client is served within seconds instead of waiting for the
old NAT mapping to expire.

## Liveness and backpressure

- `PING` is sent when nothing was received for `KeepAliveInterval` (5 s);
  `PONG` answers it. Any valid datagram marks the peer alive.
- After `DeadAfter` (30 s) with no valid datagram, `IsConnected` turns
  false. The socket stays open: a peer that comes back is validated and
  served again.
- `Send` queues records up to `QueueBytes` (512 KiB). When the queue is
  full it fails immediately (the caller backs off); the carrier never
  blocks the tunnel on a slow socket.

## What FluxFast does not do

- **No retransmission and no congestion control.** Lost datagrams stay
  lost; the tunnel's inner TCP recovers them, exactly as it would recover
  loss on a lossy link. Inner UDP (QUIC, DNS) loses like it would on any
  UDP path. That is the point: the carrier does not fight the tunnel.
- **No encryption or authentication of its own.** Use
  `--encryption-key-file` (classic) or a Session. The connection id and
  the path token are routing state, not credentials.
- **No multiplexing.** One socket serves one active peer, like `direct`.
  For several concurrent clients, run one exit port per client (or put a
  document carrier in the same Session as the shared path).

## Tuning

| Field | Default | Meaning |
|-------|--------:|---------|
| `Chunk` | 1180 | payload bytes per datagram (20 + Chunk should stay under the path MTU) |
| `MTU` | 1280 | MTU the tunnel should use over this carrier |
| `QueueBytes` | 512 KiB | records waiting for the writer |
| `MaxRecordBytes` | 64 KiB | largest record accepted from the tunnel |
| `KeepAliveInterval` | 5 s | how often a quiet peer is pinged |
| `DeadAfter` | 30 s | silence that turns `IsConnected` false |
| `ReassemblyTimeout` | 3 s | how long an incomplete record waits |
| `QuietTakeover` | 25 s | silence before a different connection id may take the port |
| `HandshakeTimeout` | 15 s | bound on DNS and the first socket setup in `Start` |

`Chunk` and `MTU` can be set per transport in a `.conf` (`Chunk`, `MTU`)
or through the registry's session params (`chunk`, `mtu`); the other
fields are available to embedders through `FluxFastConfig`.

Like `direct`, a live FluxFast carrier reports itself preferred
(`Preferred()`), so a Session picks it ahead of document carriers of the
same priority. `Prefer = no` (registry `prefer: false`) clears that, and
`OPENFLUX_FLUXFAST_FIRST=0` turns the preference off for every FluxFast
carrier (`1` forces it on).

## Tests

```bash
go test ./transport -run FluxFast
```

The tests cover the round trip, fragmentation and out-of-order/duplicate
pieces, reassembly timeout, malformed datagrams, the path challenge on an
address change, takeover by a new connection id, ping/pong connecting both
sides, oversized records, the carrier interfaces and address resolution.
