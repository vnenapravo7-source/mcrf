# WinDivert service name for MCRF Windows

Upstream: https://github.com/basil00/WinDivert
Pinned source: `1789526ecfb9ff5397c94f9f54c1a3dc2fb60440` (v2.2.2).
License: upstream LGPLv3 or GPLv2; retain its LICENSE and source with distribution.

`service-name.patch` changes only the SCM service and display name to the exact
human-requested `MRCF_WD`. The DLL filename, exports, device `\\.\WinDivert`,
installation mutex, event-log source and `WinDivert64.sys` filename remain upstream.
No kernel-driver byte is modified. The bundled signed SYS SHA-256 remains
`8da085332782708d8767bcace5327a6ec7283c17cfb85e40b03cd2323a90ddc2`.

Build the user-mode DLL with `build.ps1` using pristine pinned source and LLVM-MinGW.
No SYS build, test-signing, Secure Boot change or global driver stop is required.
As upstream, a compatible device already loaded by another app can be reused;
the new service name applies when MCRF installs the driver. Existing foreign or
legacy services are not deleted or stopped. This is not anti-cheat concealment.
Changing the internal device name would require matching DLL and SYS changes,
rebuilding the kernel driver and obtaining a new valid Windows signature.
