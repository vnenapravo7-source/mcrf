"""Patch all pinned Rust UDP dispatcher variants after their native overlays."""
from pathlib import Path


def replace(source, before, after):
    if source.count(before) != 1:
        raise ValueError("CSQTT private UDP dispatcher anchor changed")
    return source.replace(before, after)


def apply(crate, generation):
    encoder = Path(__file__).with_name("native-packet") / "mcrf_private_udp.rs"
    (crate / encoder.name).write_bytes(encoder.read_bytes())
    main = crate / "main.rs"
    with main.open("a", encoding="utf-8", newline="\n") as file:
        file.write("\nmod mcrf_private_udp;\n")
    target = crate / "dispatcher.rs"
    source = target.read_text(encoding="utf-8")
    source = replace(source, "let socket = bind_udp(listen).await?;", """crate::mcrf_private_udp::configure()?;
            if crate::mcrf_private_udp::active()
                && !listen.parse::<std::net::SocketAddr>().is_ok_and(|a| a.ip().is_loopback())
            {
                anyhow::bail!("CSQTT private UDP requires a loopback address");
            }
            let socket = bind_udp(listen).await?;""")
    if generation == "g20":
        source = replace(source, """                        *client.write().await = Some(address);
                        if packet.set_read_len(length).is_err() {
                            continue;
                        }""", """                        if packet.set_read_len(length).is_err() || !crate::mcrf_private_udp::accept(&mut packet) {
                            continue;
                        }
                        *client.write().await = Some(address);""")
        source = replace(source, "stats.total_bytes_up.fetch_add(length as i64, Ordering::Relaxed);",
                         "stats.total_bytes_up.fetch_add(packet.len() as i64, Ordering::Relaxed);")
        source = replace(source, "socket.send_to(packet.as_slice(), address).await",
                         "crate::mcrf_private_udp::send(socket, packet.as_slice(), address).await")
    elif generation in ("g215", "g219"):
        source = replace(source, "for (index, packet) in receive_batch.drain(..received).enumerate() {",
                         """for (index, mut packet) in receive_batch.drain(..received).enumerate() {
                        if !crate::mcrf_private_udp::accept(&mut packet) { continue; }""")
        source = replace(source, """            for (index, packet) in packets.iter().enumerate() {
                datagrams[index] = packet.as_slice();
            }""", """            let mut sealed = Vec::new();
            if crate::mcrf_private_udp::active() {
                sealed.reserve(packets.len());
                for packet in packets {
                    let Ok(frame) = crate::mcrf_private_udp::Frame::down(packet.as_slice()) else { return };
                    sealed.push(frame);
                }
                for (chunk, frame) in datagrams.iter_mut().zip(&sealed) {
                    *chunk = frame.as_slice();
                }
            } else {
                for (chunk, packet) in datagrams.iter_mut().zip(packets) {
                    *chunk = packet.as_slice();
                }
            }""")
    elif generation in ("g251", "g253"):
        source = replace(source, """                        burst += 1;
                        if packet.set_read_len(length).is_err() {""", """                        burst += 1;
                        if packet.set_read_len(length).is_err() || !crate::mcrf_private_udp::accept(&mut packet) {""")
        source = replace(source, "match socket.try_send_to(next_packet.as_slice(), address) {",
                         "match crate::mcrf_private_udp::try_send(&socket, next_packet.as_slice(), address) {")
        before = "match socket.try_send_to(packet.as_slice(), address) {"
        if source.count(before) != 2:
            raise ValueError("CSQTT private UDP modern send guards changed")
        source = source.replace(before, "match crate::mcrf_private_udp::try_send(socket, packet.as_slice(), address) {")
        # The first call is in write_udp, whose socket is owned by Arc.
        first = "match crate::mcrf_private_udp::try_send(socket, packet.as_slice(), address) {"
        source = source.replace(first, first.replace("(socket,", "(&socket,"), 1)
    else:
        raise ValueError("Unknown CSQTT UDP dispatcher generation")
    fixture = encoder.with_name("mcrf_private_udp_dispatcher_test.rs.in").read_text(encoding="utf-8")
    arguments = '"127.0.0.1:0", None, pool.clone(), stats.clone(), cancel'
    start = (f"Dispatcher::start_with_domain_routing({arguments}, DomainRoutingConfig::default(), 27)"
             if generation in ("g251", "g253") else f"Dispatcher::start({arguments})")
    source += "\n" + fixture.replace("@START@", start + ".await.unwrap()")
    target.write_text(source, encoding="utf-8", newline="\n")
