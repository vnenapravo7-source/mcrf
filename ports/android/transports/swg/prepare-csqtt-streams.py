"""Add exact owner events to fresh pinned legacy/Rebirth source, never a binary."""
import pathlib
import re
import sys


def replace_once(text, old, new):
    if text.count(old) != 1:
        raise ValueError("CSQTT stream owner anchor changed")
    return text.replace(old, new)


def transform(main, session):
    main = replace_once(main, "mod mcrf_captcha_waiters;\n",
                        "mod mcrf_captcha_waiters;\nmod mcrf_streams;\n")
    session = replace_once(session, "struct ActiveConnection {\n",
                           "struct ActiveConnection {\n    _mcrf_stream: crate::mcrf_streams::Owner,\n")
    session = replace_once(session, "Self { stats, events }",
                           "Self { stats, events, _mcrf_stream: crate::mcrf_streams::Owner::new() }")
    # Refuse a different upstream lifecycle rather than deriving counts from
    # READY messages or an assumed maximum worker count.
    for anchor in ("impl Drop for ActiveConnection {", "stats.active_connections.fetch_add(1, Ordering::Relaxed);",
                   "self.stats.active_connections.fetch_sub(1, Ordering::AcqRel)"):
        if session.count(anchor) != 1:
            raise ValueError("CSQTT active owner lifecycle changed")
    return main, session


def adapt(root):
    root = pathlib.Path(root).absolute()
    if not re.fullmatch(r"D:/MCRF/Builds/Android/csqtt-(?:legacy-captcha|historical)-[0-9a-f]{32}/source", root.as_posix()):
        raise ValueError("Fresh owned CSQTT producer source required")
    for path in (root, *root.parents):
        if path.is_symlink() or path.is_junction():
            raise ValueError("Redirected CSQTT producer source refused")
    paths = [root / "rust-client/main.rs", root / "rust-client/session.rs", root / "rust-client/mcrf_streams.rs"]
    if paths[2].exists() or any(p.is_symlink() or p.is_junction() for p in (paths[0].parent, *paths)):
        raise ValueError("Stream overlay already exists or is redirected")
    main, session = transform(*(p.read_text(encoding="utf-8") for p in paths[:2]))
    helper = pathlib.Path(__file__).with_name("mcrf_streams.rs").read_bytes()
    paths[0].write_text(main, encoding="utf-8", newline="\n")
    paths[1].write_text(session, encoding="utf-8", newline="\n")
    paths[2].write_bytes(helper)


if __name__ == "__main__":
    adapt(sys.argv[1])
