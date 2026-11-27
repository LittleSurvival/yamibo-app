"""Analyze the simulator recording independently of XCTest polling or action duration."""
import bisect
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


def match_glyphs(directory, actions, samples, offset, width, height, detail=False):
    """Locate the exact settled label glyphs, including its distinguishing last digit."""
    import cv2
    templates=[]
    with av.open(str(directory/"presented.mp4")) as video:
        stream=video.streams.video[0]
        labels=("開始閱讀","繼續閱讀") if detail else ("READER_FLOOR_1","READER_TEXT_1")
        action="detail_ready" if detail else "glyph"
        for sample in samples:
            row=next((r for r in actions if r["round"]==sample["round"] and r["action"]==action),None)
            if not row or row.get("label") not in labels or "touch_up_ms" not in sample: continue
            target=row["lo"]*1000-offset; video.seek(max(0,int(target/1000/stream.time_base)),stream=stream,backward=True)
            f=next((f for f in video.decode(stream) if f.pts is not None and float(f.pts*f.time_base)*1000>=target),None)
            if f is None: continue
            frame=f.reformat(width=width,height=height,format="gray").to_ndarray()
            x0,y0,x1,y1=map(round,row["rect"])
            x0=max(0,x0);y0=max(0,y0);x1=min(width,x1);y1=min(height,y1)
            region=frame[y0:y1,x0:x1]
            if not region.size: continue
            if detail:
                background=np.median(np.r_[region[0,:],region[-1,:],region[:,0],region[:,-1]])
                ry,rx=np.where(np.abs(region.astype(float)-background)>38)
            else: ry,rx=np.where(region<110)
            if not len(rx):continue
            a=max(0,int(rx.min())-1);b=min(region.shape[1],int(rx.max())+2)
            c=max(0,int(ry.min())-1);d=min(region.shape[0],int(ry.max())+2)
            # Include the empty end of the label as well as the final digit;
            # otherwise "FLOOR_10" starts with the entire "FLOOR_1" template.
            right=min(width,x0+b+6)
            template=frame[y0+c:y0+d,x0+a:right].copy()
            if not (5<=d-c<=60 and 30<=b-a<=310 and template.std()>17):continue
            if right < x0+b+4 or template[:,-4:].std()>15:continue
            if not templates: cv2.imwrite(str(directory/("continue-glyph-template.png" if detail else "pid1401-glyph-template.png")),template)
            templates.append([sample,template,x0+a,y0+c,False,False])
    if not templates:return
    with av.open(str(directory/"presented.mp4")) as video:
        for f in video.decode(video=0):
            if f.pts is None:continue
            time_ms=float(f.pts*f.time_base)*1000+offset
            current=next((r for r in templates if r[0]["touch_up_ms"]-300<=time_ms<=r[0].get("glyph_search_end_ms",r[0].get("gesture_down_ms",0)) and not r[5]),None)
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
            ends=frame[y:y+th,x+tw-4:x+tw]
            if score <.94 or suffix<.93 or np.std(ends)>18:continue
            if time_ms <sample["touch_up_ms"]:
                current[4]=True
                sample["glyph_error"]="matching label already visible before the selected tap"
            elif not current[4]:
                sample.setdefault("first_correct_glyph_ms",time_ms)
                if abs(x-tx)<=2 and abs(y-ty)<=2 and sample.get("glyph_preopen_frames",0)>0:
                    sample["settled_correct_glyph_ms"]=time_ms
                    sample["glyph_t1_t0_ms"]=time_ms-sample["touch_up_ms"]
                    if "first_motion_ms" in sample: sample["glyph_t2_t1_ms"]=sample["first_motion_ms"]-time_ms
                    if "gesture_down_ms" in sample: sample["glyph_harness_delay_ms"]=sample["gesture_down_ms"]-time_ms
                    current[5]=True


