"""Encode the user's real isolated Android UI capture; no mockups or invented frames."""
import argparse
import hashlib
import json
from pathlib import Path
from PIL import Image

parser = argparse.ArgumentParser()
parser.add_argument("frames", type=Path)
parser.add_argument("output", type=Path)
args = parser.parse_args()
root = Path("D:/MCRF").resolve()
source = args.frames.resolve()
target = args.output.resolve()
if not source.is_relative_to(root) or not target.is_relative_to(root):
    raise SystemExit("All media must stay under D:/MCRF")
paths = sorted(source.glob("frame-*.png"))
if not 100 <= len(paths) <= 800:
    raise SystemExit("Incomplete or unexpectedly large real UI capture")
images = []
times = json.loads((source / "timings.json").read_text(encoding="utf-8"))
if len(times) != len(paths) or any(b <= a for a, b in zip(times, times[1:])):
    raise SystemExit("Invalid native capture timing")
durations = [max(20, round((b - a) / 10) * 10) for a, b in zip(times, times[1:])] + [1000]
size = None
for index, path in enumerate(paths):
    if path.name != f"frame-{index:04d}.png":
        raise SystemExit("Missing capture frame")
    with Image.open(path) as original:
        if size is None:
            size = original.size
        if original.size != size or size[0] != 360:
            raise SystemExit("Unexpected capture geometry")
        images.append(original.convert("RGB").quantize(colors=128, method=Image.Quantize.MEDIANCUT))
target.parent.mkdir(parents=True, exist_ok=True)
images[0].save(target, save_all=True, append_images=images[1:], duration=durations,
               loop=0, optimize=True, disposal=1)
with Image.open(target) as check:
    if not check.is_animated or check.n_frames < 20 or check.size != size:
        raise SystemExit("Animated output verification failed")
    frames = check.n_frames
    total = sum(check.seek(i) or check.info.get("duration", 0) for i in range(frames))
receipt = {"source": str(source), "output": str(target), "input_frames": len(paths),
           "encoded_frames": frames, "duration_ms": total, "size": size,
           "bytes": target.stat().st_size, "sha256": hashlib.sha256(target.read_bytes()).hexdigest(),
           "content": "Actual isolated demo UID views, native page fades and smooth scrolling; no synthetic transitions"}
target.with_suffix(".receipt.json").write_text(json.dumps(receipt, indent=2), encoding="utf-8")
print(json.dumps(receipt))
