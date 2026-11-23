# Embedded Gooby built-in presets — source only

A focused port of CSQTTR's Apache2 embedded Gooby integration, pinned in
SOURCE.json. Original preset, TLS SNI fragmentation, TURN Allocate splitting,
SOCKS implementation and owned runtime lifetime are retained. The setup
strategies do not pace established tunnel payload. TCP write boundaries are
not guaranteed wire segmentation or proof of DPI bypass.

Only built-in current/gentle/extended presets are exported. ZIP rule archives,
SQLite and Lua custom scripts are outside this application setting and are not
compiled or accepted; a Custom strategy errors explicitly. TransformPlan was
extracted unchanged from the original Lua module. This avoids shipping unused
script/database runtimes while preserving the requested built-in strategies.

No MCRF core/config/UI activation yet. TLS certificate/SNI, cancellation,
parallel workers and all Android ABI core-integration checks remain required.
UDP behavior must remain unchanged. No upload improvement is claimed.