def analyze(directory, mode, sequence, rounds, config):
    actions=read_actions(directory)
    touches=[json.loads(line) for p in directory.glob("reader-perf-events-*.jsonl") for line in p.read_text().splitlines() if line]
    if not touches:
        raise ValueError("App UITouch capture missing")
    max_time=max(e["time"] for e in touches)*1000+10000
    ref=min(e["time"] for e in touches)*1000
    width,height=map(lambda key: int(touches[0][key]),("width","height"))
    frames=[];previous=None
    with av.open(str(directory/"presented.mp4")) as movie:
        for f in movie.decode(video=0):
            if f.pts is None: continue
            rgb=f.reformat(width=width,height=height,format="rgb24").to_ndarray()
            stamp=clock(rgb)
            raw_ms=None
            if stamp is not None:
                t=stamp+round((ref-stamp)/2**24)*2**24
                if ref-10000<=t<=max_time: raw_ms=t
            witness=(native_pattern(rgb),0,0,0) if mode=="NativeLong" else metrics(rgb)
            mini=rgb[48:-35:6,6:-6:6,:].astype(np.int16)
            diff=float(np.mean(np.max(np.abs(mini-previous),axis=2)>12)) if previous is not None else 0.
            previous=mini
            frames.append((1000*float(f.pts*f.time_base),raw_ms,*witness,diff))
    coded=[f for f in frames if f[1] is not None]
    # Repeated identical strip pixels have one target timestamp, even when the OS
    # captures them seconds later. Only a changed displayed code is a new clock
    # observation. All video frames, including stale/empty strips, remain evidence.
    edges=[f for i,f in enumerate(coded) if i==0 or f[1]!=coded[i-1][1]]
    if len(edges)<10:raise ValueError(f"insufficient independent decoded clock edges: {len(edges)}")
    offset=statistics.median(f[1]-f[0] for f in edges[::2])
    residual=[abs(f[0]+offset-f[1]) for f in edges[1::2]]
    all_residual=[abs(f[0]+offset-f[1]) for f in coded]
    trustworthy=[f[0]+offset for f in edges if abs(f[0]+offset-f[1])<=35]
    def near_edges(t):
        j=bisect.bisect_left(trustworthy,t)
        return 0<j<len(trustworthy) and t-trustworthy[j-1]<=100 and trustworthy[j]-t<=100
    with (directory/"presented-frames.csv").open("w",newline="") as out:
        w=csv.writer(out);w.writerow(["movie_pts_ms","code_target_ms","cyan_fraction","magenta_fraction","cyan_x_pt","cyan_y_pt","changed_viewport_fraction"]);w.writerows(frames)
    aligned=[(f[0]+offset,*f[2:]) for f in frames if coded[0][0]<=f[0]<=coded[-1][0]]
    def touch(action, phase):
        hits=[t for t in touches if t["phase"]==phase and action["lo"]-.01 <= t["time"] <= action["hi"]+.01]
        if len(hits)!=1: raise ValueError(f"{action['action']} needs one correlated touch, got {len(hits)}")
        return hits[0]["time"]*1000
    if mode=="Sustain":
        process=[json.loads(l) for l in (directory/"reader-process.ndjson").read_text().splitlines() if l]
        pids={t["pid"] for t in touches}
        epoch=statistics.median(t["wall"]-t["uptime"] for t in touches)
        samples=[]
        for n in range(1,31):
            row={a["action"]:a for a in actions if a["round"]==n}
            if not {"open","back","correct","idle"}.issubset(row):continue
            s={"round":n,"content":"comic" if n%2 else "pure-text"};samples.append(s)
            start=touch(row["open"],3);s["touch_up_ms"]=start
            end=row["back"]["lo"]*1000;s["glyph_search_end_ms"]=end
            if n%2:
                found=[f for f in aligned if start<=f[0]<=end and f[1]>.035]
                pre=[f for f in aligned if start-250<=f[0]<start]
                if found and pre and max(f[1] for f in pre)<.035:s["image_t1_t0_ms"]=found[0][0]-start
            for key,a,b in (("reader_peak_rss_bytes",start/1000,end/1000),
                             ("idle_rss_bytes",row["idle"]["lo"],row["idle"]["hi"])):
                values=[r["rss_bytes"] for r in process if r["pid"] in pids and a<=r["wall"]-epoch<=b]
                if values:s[key]=max(values) if key.startswith("reader") else statistics.median(values)
        match_glyphs(directory,actions,samples,offset,width,height)
        usable=sum(("image_t1_t0_ms" in s if s["round"]%2 else "glyph_t1_t0_ms" in s) for s in samples)
        report={"usable":usable,"requested":30,"qualified_pid_count":len(pids),"samples":samples,
                "clock_residual_p95_ms":percentile(residual,95),"rss_samples":len(process),
                "coverage":"One process, alternating large 20-image and pure-text threads via Downloads. RSS alone does not count surviving readers, observers, decoders or network jobs."}
        (directory/"reader-perf.json").write_text(json.dumps(report,indent=2))
        if len(pids)!=1 or usable!=30 or not process:raise ValueError("30-cycle visible content/process evidence incomplete")
        return
    if mode=="NativeLong":
        from PIL import Image
        original = np.array(Image.open(directory/"native-image.png").convert("L"))
        row,col = np.indices(original.shape)
        assert original.shape == (512,320) and np.all(original==(row+col)%256)
        t0=touch(next(a for a in actions if a["action"]=="open"),3)
        end=next(a["hi"] for a in actions if a["action"]=="visible")*1000
        before=[f for f in aligned if t0-350<=f[0]<t0]
        after=[f for f in aligned if t0<=f[0]<=end and f[1]>.93]
        valid=bool(before and max(f[1] for f in before)<.8 and after and near_edges(t0) and near_edges(after[0][0]) and percentile(residual,95)<=35)
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
            first_image=opened[0][0]
            still=[f for f in opened if g0-300<=f[0]<g0]
            if not still: raise ValueError("missing settled pre-gesture reference")
            prev=still[-1]
            if mode=="Comic":
                complete=[f for f in opened if abs(f[1]-prev[1])<=max(.004,prev[1]*.018)
                          and abs(f[3]-prev[3])<=2 and abs(f[4]-prev[4])<=2]
                # Compare the moving image's visible area and centroid to its terminal geometry.
                # Two consecutive captured frames prevent a single coincidental crossing.
                pairs=zip(opened,opened[1:])
                complete_times={f[0] for f in complete}
                aligned_image=next((a[0] for a,b in pairs if a[0] in complete_times and b[0] in complete_times and b[0]-a[0]<=67),None)
                if aligned_image is None:raise ValueError("first comic image never matched settled geometry twice")
                t1=aligned_image
            else:
                t1=first_image
            changed=[f for f in aligned if g0<=f[0]<=g1+1300 and (f[2]>.035 or
                     (abs(f[1]-prev[1])>.02) or (f[1]>.02 and (abs(f[3]-prev[3])>6 or abs(f[4]-prev[4])>6)))]
            if not changed: raise ValueError("no visible response to first gesture")
            t2=changed[0][0]
            if not all(near_edges(t) for t in (t0,t1,g0,t2)):raise ValueError("unbracketed input/presentation time; independent clock edge exceeds 100 ms")
            sample.update(touch_up_ms=t0,first_image_pixel_ms=first_image,presented_marker_ms=t1,gesture_down_ms=g0,gesture_up_ms=g1,
                first_motion_ms=t2,t1_t0_ms=t1-t0,t2_t0_ms=t2-t0,t2_t1_ms=t2-t1,
                harness_delay_ms=g0-t1,gesture_to_response_ms=t2-g0)
            panels=[a for a in actions if a["round"]==i and a["action"]=="settings"]
            next_down=min((r["time"]*1000 for r in touches if r["phase"]==0 and r["time"]*1000>g1),default=g1+5000)
            gesture_end=min(g1+5000,next_down)
            windows={"entry":(t0,t1+120),"gesture":(g0,g1+120),"gesture_and_inertia":(g0,gesture_end)}
            if panels:
                p0=touch(panels[0],3);windows["settings"]=(p0,p0+700)
            sample["active_windows"]={}
            for window,(start,end) in windows.items():
                times=[f[0] for f in aligned if start<=f[0]<=end and f[5]>.004]
                periods=[b-a for a,b in zip(times,times[1:])]
                sample["active_windows"][window]={"changed_frames":len(times),"visible_intervals_ms":periods,
                    "first_change_after_input_ms":times[0]-start if times else None,
                    "p95_ms":percentile(periods,95),"max_ms":max(periods,default=None),
                    "over_16_7":sum(n>16.7 for n in periods),"over_33_4":sum(n>33.4 for n in periods),
                    "over_50":sum(n>50 for n in periods),"over_100":sum(n>100 for n in periods)}
            # Frames during motion only. Distinguish visual updates from idle ticker ticks.
            moving=[f for f in aligned if g0<=f[0]<=g1+120]
            transitions=[b[0] for a,b in zip(moving,moving[1:]) if abs(a[1]-b[1])>.002 or abs(a[2]-b[2])>.002 or abs(a[3]-b[3])+abs(a[4]-b[4])>1.5]
            sample["animation_content_intervals_ms"]=[b-a for a,b in zip(transitions,transitions[1:])]
        except (StopIteration,ValueError) as exc:
            sample["error"]=str(exc) or "missing XCTest action"
    if mode in ("Novel","Forum") and any(a["action"]=="glyph" for a in actions):
        match_glyphs(directory,actions,samples,offset,width,height)
    for sample in samples:
        if "touch_up_ms" not in sample:continue
        if "settled_correct_glyph_ms" in sample and not near_edges(sample["settled_correct_glyph_ms"]):
            sample["glyph_error"]="unbracketed settled glyph"
            sample.pop("glyph_t1_t0_ms",None);sample.pop("settled_correct_glyph_ms",None)
        navigation={}
        for stage in ("board","download_group","download_page","open"):
            event=next((a for a in actions if a["round"]==sample["round"] and a["action"]==stage),None)
            if event:
                try: navigation[stage]=touch(event,3)
                except ValueError:navigation[stage]=None
        primary=sample["presented_marker_ms"] if mode=="Comic" else sample.get("settled_correct_glyph_ms")
        sample["navigation_touch_up_ms"]=navigation
        sample["navigation_to_correct_content_ms"]={k:primary-t for k,t in navigation.items() if primary is not None and t is not None}
        sample["navigation_to_first_motion_ms"]={k:sample["first_motion_ms"]-t for k,t in navigation.items() if t is not None}
    details=[{"round":s["round"],"touch_up_ms":s["navigation_touch_up_ms"]["board"],"glyph_search_end_ms":s["touch_up_ms"]}
             for s in samples if s.get("navigation_touch_up_ms",{}).get("board") is not None]
    if details:
        match_glyphs(directory,actions,details,offset,width,height,detail=True)
        for detail in details:
            sample=next(s for s in samples if s["round"]==detail["round"])
            ready=detail.get("settled_correct_glyph_ms")
            sample["novel_detail"]={"board_touch_up_ms":detail["touch_up_ms"],"continue_touch_up_ms":sample["touch_up_ms"],
                "control_glyph_matched":ready is not None and near_edges(ready)}
            if ready is not None and near_edges(ready):
                sample["novel_detail"].update(board_to_visible_control_ms=ready-detail["touch_up_ms"],
                    tester_wait_on_details_ms=sample["touch_up_ms"]-ready)
                if "glyph_t1_t0_ms" in sample:
                    sample["novel_detail"]["two_stages_excluding_tester_wait_ms"]=ready-detail["touch_up_ms"]+sample["glyph_t1_t0_ms"]
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
                b=sample["presented_marker_ms"]/1000+host_offset
                def filesystem_window(t0):
                    a=t0/1000+host_offset
                    inside=[(op,line) for ts,op,line in events if (ts-a)%86400<=b-a]
                    others=[op for op,line in inside if any(780001<=int(tid)<=780100 for tid in re.findall(r"/threads/(\d+)(?:/|\s|$)",line))]
                    root_lists=[op for op,line in inside if re.search(r"/(?:library/7/)?threads/?(?:\s|$)",line) and ("direntr" in op.lower() or "readdir" in op.lower())]
                    return {"host_events":len(inside),"selected_asset_positive_controls_entire_run":positive,
                        "trace_reliable":positive>0 and bool(inside),"unrelated_operations":others,"thread_root_enumerations_with_resolved_path":root_lists,
                        "caveat":"Missing traces or selected-file controls cannot demonstrate zero unrelated opens. Directory enumeration without a resolved root path cannot establish a zero inspection count."}
                sample["file_trace"]=filesystem_window(sample["touch_up_ms"])
                sample["file_trace_from_navigation"]={stage:filesystem_window(t) for stage,t in sample["navigation_touch_up_ms"].items() if t is not None}
    if (directory/"requests.ndjson").is_file():
        events=[json.loads(l) for l in (directory/"requests.ndjson").read_text().splitlines() if l]
        arrived=directory/"begins.ndjson"
        arrivals=[json.loads(l) for l in arrived.read_text().splitlines() if l] if arrived.is_file() else None
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
                held=[r["release_host_ns"]/1e6-host_delta for r in rr if r["catalog_held"] and r["complete"] and "release_host_ns" in r]
                if held: net["both_visible_before_catalog_release"]=max(t1,t2)+net["origin_clock_uncertainty_ms"]<min(held)
                spans=sorted((max(t0,r["begin_host_ns"]/1e6-host_delta),min(t1,r["end_host_ns"]/1e6-host_delta)) for r in required)
                total=0.;right=t0
                for a,b in spans:
                    total+=max(0.,b-max(a,right));right=max(right,b)
                net["overlap_observed_required_http_ms"]=total
                net["elapsed_outside_required_http_ms"]=t1-t0-total
            if arm["case"].endswith("-none"):
                # Count attempted case-specific content operations even if the client cancelled/404ed.
                net["ingress_recording_available"]=arrivals is not None
                if arrivals is not None:
                    guard=net["origin_clock_uncertainty_ms"]
                    trace=[r for r in arrivals if r["run"]==arm["case"] and t0-guard<=r["begin_host_ns"]/1e6-host_delta<=t2+700+guard]
                    net["content_attempts_open_through_first_gesture"]=[r["resource"] for r in trace if r["resource"] in
                        ("thread","catalog-post","catalog-results","unknown") or r["resource"].startswith("image-")]
                net["negative_window_ms"]=[t0,t2+700]
                net["coverage"]="Observed the fixture-origin SDK and image URL traffic only. Positive network board tests on the same code are separate. No airplane-mode claim."
            sample["controlled_network"]=net
    valid=[s for s in samples if "error" not in s]
    summary={"mode":mode,"sequence":sequence,"configuration":config,"requested":rounds,"usable":len(valid),
        "video_frames":len(frames),"clock_frames":len(coded),"independent_clock_edges":len(edges),
        "held_out_clock_edges":len(residual),"all_coded_frames_residual_p95_ms":percentile(all_residual,95),"clock_residual_p95_ms":percentile(residual,95),
        "median_clock_offset_ms":offset,"samples":samples,
        "coverage":"Seeded local author page. Cyan is an independently rendered PID 1401 image, including in text modes. Earliest textual glyph is not measured. Gesture injection after XCTest readiness checks is recorded separately. No network-attempt or tag-12 comparison in this run."}
    for key in ("t1_t0_ms","t2_t0_ms","t2_t1_ms","harness_delay_ms","gesture_to_response_ms"):
        vals=[s[key] for s in valid];summary[key]={"p50":percentile(vals,50),"p95":percentile(vals,95)}
    summary["display_link_observations"]={
        "screen_max_hz":sorted({t["screen_max_hz"] for t in touches if "screen_max_hz" in t}),
        "nominal_callback_period_ms":sorted({round(t["display_link_duration_ms"],2) for t in touches if t.get("display_link_duration_ms",0)>0}),
        "measurement":"Capture of visible content changes and simulator display-link timestamps. Physical device scanout and operating-system compositor deadline misses are not observed."}
    summary["percentile"]="nearest rank ceil(n*p/100), p95 of 40 is the 38th ordered sample"
    conditions=json.loads((directory/"profile-conditions.json").read_text()) if (directory/"profile-conditions.json").is_file() else {}
    problems=[]
    if mode in ("Novel","Forum"):
        summary["image_witness_t1_t0_ms"]=summary.pop("t1_t0_ms")
        summary["image_witness_t2_t1_ms"]=summary.pop("t2_t1_ms")
        summary["coverage"]="Source PID1401 label READER_FLOOR_1 uses a separate glyph template and suffix at settled geometry. Cyan image timings are retained as secondary observations. No zero-content-requests or tag-12 claim without separate evidence."
        glyphs=[s["glyph_t1_t0_ms"] for s in valid if "glyph_t1_t0_ms" in s]
        summary["settled_glyph"]={"usable":len(glyphs),"p50":percentile(glyphs,50),"p95":percentile(glyphs,95)}
        summary["secondary_image_usable"]=len(valid);summary["usable"]=len(glyphs)
        if len(glyphs)!=rounds:problems.append("missing physically matched primary text witness")
    navigation_summary={}
    for stage in ("board","download_group","download_page","open"):
        values=[s["navigation_to_correct_content_ms"][stage] for s in valid if stage in s.get("navigation_to_correct_content_ms",{})]
        if values:
            navigation_summary[stage]={"usable":len(values),"p50":percentile(values,50),"p95":percentile(values,95)}
    summary["navigation_to_correct_content_ms"]=navigation_summary
    if conditions.get("network") or conditions.get("guard_local"):
        for s in valid:
            req=s.get("controlled_network",{})
            if conditions.get("network") and not req.get("thread_retrieved"):problems.append(f"round {s['round']}: cold thread response missing")
            if conditions.get("network")=="slow" and mode=="Comic" and req.get("both_visible_before_catalog_release") is not True:
                problems.append(f"round {s['round']}: held catalog did not have verified content and response before release")
            if conditions.get("guard_local") and req.get("content_attempts_open_through_first_gesture")!=[]:
                problems.append(f"round {s['round']}: local content request or unobserved interval")
    if conditions.get("prompt_input"):
        summary["harness_injected_within_100_ms"]=sum(0 <= s.get("glyph_harness_delay_ms",s["harness_delay_ms"]) <=100 for s in valid)
    summary["acquisition_failures"]=problems
    (directory/"reader-perf.json").write_text(json.dumps(summary,indent=2))
    for s in samples: print("READER_PRESENTED_SAMPLE "+json.dumps(s,separators=(",",":")),flush=True)
    print(json.dumps({k:v for k,v in summary.items() if k!="samples"}),flush=True)
    if problems or len(valid)!=rounds or summary["clock_residual_p95_ms"]>35:
        raise ValueError("recording did not satisfy input/marker/clock cross-checks")

if __name__=="__main__":
    import sys
    directory=Path(sys.argv[1])
    analyze(directory,sys.argv[2],sys.argv[3],int(sys.argv[4]),sys.argv[5])
