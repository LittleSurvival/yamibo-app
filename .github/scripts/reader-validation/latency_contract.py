"""Reader T0 -> correct rendered content T1 -> visible gesture response T2. Times in ms."""
import argparse
import csv
import math
from pathlib import Path
from statistics import median

def percentile95(dataset):
    return sorted(dataset)[math.ceil(.95 * len(dataset)) - 1]

def summarize(rows, count):
    errors = []
    if len(rows) != count or sorted(int(r["round"]) for r in rows) != list(range(1,count+1)):
        errors.append("missing or duplicate rounds")
    ready, interactive, gesture, frames = [], [], [], []
    for row in rows:
        if row["status"] != "OK":
            errors.append(f"round {row['round']} {row['status']}")
            continue
        if row["expected_marker"] != row["rendered_marker"] or not row["expected_marker"]:
            errors.append(f"round {row['round']} wrong or missing visible content")
            continue
        t0, t1, drag, t2 = (float(row[k]) for k in ("input_ms","present_ms","gesture_ms","response_ms"))
        if not all(math.isfinite(x) for x in (t0,t1,drag,t2)) or not t0 <= t1 <= drag <= t2:
            errors.append("invalid monotonic timestamps")
            continue
        ready.append(t1-t0)
        interactive.append(t2-t1)
        gesture.append(t2-drag)
        sample_frames = [float(v) for v in row["active_frames_ms"].split() if v]
        if not sample_frames or any(not math.isfinite(v) or v < 0 for v in sample_frames):
            errors.append("missing or invalid presented frames")
        frames.extend(sample_frames)
    if len(ready) != count or not frames:
        return {}, errors or ["incomplete samples"]
    return dict(p50=median(ready),p95=percentile95(ready),maximum=max(ready),
                interactive95=percentile95(interactive),gesture95=percentile95(gesture),
                frame95=percentile95(frames),frame_max=max(frames)), errors

def compare(baseline, candidate, workload):
    problems = []
    limits = {"warm-local": (350,650), "process-cold": (650,1100), "cold-network": (None,2200)}
    median_limit, tail_limit = limits[workload]
    if median_limit is not None and candidate["p50"] > median_limit:
        problems.append("absolute median budget exceeded")
    if candidate["p95"] > tail_limit:
        problems.append("absolute p95 budget exceeded")
    for key in ("p50","p95"):
        if candidate[key] - baseline[key] > max(50, baseline[key] * .10):
            problems.append(key + " regression exceeds tolerance")
    return problems

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("workload",choices=("warm-local","process-cold","cold-network"))
    parser.add_argument("baseline",type=Path)
    parser.add_argument("candidate",type=Path)
    args=parser.parse_args()
    stats=[]
    findings=[]
    matched = None
    for path in (args.baseline,args.candidate):
        with path.open(encoding="utf-8-sig",newline="") as handle:
            rows=list(csv.DictReader(handle))
        conditions={(r["platform"],r["device"],r["build_type"],r["fixture_hash"],r["mode"],r["workload"]) for r in rows}
        if (len(conditions)!=1 or any(c[2]=="debug" or c[-1]!=args.workload for c in conditions)
                or matched is not None and matched!=conditions):
            findings.append("incomparable device, fixture, mode, build type or workload")
        if not rows or any(not r["trace"] or not (path.parent / r["trace"]).is_file() for r in rows):
            findings.append("missing trace evidence")
        matched=conditions
        result,errors=summarize(rows,40)
        stats.append(result)
        findings.extend(errors)
        print(path.name,result)
    if all(stats):
        findings.extend(compare(*stats,args.workload))
    print("\n".join(findings) if findings else "PASS P01/P02 time budgets")
    raise SystemExit(bool(findings))

if __name__ == "__main__":
    main()
