"""Prepare legacy adapters for owned direct-TUN fixtures without advertising support."""

from pathlib import Path
import shutil

from prepare_csqtt_direct_tun import once


HERE = Path(__file__).resolve().parent
GENERATIONS = ("2.0", "2.1.5", "2.1.9")
REGISTER = "        let device = match AsyncFd::new(file) {"


def transform(generation, main, tun, dispatcher):
    if generation not in GENERATIONS:
        raise ValueError("Unsupported legacy direct TUN generation")
    main = once(main, "mod tun;", "mod tun;\nmod mcrf_direct_tun;")
    entry = (
        "    let arguments = Arguments::parse_from(normalized_arguments());"
        if generation == "2.0"
        else "async fn run(arguments: Arguments) -> Result<()> {"
    )
    main = once(main, entry, entry + '''
    if std::env::var_os("MCRF_DIRECT_TUN_GENERATION").is_some() {
        mcrf_direct_tun::configure(&arguments.tun_uds)?;
    }''')

    if generation == "2.1.9":
        tun, dispatcher = replaceable_tun(tun, dispatcher)
    else:
        tun, dispatcher = one_shot_tun(tun, dispatcher)
    return main, tun, dispatcher


def one_shot_tun(tun, dispatcher):
    receive = "pub async fn receive_fd(name: String, cancel: CancellationToken) -> Result<File> {"
    tun = once(tun, receive, receive + '''
    #[cfg(any(target_os = "android", target_os = "linux"))]
    if crate::mcrf_direct_tun::active() {
        use nix::sys::socket::{AddressFamily, Backlog, SockFlag, SockType, UnixAddr, bind, listen, socket};
        use std::os::fd::AsRawFd;
        let listener = socket(AddressFamily::Unix, SockType::Stream,
            SockFlag::SOCK_CLOEXEC | SockFlag::SOCK_NONBLOCK, None)?;
        bind(listener.as_raw_fd(), &UnixAddr::new_abstract(name.as_bytes())?)?;
        listen(&listener, Backlog::new(1)?)?;
        let listener = tokio::io::unix::AsyncFd::new(listener)?;
        return crate::mcrf_direct_tun::receive(&listener, &cancel).await;
    }''')
    wait = "                    let result = tun::receive_fd(name.clone(), io_dispatcher.cancel.clone()).await;"
    # Keep the guard in the same task through receive, registration and shutdown.
    dispatcher = once(dispatcher, wait, '''                    let direct = crate::mcrf_direct_tun::active();
                    let _direct_guard = direct.then(crate::mcrf_direct_tun::guard);
''' + wait)
    retry = "                            tokio::time::sleep(Duration::from_millis(100)).await;\n                            continue;"
    dispatcher = once(dispatcher, retry, '''                            if direct {
                                io_dispatcher.cancel.cancel();
                                return;
                            }
''' + retry)
    suspend = "                    return_rx.suspend();\n                }"
    dispatcher = once(dispatcher, suspend, suspend.replace("\n                }", '''
                    if direct {
                        io_dispatcher.cancel.cancel();
                        return;
                    }
                }'''))
    select = "        tokio::select! {\n            _ = self.cancel.cancelled() => {}"
    dispatcher = once(dispatcher, select, '''        if crate::mcrf_direct_tun::active()
            && (self.cancel.is_cancelled() || crate::mcrf_direct_tun::adopted().is_err()) {
            return;
        }
''' + select)
    return tun, dispatcher


def replaceable_tun(tun, dispatcher):
    receive = "    pub async fn receive(&self, cancel: &CancellationToken) -> Result<File> {"
    tun = once(tun, receive, receive + '''
        if crate::mcrf_direct_tun::active() {
            return crate::mcrf_direct_tun::receive(&self.listener, cancel).await;
        }''')
    receiver_task = 'spawn_critical("TUN FD receiver", receive_cancel.clone(), async move {'
    dispatcher = once(dispatcher, receiver_task, receiver_task + '''
                    let _direct_guard = crate::mcrf_direct_tun::active().then(crate::mcrf_direct_tun::guard);''')
    retry = "                                tokio::time::sleep(Duration::from_millis(100)).await;\n                                continue;"
    dispatcher = once(dispatcher, retry, '''                                if crate::mcrf_direct_tun::active() {
                                    receive_cancel.cancel();
                                    return;
                                }
''' + retry)
    handoff = "                        if fd_tx.send(file).await.is_err() {"
    dispatcher = once(dispatcher, handoff, '''                        if crate::mcrf_direct_tun::active() {
                            if crate::mcrf_direct_tun::handoff_once(receiver, file, &fd_tx, &receive_cancel).await.is_err() {
                                receive_cancel.cancel();
                            }
                            return;
                        }
''' + handoff)
    dispatcher = once(dispatcher, "        let mut file = initial_file;", '''        let direct = crate::mcrf_direct_tun::active();
        let _direct_guard = direct.then(crate::mcrf_direct_tun::guard);
        let mut file = initial_file;''')
    dispatcher = once(dispatcher, "            let received = tokio::select! {", '''            if direct && (self.cancel.is_cancelled() || crate::mcrf_direct_tun::adopted().is_err()) {
                self.cancel.cancel();
                return;
            }
            let received = tokio::select! {''')
    replacement = "\n                replacement = replacements.recv() => replacement,"
    dispatcher = once(dispatcher, replacement, "\n                replacement = replacements.recv(), if !direct => replacement,")
    dispatcher = once(dispatcher, "            let next = match received {", '''            if direct {
                self.cancel.cancel();
                return;
            }
            let next = match received {''')
    registration_error = '''                    crate::log_error!("[ОШИБКА] Не удалось зарегистрировать TUN FD: {error}");
                    return;'''
    dispatcher = once(dispatcher, registration_error, registration_error.replace("                    return;", "                    if direct { self.cancel.cancel(); }\n                    return;"))
    return tun, dispatcher


def apply(root, generation):
    """Apply only to a disposable, unmodified staged adapter crate."""
    root = Path(root)
    paths = [root / name for name in ("main.rs", "tun.rs", "dispatcher.rs")]
    outputs = [root / name for name in ("mcrf_direct_tun.rs", "mcrf_direct_tun_receive.rs")]
    if any(path.exists() for path in outputs):
        raise ValueError("Legacy direct TUN boundary already applied")
    transformed = transform(generation, *(path.read_text(encoding="utf-8") for path in paths))
    for path, source in zip(paths, transformed):
        path.write_text(source, encoding="utf-8", newline="\n")
    for path in outputs:
        shutil.copyfile(HERE / path.name, path)
