# Windows core host

Build the pinned source with `./native/singbox-host/build.ps1`. It exports the
existing immutable upstream revision and required Go submodules into a separate
Windows-owned stage. Android/Linux sources are read-only inputs and are not edited.

Pass the produced `sing-box.exe` through `native/build.ps1 -CoreExecutable PATH`.
There is no fallback to the old console-based host. The Go binary uses the Windows
GUI subsystem and receives a private inherited stdin pipe. `STOP` or EOF follows
the normal `Box.Close()` path; no public control socket or console signal is used.

Build options preserve the original Windows feature tags and native dependencies.
Upstream core updates without private STOP support cannot replace this runtime.
