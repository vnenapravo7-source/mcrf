# Required canonical Request update (main/Steer-owned SPI)

The coordinator explicitly requires PROVIDED/DEFERRED acquisition, not a generic
fd validation relaxation. The canonical SPI owner has now implemented this model,
including NONE proxy, exact input fingerprint and host-verified capability SHA.
Adapter uses the actual shared fields/validator, not a fork. The rules below remain
the host IPC obligations; native/PFD/runtime acceptance is still pending.

Add `Engine.TunAcquisition { PROVIDED, DEFERRED, NONE }` and an immutable
`Request.tunAcquisition`. NONE applies only to validated PROXY mode. Legacy VPN
constructors forward PROVIDED, preserving fd>=0. Add an explicit constructor
parameter for acquisition and a successful Validation bound to EXACT profile,
settings, mode and acquisition by the existing length-delimited SHA256 fingerprint.

* PROVIDED VPN: fd>=0; Steer supports only this. No new TUN creation by module.
* DEFERRED VPN: fd exactly -1, vpnNetworkHandle initially0; validation.supported,
  modeVPN, acquisitionDEFERRED and matching fingerprint required. Only native
  complete-config checked sing-box validates this. Module capabilities explicitly
  advertise deferred-native-options. Host verifies signed kind/API/capability and
  matching preflight descriptor, not a module string alone, before constructing it.
* NONE PROXY: fd exactly -1, vpnNetworkHandle0, modePROXY and complete validated
  proxy fingerprint required. Any native openTun is rejected before host callback.
* Reject all other combinations, null policy, changed profile/settings, failed or
  different-mode validation, supplied placeholder fd0 in DEFERRED, and DEFERRED
  selection for Steer. No silent PROXY/VPN fallback.

Add `Validation.deferredVpnValidated(profile,settings)` for the native-checked
sing-box adapter. `proxyValidated` sets NONE. General successful VPN Validation
sets PROVIDED. Failed Validation cannot authorize either missing-fd mode.
Request constructors belong to canonical SPI owner; do not ship a fork. Owned
SingBoxDeferredRequestChecks executes those REAL constructors:14 positive/negative
cases, including placeholderfd0/changedinputs/capabilitymismatch/failedvalidation.

Sing-box validate returns deferredVpnValidated after full native
checkConfig; run requires explicit DEFERRED/fd-1 for VPN and NONE/fd-1 for proxy.
Native openTun sends typed authoritative Platform.TunPlan. Host validates it and
creates exactly one main VpnService TUN, retains main PFD and transfers duplicate
through Binder. Return numeric fd only after worker unmarshalling and generation
ACK. Worker then permits native to use its own duplicate. Failed/timeout/revoked/
stale ACK must close all OWNED copies and fail startup; no second establish/retry
within this worker. SingBoxCallbackGuard already permits only one TUN callback
and rejects STOP racing ACK. Host must also enforce the one-establish transaction.

Host cleanup ACK waits for native close, worker-owned duplicates closure, worker
death and host TUN closure. Main retains sole VpnService, consent and notification.
The primary choice/install flow and old profile storage must remain intact.

Factory is explicit for both entries: public constructor(Context), invoked once
with applicationContext in fresh worker. No no-arg/overload guesses or fallback.
ClientPrimaryCoreLoader.instantiate implements this rule; Steer owner must provide
the agreed constructor. JNI go.Seq uses module classloader and its native path.

This source contract resolves callback ordering without guessing native split
routes. Current source compile/tests are not native startup/IPC acceptance. Exact
pinned libbox experimental/libbox/service.go79-87 DUPLICATES the callback fd before
tun.New; service_other.go uses syscall.Dup. Adapter returns the retained worker-local
borrowed descriptor, not an extra detached duplicate (that would leak). Native
closes its own duplicate; host/worker close only their own PFD copies during teardown.
CLOEXEC on libbox's new native dup is not proved by syscall.Dup alone; preserve this
source finding in the native gate rather than claiming CLOEXEC or patching the AAR.
Actual dup/close/revoke/death evidence remains main gate.
