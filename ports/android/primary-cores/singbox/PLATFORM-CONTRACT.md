# Minimum host platform additions

Authoritative current contract is the existing SPI's parent-owned
`org.mcrf.engine.spi.v1.Platform`, supplied by `Engine.Host.platform()`.
The interim ClientPrimaryCorePlatform was deleted. Module imports the canonical
typed DTOs and never bundles a second SPI copy. Host implements the single shared
contract through its own IPC; no module-defined JSON callback interface remains.

Required methods from actual CorePlatform/McrfVpnService:

* openTun(generation, nativeOptionsJson) -> worker-local borrowed fd. Main validates
  numeric CIDRs/routes, MTU1280 IPv6 rules, native include/exclude package precedence,
  metering/blocking, HTTP proxy and DNS, builds the only TUN and transfers a duplicate
  PFD to worker. Return integer exists only in worker after Binder unmarshalling.
* interfacesJson -> array name/index/mtu/flags/type(wifi,cellular,other)/metered,
  addresses CIDRs and numeric dnsServers. Only physical networks; no VPN interfaces.
* connectionOwnerJson -> userId and packageNames, actual ConnectivityManager lookup.
* exchangeDns/cancelDns -> physical-network DnsResolver raw query, <=8s timeout,
  unique requestId, cancel and stale-generation/dead-peer rejection. No gomobile
  ExchangeContext crosses IPC. Worker reports error via errno and owns exactly-once
  completion; host must cancel underlying Android query and drain callbacks.
* start/closeInterfaceMonitor -> primitive name/index/metered callbacks. Main owns
  Android monitor and underlying network; worker converts to libbox listener.
* traffic -> actual native totals only, no guessed bytes. Existing prepareSocket
  remains synchronous protect+physical bind before connect, including proxy mode
  when a different host VPN exists. False always fails; no unprotected fallback.

Native typed Platform.TunPlan schema1: mtu,autoRoute,dnsEnabled,dnsServers,
inet4Address,inet6Address,inet4RouteAddress,inet6RouteAddress,
inet4RouteExcludeAddress,inet6RouteExcludeAddress,inet4RouteRange,inet6RouteRange,
includePackage,excludePackage,httpProxy {enabled,server,port,bypass}.
Each prefix is a numeric CIDR string; lists preserve native order. API29-32 uses native
routeRange; API33+ includes/defaults only for present family and excludeRoute;
never allowFamily(AF_INET6) when absent. Loopback exclusions match current
ClientTunPlatform policy. AutoRoute=false creates no invented routes or DNS.

Native authoritative TunOptions are available in openTun during native startup;
preflight tunPlanJson returns an explicit deferred-native-options descriptor.
Host must recognize this mode and await callback, never infer final routes from
raw config or pre-establish a second TUN. Proxy-only requests reject any openTun.

Runtime envelope schema1 contains config (complete final sing-box object), mode
(vpn/proxy) and metered (host policy hint). Unknown envelope fields fail. Native
checkConfig validates COMPLETE config without stripping unknown options. All
profile/default/optional-worker substitutions occur before freezing it in main.
No encrypted store access, silent fallback or engine selection inside module.

Entry bootstrap SingBoxEngine(Context) only in isolated host worker;
Context is Android applicationContext, initializes go.Seq for that module loader.
Engine.Host.platform() supplies parent platform contract. Constructor SPI
factory and deferred plan mode are explicit main integration hooks pending approval.
CommandServer/control clients, TunOptions conversion, go.Seq and all Libbox types
remain module-private. Shell/bridge/SSHAgent/system-proxy-toggle/nativecrash are
explicit unsupported, matching current Android CorePlatform/service behavior.
Neighbor/cache/notification/WIFI are same current no-op/empty behavior, not expanded
capabilities. Native stats availability false must not emit fabricated totals.

Main owns actual IPC implementation, timeout/death handling, usage lease, service
permission/revoke/foreground state and cleanup ACK. Native shutdown success alone
does not close host TUN or prove worker death; ACK only after all resources close.

Canonical Engine now supports mode-bound proxy validation with input fingerprint
and Request.PROXY/NONE fd=-1. Explicit DEFERRED VPN also uses fd=-1 only with exact
native-validated input/acquisition and host-verified capability SHA. Steer uses
PROVIDED; no fake fd or unvalidated mode relaxation. See DEFERRED-TUN.md for the
current accepted canonical Request and one host TUN/PFD transaction contract.

Stats use actual Platform.traffic(tx,rx) callbacks. Engine.Counters.unavailable()
marks the incomplete legacy aggregate instead of fabricated zero stats. Typed
Engine.Stats exposes actual TX/RX with unavailable blocked attempts, STATS_PARTIAL.
Generation-gated control supports STOP request and FRESH_WORKER_REQUIRED reload.
STOP reply is never teardown ACK.

DNS completion is exactly once; cancellation already reported during onCancel
registration prevents exchange start. Host must retain a bounded cancellation
tombstone when cancel IPC beats exchange registration (generation,requestId),
cancel actual DnsResolver query and drain callbacks. Module checks alone cannot
close that cross-process race. Native GetDNSServerAddress is evaluated only when
autoRoute && dnsEnabled, matching actual host Builder applicability.
