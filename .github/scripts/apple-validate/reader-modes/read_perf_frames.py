"""Analyze the simulator recording independently of XCTest polling or action duration."""
import argparse
import csv
import json
import math
from pathlib import Path
import statistics

import av
import numpy as np


def read_actions(output):
    for path in (output / "perf-attachments").glob("*"):
        if not path.is_file() or path.stat().st_size > 400_000:
            continue
        try:
            rows = json.loads(path.read_bytes())
        except (ValueError, UnicodeError):
            continue
        if isinstance(rows, list) and rows and isinstance(rows[0], dict) and "round" in rows[0] and "action" in rows[0]:
            return rows
    raise ValueError("XCTest input correlation attachment missing")


def clock(rgb):
    # All coordinates are UIKit points after reduction to window size.
    line = rgb[-18, 6+np.arange(34)*3+1].astype(np.int16)
    for i, channel in ((0,0),(1,0),(2,1),(3,1),(4,2),(5,2)):
        if line[i,channel] < 145 or any(line[i,c]>115 for c in range(3) if c!=channel):
            return None
    values = line[6:].mean(axis=1)
    if any(65 < v < 185 for v in values) or list(values[-4:]>128) != [True,False,True,False]:
        return None
    return sum(int(v>128) << i for i,v in enumerate(values[:24]))


def metrics(rgb):
    region = rgb[48:-35:3,6:-6:3].astype(np.int16)
    r,g,b = region[:,:,0],region[:,:,1],region[:,:,2]
    cyan = (r<75)&(g>140)&(g<215)&(b>185)&(b<247)
    magenta = (r>185)&(g<80)&(b>135)&(b<215)
    ys,xs = np.nonzero(cyan)
    return (float(cyan.mean()),float(magenta.mean()),float(xs.mean()*3) if len(xs) else 0,
            float(ys.mean()*3) if len(ys) else 0)


def percentile(a,p):
    return float(np.percentile(a,p)) if a else None


def analyze(directory, mode, sequence, rounds, config):
    actions=read_actions(directory)
    touches=[json.loads(line) for p in directory.glob("reader-perf-events-*.jsonl") for line in p.read_text().splitlines() if line]
    if not touches:
        raise ValueError("App UITouch capture missing")
    ref=min(e["time"] for e in touches)*1000
    width,height=map(lambda key: int(touches[0][key]),("width","height"))
    frames=[]
    with av.open(str(directory/"presented.mp4")) as movie:
        for f in movie.decode(video=0):
            if f.pts is None: continue
            rgb=f.reformat(width=width,height=height,format="rgb24").to_ndarray()
            stamp=clock(rgb)
            if stamp is None: continue
            raw_ms=stamp+round((ref-stamp)/2**24)*2**24
            if ref-10000 <= raw_ms <= max_time:
                frames.append((1000*float(f.pts*f.time_base),raw_ms,*metrics(rgb)))
    if len(frames)<5:
        raise ValueError(f"insufficient independently decoded clock frames: {len(frames)}")
    offset=statistics.median(f[1]-f[0] for f in frames)
    residual=[abs((f[0]+offset)-f[1]) for f in frames]
    with (directory/"presented-frames.csv").open("w",newline="") as out:
        w=csv.writer(out); w.writerow(["movie_pts_ms","code_target_ms","cyan_fraction","magenta_fraction","cyan_x_pt","cyan_y_pt"]);w.writerows(frames)
    aligned=[(f[0]+offset,*f[2:]) for f in frames if abs(f[0]+offset-f[1])<=36]
    def touch(action, phase):
        hits=[t for t in touches if t["phase"]==phase and action["lo"]-.01 <= t["time"] <= action["hi"]+.01]
        if len(hits)!=1: raise ValueError(f"{action['action']} needs one correlated touch, got {len(hits)}")
        return hits[0]["time"]*1000
    samples=[]
    for i in range(1,rounds+1):
        sample={"round":i};samples.append(sample)
        try:
            selection=next(a for a in actions if a["round"]==i and a["action"]=="open")
            gesture=next(a for a in actions if a["round"]==i and a["action"]=="gesture")
            t0=touch(selection,3);g0=touch(gesture,0);g1=touch(gesture,3)
            before=[f for f in aligned if t0-300<=f[0]<t0-15]
            if not before or max(f[1] for f in before)>=.035: raise ValueError("first-floor marker was visible before selection, or no pre-input frames")
            opened=[f for f in aligned if t0<=f[0]<=g0 and f[1]>=.035]
            if not opened: raise ValueError("correct first-floor cyan image missing in recorded frames")
            t1=opened[0][0]
            still=[f for f in opened if g0-300<=f[0]<g0]
            if not still: raise ValueError("missing settled pre-gesture reference")
            prev=still[-1]
            changed=[f for f in aligned if g0<=f[0]<=g1+1300 and (f[2]>.035 or
                     (abs(f[1]-prev[1])>.02) or (f[1]>.02 and (abs(f[3]-prev[3])>6 or abs(f[4]-prev[4])>6)))]
            if not changed: raise ValueError("no visible response to first gesture")
            t2=changed[0][0]
            sample.update(touch_up_ms=t0,presented_marker_ms=t1,gesture_down_ms=g0,gesture_up_ms=g1,
                first_motion_ms=t2,t1_t0_ms=t1-t0,t2_t0_ms=t2-t0,t2_t1_ms=t2-t1,
                harness_delay_ms=g0-t1,gesture_to_response_ms=t2-g0)
            # Frames during motion only. Distinguish visual updates from idle ticker ticks.
            moving=[f for f in aligned if g0<=f[0]<=g1+120]
            transitions=[b[0] for a,b in zip(moving,moving[1:]) if abs(a[1]-b[1])>.002 or abs(a[2]-b[2])>.002 or abs(a[3]-b[3])+abs(a[4]-b[4])>1.5]
            sample["animation_content_intervals_ms"]=[b-a for a,b in zip(transitions,transitions[1:])]
        except (StopIteration,ValueError) as exc:
            sample["error"]=str(exc) or "missing XCTest action"
    valid=[s for s in samples if "error" not in s]
    summary={"mode":mode,"sequence":sequence,"configuration":config,"requested":rounds,"usable":len(valid),
        "clock_frames":len(frames),"clock_residual_p95_ms":percentile(residual,95),
        "median_clock_offset_ms":offset,"samples":samples,
        "coverage":"Seeded local author page. Cyan is an independently rendered PID 1401 image, including in text modes. Earliest textual glyph is not measured. Gesture injection after XCTest readiness checks is recorded separately. No network-attempt or tag-12 comparison in this run."}
    for key in ("t1_t0_ms","t2_t0_ms","t2_t1_ms","harness_delay_ms","gesture_to_response_ms"):
        vals=[s[key] for s in valid];summary[key]={"p50":percentile(vals,50),"p95":percentile(vals,95)}
    (directory/"reader-perf.json").write_text(json.dumps(summary,indent=2))
    print(json.dumps({k:v for k,v in summary.items() if k!="samples"}),flush=True)
    if len(valid)!=rounds or summary["clock_residual_p95_ms"]>35:
        raise ValueError("recording did not satisfy input/marker/clock cross-checks")

if __name__=="__main__":
    import sys
    directory=Path(sys.argv[1])
    max_time=max(json.loads(line)["time"] for p in directory.glob("reader-perf-events-*.jsonl") for line in p.read_text().splitlines() if line)*1000+10000
    analyze(directory,sys.argv[2],sys.argv[3],int(sys.argv[4]),sys.argv[5])
