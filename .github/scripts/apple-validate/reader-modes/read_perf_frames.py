"""Analyze the simulator recording independently of XCTest polling or action duration."""
import csv
import math
import re
import datetime
import json
from pathlib import Path
import statistics

import av
import numpy as np


def read_actions(output):
    collected={}
    for path in (output / "perf-attachments").rglob("*"):
        if not path.is_file() or path.stat().st_size > 400_000:
            continue
        try:
            rows = json.loads(path.read_bytes())
        except (ValueError, UnicodeError):
            continue
        if isinstance(rows, list) and rows and isinstance(rows[0], dict) and "round" in rows[0] and "action" in rows[0]:
            for row in rows:
                collected[(row["round"],row["action"])]=row
    if collected: return list(collected.values())
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


def native_pattern(rgb):
    # Template from the decoded grayscale source, known to be L=(x+y) mod 256.
    # A translation changes phase; only the absolute phase correlation is compared.
    h,w,_ = rgb.shape
    ys,xs = np.mgrid[int(h*.39):int(h*.62):6,int(w*.28):int(w*.72):6]
    samples = rgb[ys,xs].astype(np.float32)
    if np.mean(samples.max(2)-samples.min(2)<9) < .94: return 0.
    light = samples.mean(2)
    if light.std()<26: return 0.
    factor = 2j*np.pi/256
    scales = [320/w, 512/h, 1., 2., 3.] + list(np.linspace(.6,2.,19))
    return max(abs(np.mean(np.exp(factor*(light-slope*(xs+ys))))) for slope in scales)


def percentile(a,p):
    return float(sorted(a)[max(0,math.ceil(len(a)*p/100)-1)]) if a else None


def match_glyphs(directory, actions, samples, offset, width, height):
    """Locate the exact settled label glyphs, including its distinguishing last digit."""
    import cv2
    templates=[]
    with av.open(str(directory/"presented.mp4")) as video:
        stream=video.streams.video[0]
        for sample in samples:
            row=next((r for r in actions if r["round"]==sample["round"] and r["action"]=="glyph"),None)
            if not row or row.get("label")!="READER_FLOOR_1" or "touch_up_ms" not in sample: continue
            target=row["lo"]*1000-offset; video.seek(max(0,int(target/1000/stream.time_base)),stream=stream,backward=True)
            f=next((f for f in video.decode(stream) if f.pts is not None and float(f.pts*f.time_base)*1000>=target),None)
            if f is None: continue
            frame=f.reformat(width=width,height=height,format="gray").to_ndarray()
            x0,y0,x1,y1=map(round,row["rect"])
            x0=max(0,x0);y0=max(0,y0);x1=min(width,x1);y1=min(height,y1)
            region=frame[y0:y1,x0:x1]
            if not region.size: continue
            ry,rx=np.where(region<110)
            if not len(rx):continue
            a=max(0,int(rx.min())-1);b=min(region.shape[1],int(rx.max())+2)
            c=max(0,int(ry.min())-1);d=min(region.shape[0],int(ry.max())+2)
            template=region[c:d,a:b].copy()
            if not (5<=d-c<=60 and 30<=b-a<=310 and template.std()>17):continue
            if not templates: cv2.imwrite(str(directory/"pid1401-glyph-template.png"),template)
            templates.append([sample,template,x0+a,y0+c,False,False])
    if not templates:return
    with av.open(str(directory/"presented.mp4")) as video:
        for f in video.decode(video=0):
            if f.pts is None:continue
            time_ms=float(f.pts*f.time_base)*1000+offset
            current=next((r for r in templates if r[0]["touch_up_ms"]-300<=time_ms<=r[0]["gesture_down_ms"] and not r[5]),None)
            if current is None:continue
            sample,img,tx,ty,_,_=current
            frame=f.reformat(width=width,height=height,format="gray").to_ndarray()
            th,tw=img.shape
            # Match the unobscured glyph line inside the actual viewport. Threshold both
            # the full string and its numeric suffix to reject the neighbouring floor.
            y0=40;area=frame[y0:height-35,:]
            _,score,_,(x,y)=cv2.minMaxLoc(cv2.matchTemplate(area,img,cv2.TM_CCOEFF_NORMED))
            y+=y0; sx=int(tw*.82)
            suffix=float(cv2.matchTemplate(frame[y:y+th,x+sx:x+tw],img[:,sx:],cv2.TM_CCOEFF_NORMED)[0,0])
            if time_ms < sample["touch_up_ms"]:
                sample["glyph_preopen_frames"]=1+sample.get("glyph_preopen_frames",0)
            if score <.94 or suffix<.93:continue
            if time_ms <sample["touch_up_ms"]:
                current[4]=True
                sample["glyph_error"]="matching label already visible before the selected tap"
            elif not current[4]:
                sample.setdefault("first_correct_glyph_ms",time_ms)
                if abs(x-tx)<=2 and abs(y-ty)<=2 and sample.get("glyph_preopen_frames",0)>0:
                    sample["settled_correct_glyph_ms"]=time_ms
                    sample["glyph_t1_t0_ms"]=time_ms-sample["touch_up_ms"]
                    sample["glyph_t2_t1_ms"]=sample["first_motion_ms"]-time_ms
                    sample["glyph_harness_delay_ms"]=sample["gesture_down_ms"]-time_ms
                    current[5]=True


