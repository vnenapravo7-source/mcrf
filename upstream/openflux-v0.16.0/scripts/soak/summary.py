#!/usr/bin/env python3
"""Summary of a soak run: per transport, over the last N seconds, the median / 10th percentile / maximum speed, the share of
seconds with no data, the total and the error counters.   python summary.py [workdir ...] [--last 120]"""
import argparse, collections, csv, os

ap = argparse.ArgumentParser()
ap.add_argument("dirs", nargs="*", default=[os.path.join(os.environ.get("TEMP", "/tmp"), "openflux-soak")])
ap.add_argument("--last", type=int, default=120)
a = ap.parse_args()
print("%-14s %5s %7s %7s %7s %6s %8s %5s %4s %4s %5s %4s %5s" % (
    "transport", "up s", "median", "p10", "max", "zero%", "total MB", "dlErr", "429", "4004", "wserr", "rot", "rst"))
for d in a.dirs:
    path = os.path.join(d, "soak.csv")
    if not os.path.exists(path):
        print("no", path)
        continue
    by = collections.defaultdict(list)
    for r in csv.DictReader(open(path)):
        by[r["transport"]].append(r)
    for t, rs in by.items():
        recent = rs[-a.last:]
        mb = sorted(int(r["bytes_s"]) / 1e6 for r in recent)
        last = rs[-1]
        zero = 100.0 * sum(1 for x in mb if x < 0.01) / len(mb)
        print("%-14s %5d %7.2f %7.2f %7.2f %5.0f%% %8s %5s %4s %4s %5s %4s %5s" % (
            t, len(rs), mb[len(mb) // 2], mb[len(mb) // 10], mb[-1], zero, last["total_mb"], last["dl_errors"],
            last["429"], last["4004"], last["wserr"], last["rot"], last["restarts"]))
