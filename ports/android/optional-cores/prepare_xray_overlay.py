"""Prepare the pinned Xray fork in a private build directory."""

import hashlib
import shutil
import sys
from pathlib import Path


source, destination = (Path(value).resolve() for value in sys.argv[1:])
root = Path(__file__).resolve().parent
if root / "build" not in destination.parents or destination.exists():
    raise SystemExit("Xray overlay requires a fresh private build directory")

relative = Path("transport/internet/system_dialer.go")
original = (source / relative).read_bytes()
if hashlib.sha256(original).hexdigest() != "dc43755d66b31edf36465f7ecd6f5a4701da53cdf0fc57596b421741161e05c9":
    raise SystemExit("Pinned Xray dialer source differs")

log = b'errors.LogInfoInner(ctx, err, "failed to apply external controller")'
if original.count(log) != 2:
    raise SystemExit("Pinned Xray socket controls differ")
patched = original.replace(log, b'return errors.New("failed to apply external controller").Base(err)')

shutil.copytree(source, destination)
target = destination / relative
target.chmod(0o600)
target.write_bytes(patched)
shutil.copyfile(root / "patches/xray-controller_test.go.txt", target.with_name("mcrf_controller_test.go"))
print(hashlib.sha256(patched).hexdigest())
