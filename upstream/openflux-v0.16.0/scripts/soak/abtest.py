#!/usr/bin/env python3
"""Runs soak variants one after another (so they do not share a service's per-account limits or each other's bandwidth)
and prints a summary row for each.

    python abtest.py --extra mailru-variants.json --names mailru-i200,mailru-i100 --seconds 180
"""
import argparse, csv, os, subprocess, sys

here = os.path.dirname(os.path.abspath(__file__))
ap = argparse.ArgumentParser()
ap.add_argument("--names", required=True)
ap.add_argument("--extra")
ap.add_argument("--seconds", type=int, default=180)
ap.add_argument("--streams", type=int, default=1)
ap.add_argument("--base", default=os.path.join(os.environ.get("TEMP", "/tmp"), "openflux-ab"))
a = ap.parse_args()
rows = []
for i, name in enumerate(a.names.split(",")):
    work = os.path.join(a.base, name)
    cmd = [sys.executable, os.path.join(here, "soak.py"), "--transports", name, "--work", work,
           "--duration", str(a.seconds), "--port-base", str(21300 + i), "--streams", str(a.streams)]
    if a.extra:
        cmd += ["--extra", a.extra]
    print("== %s (%ds) ..." % (name, a.seconds), flush=True)
    subprocess.run(cmd, stdout=subprocess.DEVNULL)
    path = os.path.join(work, "soak.csv")
    if not os.path.exists(path):
        print("   no data")
        continue
    rs = list(csv.DictReader(open(path)))
    warm = rs[20:] or rs  # skip the first seconds: the carrier is still coming up
    mb = sorted(int(r["bytes_s"]) / 1e6 for r in warm)
    last = rs[-1]
    zero = 100.0 * sum(1 for x in mb if x < 0.01) / len(mb)
    row = (name, mb[len(mb) // 2], mb[len(mb) // 10], mb[-1], zero, last["total_mb"], last["dl_errors"],
           last["429"], last["4004"], last["wserr"])
    rows.append(row)
    print("   median %.2f  p10 %.2f  max %.2f MB/s  zero %.0f%%  total %s MB  dlErr %s  429 %s  4004 %s  wserr %s" % row[1:],
          flush=True)
print()
print("summary (median MB/s):")
for r in sorted(rows, key=lambda r: -r[1]):
    print("  %-16s %.2f   p10 %.2f  zero %.0f%%  4004 %s" % (r[0], r[1], r[2], r[4], r[8]))