def analyze(directory, mode, sequence, rounds, config):
    actions=read_actions(directory)
    touches=[json.loads(line) for p in directory.glob("reader-perf-events-*.jsonl") for line in p.read_text().splitlines() if line]
    if not touches:
        raise ValueError("App UITouch capture missing")
    max_time=max(e["time"] for e in touches)*1000+10000
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
                witness=(native_pattern(rgb),0,0,0) if mode=="NativeLong" else metrics(rgb)
                frames.append((1000*float(f.pts*f.time_base),raw_ms,*witness))
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
    if mode=="NativeLong":
        from PIL import Image
        original = np.array(Image.open(directory/"native-image.png").convert("L"))
        row,col = np.indices(original.shape)
        assert original.shape == (512,320) and np.all(original==(row+col)%256)
        t0=touch(next(a for a in actions if a["action"]=="open"),3)
        end=next(a["hi"] for a in actions if a["action"]=="visible")*1000
        before=[f for f in aligned if t0-350<=f[0]<t0]
        after=[f for f in aligned if t0<=f[0]<=end and f[1]>.93]
        valid=bool(before and max(f[1] for f in before)<.8 and after and percentile(residual,95)<=35)
        report={"usable":int(valid), "observed":len(after), "pid":100001, "image_sha256":"0e92708f9b5063336c4036998ab625845bcf983de1cc212d5bb3851df8d0fbc4",
                "native_floors":240,"native_pages":12,"native_blobs":1,"max_preopen_template_score":max((f[1] for f in before),default=None),
                "first_presented_template_score":after[0][1] if after else None,"t1_t0_ms":after[0][0]-t0 if valid else None,
                "clock_residual_p95_ms":percentile(residual,95),"coverage":"First real, winning center patch from the native library. Repeated floor images share a blob; page/first position additionally checked in XCTest. One diagnostic sample."}
        (directory/"reader-perf.json").write_text(json.dumps(report,indent=2))
        if not valid: raise ValueError("native library bitmap/clock/counter evidence incomplete")
        print(json.dumps(report),flush=True)
        return
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
    if mode in ("Novel","Forum") and any(a["action"]=="glyph" for a in actions):
        match_glyphs(directory,actions,samples,offset,width,height)
    if (directory/"fs-usage.log").is_file():
        watch=(directory/"fs-usage.log").read_text(errors="replace")
        events=[]
        for line in watch.splitlines():
            stamp=re.match(r"\s*(\d{2}):(\d{2}):(\d{2}\.\d+)\s+(\S+)",line)
            if not stamp: continue
            hh,mm,ss,op=stamp.groups()
            events.append((3600*int(hh)+60*int(mm)+float(ss),op,line))
        clockref=next((t for t in touches if "wall" in t and "uptime" in t),None)
        positive=sum("YamiboDownloads" in line and ("thread_140_" in line or "/threads/140/" in line or "/images/" in line) for _,_,line in events)
        if clockref:
            daytime=datetime.datetime.fromtimestamp(clockref["wall"])
            day0=daytime.replace(hour=0,minute=0,second=0,microsecond=0).timestamp()
            host_offset=clockref["wall"]-day0-clockref["uptime"]
            for sample in samples:
                if "touch_up_ms" not in sample: continue
                a=sample["touch_up_ms"]/1000+host_offset; b=sample["presented_marker_ms"]/1000+host_offset
                inside=[(op,line) for ts,op,line in events if (ts-a)%86400<=b-a]
                others=[op for op,line in inside if "/threads/780001/" in line]
                sample["file_trace"]={"host_events":len(inside),"selected_asset_positive_controls_entire_run":positive,
                    "trace_reliable":positive>0 and bool(inside),"unrelated_operations":others,
                    "caveat":"fs_usage for the App executable. A missing trace/selected-file control cannot demonstrate zero unrelated physical opens."}
    if (directory/"requests.ndjson").is_file():
        events=[json.loads(l) for l in (directory/"requests.ndjson").read_text().splitlines() if l]
        for sample in samples:
            arm=next((r for r in actions if r["round"]==sample["round"] and r["action"]=="origin"),None)
            if not arm or "touch_up_ms" not in sample: continue
            host_delta=arm["host_ns"]/1e6-(arm["lo"]+arm["hi"])*500
            rr=[r for r in events if r["run"]==arm["case"]]
            needs={"thread","image-1"} if mode=="Comic" else {"thread"}
            required=[r for r in rr if r["resource"] in needs and r["complete"]]
            t1=sample["presented_marker_ms"] if mode=="Comic" else sample.get("settled_correct_glyph_ms")
            t0=sample["touch_up_ms"];t2=sample["first_motion_ms"]
            net={"requests":len(rr),"origin_clock_uncertainty_ms":(arm["hi"]-arm["lo"])*500,
                 "required_resources_seen":sorted({r["resource"] for r in required}),
                 "thread_retrieved":any(r["resource"]=="thread" for r in required),
                 "completed_catalog_hold":any(r["catalog_held"] and r["complete"] for r in rr)}
            if t1:
                held=[r["end_host_ns"]/1e6-host_delta for r in rr if r["catalog_held"] and r["complete"]]
                if held: net["both_visible_before_catalog_release"]=max(t1,t2)+net["origin_clock_uncertainty_ms"]<min(held)
                spans=sorted((max(t0,r["begin_host_ns"]/1e6-host_delta),min(t1,r["end_host_ns"]/1e6-host_delta)) for r in required)
                total=0.;right=t0
                for a,b in spans:
                    total+=max(0.,b-max(a,right));right=max(right,b)
                net["overlap_observed_required_http_ms"]=total
                net["elapsed_outside_required_http_ms"]=t1-t0-total
            sample["controlled_network"]=net
    valid=[s for s in samples if "error" not in s]
    summary={"mode":mode,"sequence":sequence,"configuration":config,"requested":rounds,"usable":len(valid),
        "clock_frames":len(frames),"clock_residual_p95_ms":percentile(residual,95),
        "median_clock_offset_ms":offset,"samples":samples,
        "coverage":"Seeded local author page. Cyan is an independently rendered PID 1401 image, including in text modes. Earliest textual glyph is not measured. Gesture injection after XCTest readiness checks is recorded separately. No network-attempt or tag-12 comparison in this run."}
    for key in ("t1_t0_ms","t2_t0_ms","t2_t1_ms","harness_delay_ms","gesture_to_response_ms"):
        vals=[s[key] for s in valid];summary[key]={"p50":percentile(vals,50),"p95":percentile(vals,95)}
    summary["percentile"]="nearest rank ceil(n*p/100), p95 of 40 is the 38th ordered sample"
    if mode in ("Novel","Forum"):
        summary["image_witness_t1_t0_ms"]=summary.pop("t1_t0_ms")
        summary["image_witness_t2_t1_ms"]=summary.pop("t2_t1_ms")
        summary["coverage"]="Source PID1401 label READER_FLOOR_1 uses a separate glyph template and suffix at settled geometry. Cyan image timings are retained as secondary observations. No zero-content-requests or tag-12 claim without separate evidence."
        glyphs=[s["glyph_t1_t0_ms"] for s in valid if "glyph_t1_t0_ms" in s]
        summary["settled_glyph"]={"usable":len(glyphs),"p50":percentile(glyphs,50),"p95":percentile(glyphs,95)}
    (directory/"reader-perf.json").write_text(json.dumps(summary,indent=2))
    for s in samples: print("READER_PRESENTED_SAMPLE "+json.dumps(s,separators=(",",":")),flush=True)
    print(json.dumps({k:v for k,v in summary.items() if k!="samples"}),flush=True)
    if len(valid)!=rounds or summary["clock_residual_p95_ms"]>35:
        raise ValueError("recording did not satisfy input/marker/clock cross-checks")

if __name__=="__main__":
    import sys
    directory=Path(sys.argv[1])
    analyze(directory,sys.argv[2],sys.argv[3],int(sys.argv[4]),sys.argv[5])
