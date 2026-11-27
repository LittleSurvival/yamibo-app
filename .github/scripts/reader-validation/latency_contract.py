"""Reader T0 -> correct rendered content T1 -> visible gesture response T2. Times in ms."""
import argparse
import csv
import hashlib
import json
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
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

"""Public forum benchmark for isolated .abtag12 / .abcurrent releaseRun APKs."""

PACKAGES = {side: "me.thenano.yamibo.yamibo_app." + suffix
            for side, suffix in (("baseline", "abtag12"), ("candidate", "abcurrent"))}
PINNED_THREAD = "百合会新人须知/论坛规则"
VIDEO_SIZE = (432, 768)


def write_measurement(path, record):
    path.write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")


def apk_identity(path, expected_package, aapt):
    result = subprocess.check_output([aapt, "dump", "badging", str(path)], encoding="utf-8")
    reported = re.search(r"^package: name='([^']+)'", result, re.MULTILINE)
    if not reported or reported[1] != expected_package or re.search(r"^application-debuggable", result, re.MULTILINE):
        raise ValueError(f"Expected the non-debuggable benchmark package {expected_package} in {path}")
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def installed_identity(device):
    path = device.shell("pm", "path", device.package)
    if not path.startswith("package:"):
        raise ValueError(f"Missing benchmark APK {device.package}")
    sha = device.shell("sha256sum", path.splitlines()[0].removeprefix("package:")).split()[0]
    info = device.shell("dumpsys", "package", device.package)
    if "DEBUGGABLE" in info:
        raise ValueError(f"Benchmark package is debuggable: {device.package}")
    states = re.findall(r"(?m)^\s*x86_64: \[status=([^\]]+)\] \[reason=([^\]]+)\] \[primary-abi\]", info)
    if len(states) != 1:
        raise ValueError(f"Missing unique ART optimization state for {device.package}")
    return sha, list(states[0])


def run_forum_matrix(args):
    """Use adb, aapt, Python 3.11+, opencv-python, numpy, perfetto and trace_processor_shell."""
    if not re.fullmatch(r"emulator-\d+", args.serial):
        raise ValueError("Select a dedicated Android emulator")
    paths = {"baseline": args.baseline_apk, "candidate": args.candidate_apk}
    signatures = {side: apk_identity(path, PACKAGES[side], args.aapt) for side, path in paths.items()}
    if signatures["baseline"] == signatures["candidate"]:
        raise ValueError("Baseline and candidate must be distinct APKs")
    trace_shell = args.trace_processor.resolve(strict=True)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    if not 1 <= args.calibration_pairs <= 1000 or not 1 <= args.pairs <= 1000:
        raise ValueError("Pair counts must be between 1 and 1000")
    devices = {side: ReaderDevice(args.serial, package, output) for side, package in PACKAGES.items()}
    profile = devices["baseline"]
    for side, device in devices.items():
        device.adb("install", "-r", paths[side], timeout=120)
        if installed_identity(device)[0] != signatures[side]:
            raise ValueError(f"Installed {side} binary does not match its file")
    params = {
        "serial": args.serial, "route":"forum", "wait_for_body_draw":True, "physical_markers_required":True,
        "public_body_replay":False, "avd_name":profile.adb("emu","avd","name").decode().splitlines()[0].strip(),
        "boot_id":profile.shell("cat","/proc/sys/kernel/random/boot_id"),
        "device_fingerprint":profile.shell("getprop","ro.build.fingerprint"),
        "model":profile.shell("getprop","ro.product.model"), "resolution":profile.shell("wm","size"),
        "density":profile.shell("wm","density"), "font_scale":profile.shell("settings","get","system","font_scale"),
    }
    size = re.findall(r"(\d+)x(\d+)",params["resolution"])
    density = re.findall(r"\d+",params["density"])
    if not size or size[-1] != ("720","1280") or not density or density[-1] != "320":
        raise ValueError("Registered forum pixel fixture requires 720x1280 at 320 dpi")
    for key in ("window_animation_scale","transition_animation_scale","animator_duration_scale"):
        params[key] = profile.shell("settings","get","global",key)
    ref_pixels = []
    for side, device in devices.items():
        directory = output/("ref-"+side)
        directory.mkdir()
        device.folder = directory
        _forum_reference(device,directory)
        ref_pixels.append((directory/"forum-body.png").read_bytes())
    if ref_pixels[0] != ref_pixels[1]:
        raise ValueError("First-floor reference pixels differ")
    for purpose, pairs in (("aa",args.calibration_pairs),("ab",args.pairs)):
        matrix = output/f"{purpose}-{pairs}"
        matrix.mkdir()
        environment = dict(params, purpose=purpose, rounds=pairs)
        for side in PACKAGES:
            kind = "baseline" if purpose=="aa" else side
            environment[side+"_sha256"] = signatures[kind]
            environment[side+"_apk_bytes"] = paths[kind].stat().st_size
        write_measurement(matrix/"environment.json",environment)
        records = []
        for number in range(1,pairs+1):
            for side in (("baseline","candidate") if number%2 else ("candidate","baseline")):
                kind = "baseline" if purpose=="aa" else side
                device = devices[kind]
                directory = matrix/f"{number:02}-{side}"
                directory.mkdir()
                device.folder = directory
                record = {"status":"FAIL","label":kind,"package":device.package,"route":"forum"}
                try:
                    actual, optimization = installed_identity(device)
                    boot = device.shell("cat","/proc/sys/kernel/random/boot_id")
                    if actual != signatures[kind] or boot != environment["boot_id"]:
                        raise ValueError("Device boot or installed binary changed")
                    record.update(binary_sha256=actual,dexopt=optimization,boot_id=boot)
                    observation = _capture(device,directory,output/("ref-"+kind),boot,trace_shell)
                    record.update(observation,status="OK")
                except (ValueError,OSError,RuntimeError,subprocess.SubprocessError) as error:
                    record["error"] = repr(error)
                    try:
                        failure = device.shell("dumpsys","activity","exit-info",device.package)
                        (directory/"android-exit-info.txt").write_text(failure,encoding="utf-8")
                    except (OSError,subprocess.SubprocessError) as diagnostic:
                        record["diagnostic_error"] = repr(diagnostic)
                write_measurement(directory/"result.json",record)
                records.append(dict(record,round=number,side=side))
                write_measurement(matrix/"rounds.json",records)
                print(f"{purpose} {number:02} {side}: {record['status']}",flush=True)
    android_summary(["--aa",str(output/f"aa-{args.calibration_pairs}"/"rounds.json"),
                     "--ab",str(output/f"ab-{args.pairs}"/"rounds.json"),"--output",str(output/"comparison.json")])


def android_forum_benchmark(argv):
    parser = argparse.ArgumentParser(description="Alternate the observed releaseRun APKs on one 720x1280 320dpi x86_64 AVD. Each trial clears only the selected .abtag12 or .abcurrent data.")
    parser.add_argument("--serial",required=True)
    parser.add_argument("--baseline-apk",required=True,type=Path)
    parser.add_argument("--candidate-apk",required=True,type=Path)
    parser.add_argument("--trace-processor",required=True,type=Path)
    parser.add_argument("--aapt",default="aapt",help="Android SDK build-tools aapt executable")
    parser.add_argument("--output",required=True,type=Path,help="New output directory")
    parser.add_argument("--calibration-pairs",type=int,default=7)
    parser.add_argument("--pairs",type=int,default=40)
    run_forum_matrix(parser.parse_args(argv))



class ReaderDevice:
    def __init__(self, serial, package, folder):
        if not re.fullmatch(r"emulator-\d+",serial) or package not in {
            "me.thenano.yamibo.yamibo_app.abtag12","me.thenano.yamibo.yamibo_app.abcurrent"
        }:
            raise ValueError("Unregistered benchmark target")
        self.serial, self.package, self.folder = serial, package, Path(folder)
        self.size = (720,1280)

    def adb(self,*args,timeout=35,input=None,data=None,check=True,**kwargs):
        return subprocess.run(["adb","-s",self.serial,*map(str,args)],input=data if data is not None else input,stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=check,timeout=timeout,**kwargs).stdout

    def shell(self,*args,**kwargs):
        return self.adb("shell",*args,**kwargs).decode("utf-8",errors="replace").strip()

    def snapshot(self):
        for _ in range(3):
            self.shell("uiautomator","dump","/data/local/tmp/reader-perf-ui.xml")
            raw=self.adb("exec-out","cat","/data/local/tmp/reader-perf-ui.xml")
            try: return ET.fromstring(raw)
            except ET.ParseError:time.sleep(.2)
        raise ValueError("Invalid native UI tree")

    @staticmethod
    def label(node):
        return node.get("text","") or node.get("content-desc","")

    @staticmethod
    def bounds(node):
        return tuple(map(int,re.findall(r"-?\d+",node.get("bounds",""))))

    def find(self,tree,name):
        return next((node for node in tree.iter("node") if name in (node.get("content-desc"),node.get("text"))),None)

    def tap(self,node):
        x,y,r,b=self.bounds(node)
        self.shell("input","tap",(x+r)//2,(y+b)//2)

    def launch(self):
        self.shell("am","start","-W","-n",self.package+"/me.thenano.yamibo.yamibo_app.MainActivity")

    def showcase(self,name,tree=None):
        if tree is None: tree=self.snapshot()
        (self.folder/(name+".xml")).write_bytes(ET.tostring(tree,encoding="utf-8",xml_declaration=True))
        (self.folder/(name+".png")).write_bytes(self.adb("exec-out","screencap","-p"))
        return tree

def _center(bounds):
    x,y,r,b=bounds
    return (x+r)//2,(y+b)//2


def _forum_button(d):
    # No reading history, prior page cache or stored anchor for either release.
    if d.shell("pm","clear",d.package)!="Success": raise ValueError("could not clear dedicated benchmark package")
    d.shell("pm","grant",d.package,"android.permission.POST_NOTIFICATIONS")
    import time
    def await_visible(label):
        until=time.monotonic()+35
        while time.monotonic()<until:
            tree=d.snapshot();node=d.find(tree,label)
            if node is not None and node.get("package")==d.package: return tree,node
            if not d.shell("pidof",d.package).strip(): raise ValueError("measured app exited during forum navigation")
        raise ValueError("normal forum navigation timed out waiting for "+label)
    d.launch()
    _,forum=await_visible("管理版")
    d.tap(forum)
    tree,node=await_visible(PINNED_THREAD)
    if node is None or d.find(tree,"置頂") is None or d.find(tree,"管理版") is None:
        raise ValueError("normal forum listing does not contain the registered pinned thread")
    return _center(d.bounds(node))


def _forum_reference(d,out):
    import time
    d.shell("input","tap",*_forum_button(d));time.sleep(1)
    for _ in range(7):
        tree=d.snapshot()
        pagers=[d.label(n) for n in tree.iter("node") if re.fullmatch(r"1/\d+\s+0%",d.label(n))]
        title=d.find(tree,PINNED_THREAD)
        if pagers and title is not None and title.get("package")==d.package: break
    else: raise ValueError("native reader did not open the designated forum post at zero progress")
    d.showcase("forum-reference",tree)
    # The first substantial visible paragraph is registered before measuring either APK.
    candidates=[n for n in tree.iter("node") if n.get("class")=="android.widget.TextView" and
                len(d.label(n))>=20 and 210<=d.bounds(n)[1]<=1000 and
                d.bounds(n)[2]-d.bounds(n)[0]>=200 and d.bounds(n)[3]-d.bounds(n)[1]>=26]
    if not candidates: raise ValueError("no substantial opening paragraph visible")
    node=candidates[0]
    diagnostic=d.shell("dumpsys","activity",d.package+"/me.thenano.yamibo.yamibo_app.MainActivity","reader-ab-public-floor")
    matches=re.findall(r'(?m)^\s*ReaderABPublicFloor=([^\s]+)\s*$',diagnostic)
    if matches!=["535389,1,40718127,40718127"]:
        raise ValueError("Observer APK does not confirm the unique opening TID, page and PID")
    rows=[dict(threadId=535389,threadName=d.label(title),page=1,anchorPostId=40718127,postId=40718127)]
    source="app-read-only-fixed-query-via-system-dump"
    (out/"saved-public-floor.json").write_text(json.dumps(dict(source=source,rows=rows),indent=2),encoding="utf-8")
    if (len(rows)!=1 or rows[0]["threadName"]!=PINNED_THREAD or rows[0]["page"]!=1 or
            rows[0]["threadId"]!=535389 or rows[0]["postId"]!=40718127 or rows[0]["anchorPostId"]!=40718127):
        raise ValueError("pinned forum listing and actual reader history disagree")
    return _register_pixels(d,out,node,"forum-reference","forum-body",
                            dict(thread=rows[0],initial_progress=pagers[0],route="forum",identity_source=source))


def _register_pixels(d,out,node,refname,patchname,fields):
    import cv2,numpy as np
    gray=cv2.resize(cv2.imread(str(out/(refname+".png")),cv2.IMREAD_GRAYSCALE),VIDEO_SIZE)
    a,b,c,e=d.bounds(node)
    w,h=d.size
    x0=int(a*VIDEO_SIZE[0]/w)
    y0=int(b*VIDEO_SIZE[1]/h)
    x1=min(int(c*VIDEO_SIZE[0]/w),x0+142)
    y1=min(int(e*VIDEO_SIZE[1]/h),y0+60)
    # Detect the first complete line of ink in the selected body Text, excluding its padding.
    region=gray[y0:y1,x0:x1]
    foreground,background=np.percentile(region,(5,80))
    if background-foreground<12: raise ValueError("reference has no distinct foreground/background")
    ink=np.where((region<(foreground+background)/2).mean(axis=1)>.045)[0]
    if len(ink)<5: raise ValueError("reference has no recognizable body text glyphs")
    start=ink[0]; stop=next((i for i in range(start+3,len(region)-1)
                              if i not in ink and i+1 not in ink),ink[-1]+1)
    rect=[x0,max(0,y0+int(start)-2),x1,min(y1,y0+int(stop)+2)]
    patch=gray[rect[1]:rect[3],rect[0]:rect[2]]
    if float(patch.std())<5: raise ValueError("body reference lacks contrast")
    cv2.imwrite(str(out/(patchname+".png")),patch)
    meta={"package":d.package,"serial":d.serial,"text":d.label(node),
          "rect":rect,"display":d.size,"capture_size":VIDEO_SIZE,**fields}
    (out/(refname+".json")).write_text(json.dumps(meta,ensure_ascii=False,indent=2),encoding="utf-8")
    return meta


def _score(frame,patch,rect):
    import cv2
    h,w=frame.shape[:2]; x,y,r,b=rect
    roi=frame[max(0,y-24):min(h,b+24),max(0,x-7):min(w,r+7)]
    _,score,_,pos=cv2.minMaxLoc(cv2.matchTemplate(roi,patch,cv2.TM_CCOEFF_NORMED))
    return score,(pos[0]+max(0,x-7),pos[1]+max(0,y-24))


def _video_timestamps(path):
    import struct
    buf=path.read_bytes()
    magic=b"#VV1NSC0PET1ME2#"
    if buf.count(magic)!=1: raise ValueError("screenrecord Winscope v2 elapsed timestamps absent/ambiguous")
    pos=buf.index(magic)+len(magic)
    version,_,count=struct.unpack_from("<IqI",buf,pos)
    if version!=2 or count<2: raise ValueError("invalid Winscope v2 frame count")
    ts=struct.unpack_from(f"<{count}Q",buf,pos+16)
    if any(a>=b for a,b in zip(ts,ts[1:])): raise ValueError("nonmonotonic video timestamps")
    return ts


def _screen_frames(path):
    import cv2
    ts=_video_timestamps(path)
    cap=cv2.VideoCapture(str(path)); frames=[]
    try:
        while True:
            ok,frame=cap.read()
            if not ok: break
            frames.append(cv2.cvtColor(frame,cv2.COLOR_BGR2GRAY))
    finally:
        cap.release()
    if len(frames)!=len(ts): raise ValueError(f"video/Winscope frames disagree: {len(frames)}/{len(ts)}")
    return ts,frames


def _reader_slide(path, stamps, frames, t0, t1):
    """Measure the moving edge between the dark forum toolbar and light incoming reader."""
    import numpy as np
    pre=[i for i,t in enumerate(stamps) if t<t0]
    if not pre: return dict(status="unavailable",reason="no forum pre-tap camera frame")
    def profile(f):
        h=f.shape[0]
        return np.median(f[round(h*.11):round(h*.185)],axis=0)
    ref=profile(frames[pre[-1]])
    width=len(ref)
    if np.quantile(ref,.90)>95: return dict(status="unavailable",reason="pre-tap toolbar is not the registered dark scene")
    observed=[]
    for i in range(pre[-1],len(stamps)):
        if stamps[i]>t1: break
        bright=(profile(frames[i])-ref)>12
        prefix=np.concatenate(([0],np.cumsum(bright)))
        # Exact minimum classification disagreement for one incoming right-hand rectangle.
        errors=prefix+(width-np.arange(width+1))-(prefix[-1]-prefix)
        edge=int(np.argmin(errors)); mismatch=float(errors[edge])/width
        if mismatch>.06: continue
        covered=(width-edge)/width
        observed.append((stamps[i],edge,covered,mismatch))
        if covered>=.995:break
    with (path.parent/'entry-slide.csv').open('w',encoding='utf-8',newline='') as f:
        writer=csv.writer(f);writer.writerow(('elapsed_ns','reader_left_x_px','reader_coverage','classification_disagreement'))
        writer.writerows(observed)
    levels=[r[2] for r in observed]
    if not observed or levels[0]>.005 or levels[-1]<.995 or any(b<a-.04 for a,b in zip(levels,levels[1:])):
        return dict(status="unavailable",reason="no monotonic dark-to-light forum transition")
    first=next(i for i,r in enumerate(observed) if r[2]>.005)
    if sum(.05<x<.95 for x in levels)<2:
        return dict(status="bracket_only",reason="too few recorded partial positions to identify a midpoint",
                    start_ns=max(t0,observed[first-1][0]),end_ns=observed[-1][0],
                    partial_positions=sum(.005<x<.995 for x in levels))
    mid_in=next(i for i,r in enumerate(observed) if r[2]>=.15)
    mid_out=next(i for i,r in enumerate(observed) if r[2]>=.85)
    gaps=[dict(start_ns=a[0],end_ns=b[0],gap_ms=(b[0]-a[0])/1.e6,from_fraction=a[2],to_fraction=b[2])
          for a,b in zip(observed[mid_in-1:mid_out],observed[mid_in:mid_out+1])]
    return dict(status="observed",start_ns=max(t0,observed[first-1][0]),end_ns=observed[-1][0],
                middle_start_ns=max(t0,observed[max(0,mid_in-1)][0]),middle_end_ns=observed[mid_out][0],
                longest_middle_camera_gaps=sorted(gaps,key=lambda r:-r["gap_ms"])[:3])


def _video_timing(path,events,reference,patch):
    import cv2,numpy as np
    ts,frames=_screen_frames(path)
    t0,gesture=events["start_up"],events["swipe_down"]
    high=.79
    rect=reference["rect"]
    scores=[]; positions=[]
    for f in frames:
        # Search both horizontal slide directions across the full width. Include vertical motion
        # through the registered line position while avoiding unrelated UI and status-bar text.
        top=max(0,rect[1]-90); bottom=min(len(f),rect[3]+90)
        _,score,_,pos=cv2.minMaxLoc(cv2.matchTemplate(f[top:bottom],patch,cv2.TM_CCOEFF_NORMED))
        scores.append(score); positions.append((pos[0],pos[1]+top))
    pre=[i for i,t in enumerate(ts) if t<t0]
    if not pre or scores[pre[-1]]>=high:
        raise ValueError("missing clean pre-click frame or target visible before entry")
    index=next((i for i,t in enumerate(ts) if t>=t0 and scores[i]>=high),None)
    if index is None or ts[index]>gesture: raise ValueError("target body not visibly present before gesture")
    aligned=lambda i:scores[i]>=high and all(abs(a-b)<=2 for a,b in zip(positions[i],rect[:2]))
    settled=next((i for i in range(index,len(ts)-1) if ts[i]<gesture and aligned(i)
                  and (aligned(i+1) or ts[i+1]>=gesture)),None)
    if settled is None: raise ValueError("reader body did not settle at the registered position before gesture")
    with (path.parent/"entry-frames.csv").open("w",encoding="utf-8",newline="") as csvfile:
        writer=csv.writer(csvfile);writer.writerow(("elapsed_ns","score","x_px","y_px","aligned"))
        for i in range(pre[-1],next((i for i,t in enumerate(ts) if t>=gesture),len(ts)-1)+1):
            writer.writerow((ts[i],round(scores[i],4),*positions[i],aligned(i)))
    start=next((i for i,t in enumerate(ts) if t>=gesture),None)
    if not start: raise ValueError("gesture not covered by video")
    h,w=frames[start].shape
    x1,x2=w//8,w*7//8
    # A busy target may travel over 100 px by the next recorded frame.
    # Register multiple body text bands independently against the screen, then require
    # two different bands to agree on both the direction and amplitude of scrolling.
    before=frames[start-1]
    stripes=[(y,before[y:y+19,x1:x2]) for y in range(h//3,4*h//5,34)]
    stripes=[(y,s) for y,s in stripes if s.std()>7]
    moved=None; shift=None
    for i in range(start,min(len(ts),start+100)):
        offsets=[]
        for y,stripe in stripes:
            top=max(h//12,y-h//2)
            search=frames[i][top:y+24,x1-2:x2+2]
            _,match,_,pos=cv2.minMaxLoc(cv2.matchTemplate(search,stripe,cv2.TM_CCOEFF_NORMED))
            if match>.78:
                offsets.append(top+pos[1]-y)
        matches=[dy for dy in offsets if dy < -2 and sum(abs(other-dy)<=2 for other in offsets)>=2]
        if matches:
            moved=i;shift=float(np.median(matches));break
    if moved is None: raise ValueError("first swipe produced no observable upward movement of reader content")
    timing=dict(t0_ns=t0,t1_ns=ts[index],gesture_ns=gesture,t2_ns=ts[moved],
                t1_previous_ns=ts[index-1],t2_previous_ns=ts[moved-1],t1_match=round(scores[index],3),
                t0_t1_ms=(ts[index]-t0)/1e6,t0_t2_ms=(ts[moved]-t0)/1e6,
                gesture_to_response_ms=(ts[moved]-gesture)/1e6,first_scroll_displacement_px=shift,
                settled_ns=ts[settled],t0_settled_ms=(ts[settled]-t0)/1e6,
                settled_to_gesture_ms=(gesture-ts[settled])/1e6,
                t1_to_gesture_ms=(gesture-ts[index])/1e6,frames=len(ts))
    import hashlib
    def observed_marker(i):
        x,y=positions[i];height,width=patch.shape
        sample=frames[i][y:y+height,x:x+width]
        return dict(frame_index=i,rect=[x,y,x+width,y+height],gray_sha256=hashlib.sha256(sample.tobytes()).hexdigest(),
                    similarity=float(scores[i]))
    timing["observed_body_pixels"]={"first":observed_marker(index),"settled":observed_marker(settled)}
    if reference.get("route")=="forum":
        timing['reader_slide']=_reader_slide(path,ts,frames,t0,timing['t1_ns'])
    if "gear_up" in events:
        gear=events["gear_up"]
        target=cv2.resize(cv2.imread(str(path.parent/"settings-open.png"),0),VIDEO_SIZE)
        panel=lambda f:f[h//2:92*h//100,w//12:11*w//12].astype("float32")
        final=panel(frames[-1])
        if np.mean(abs(final-panel(target)))>4: raise ValueError("settings screenshot does not match the final recorded panel")
        errors=[np.mean(abs(panel(f)-final)) for f in frames]
        previous=next((i-1 for i,t in enumerate(ts) if t>=gear),None)
        if previous is None or previous<0 or errors[previous]<8: raise ValueError("settings already visible before gear tap")
        stable=next((i for i in range(previous+1,len(ts)-1) if max(errors[i:])<=.75),None)
        if stable is None: raise ValueError("settings panel did not reach its final position on camera")
        timing.update(gear_ns=gear,settings_stable_ns=ts[stable],settings_to_stable_ms=(ts[stable]-gear)/1e6)
    return timing


def _physical_endpoints(out, package, timing, marks, trace_shell):
    """Only accept the actual marked frame of the verified body; preserve ambiguous bounds."""
    from perfetto.trace_processor import TraceProcessor, TraceProcessorConfig
    if package not in ("me.thenano.yamibo.yamibo_app.abtag12","me.thenano.yamibo.yamibo_app.abcurrent"):
        raise ValueError("unexpected measured package")
    with TraceProcessor(trace=str(out/"flow.pftrace"),config=TraceProcessorConfig(bin_path=trace_shell)) as trace:
        def records(query):
            return [vars(r) for r in trace.query(query)]
        frames=records(f"""SELECT th.tid, s.ts, s.dur, s.name FROM slice s
             JOIN thread_track tr ON tr.id=s.track_id JOIN thread th USING(utid) JOIN process p USING(upid)
             WHERE p.name='{package}' AND th.tid=p.pid AND s.name GLOB 'Choreographer#doFrame *' AND s.dur>0""")
        present=records(f"""SELECT a.surface_frame_token token, a.display_frame_token display, a.layer_name layer,
            a.present_type app_present_type, a.ts+a.dur app_end_ns, d.ts sf_begin_ns, d.ts+d.dur present_ns, d.present_type sf_present_type
            FROM actual_frame_timeline_slice a JOIN process p ON p.upid=a.upid
            LEFT JOIN (SELECT f.* FROM actual_frame_timeline_slice f JOIN process sf USING(upid)
                WHERE sf.name='/system/bin/surfaceflinger' AND f.layer_name IS NULL AND f.dur>0
                AND f.present_type IN ('On-time Present','Late Present','Early Present')) d
              ON a.display_frame_token=d.display_frame_token
            WHERE p.name='{package}' AND a.layer_name LIKE '%{package}/me.thenano.yamibo.yamibo_app.MainActivity#%'
                AND a.dur>0""")
        bounds=records("SELECT start_ts,end_ts FROM trace_bounds")[0]
        if (bounds['start_ts']>timing['t0_ns'] or bounds['end_ts']<timing['settings_stable_ns']
                or not any(r['ts']<=timing['t1_ns'] and r['ts']+r['dur']>=timing['t0_ns'] for r in frames)
                or not any(r['present_ns'] is not None and timing['t0_ns']<=r['present_ns']<=timing['gesture_ns'] for r in present)):
            raise ValueError("Perfetto lost the entry-to-settings window or the app's frame timeline")
    def frame(event):
        candidates=[r for r in frames if r['tid']==event['tid'] and re.fullmatch(r'Choreographer#doFrame [0-9]+',r['name']) and r['ts']<=event['elapsed_ns']<r['ts']+r['dur']]
        if len(candidates)!=1: return dict(event=event,reason="missing or ambiguous main-thread doFrame")
        token=int(candidates[0]['name'].split()[-1])
        rows=[r for r in present if r['token']==token]
        if len(rows)!=1: return dict(event=event,token=token,reason="missing or ambiguous app or display token")
        return dict(event=event,**rows[0])
    first,gesture=timing['t0_ns'],timing['gesture_ns']
    draws=[r for r in marks if first<r['elapsed_ns']<gesture and r['point']=='draw']
    places=[r for r in marks if r['point']=='place' and r['elapsed_ns']>first]
    before=[r for r in places if r['elapsed_ns']<gesture]
    moved=[]
    if before:
        start=before[-1]
        moved=[r for r in places if gesture<=r['elapsed_ns']<=gesture+500_000_000 and abs(r['x']-start['x'])<2
               and r['y']<start['y']-3]
    result={}
    for key,events in (('t1',draws),('t2',moved)):
        low=max(first if key=='t1' else gesture,timing[key+'_previous_ns'])
        high=timing[key+'_ns']
        attempts=[]
        chosen=None
        for event in events:
            hit=frame(event);attempts.append(hit)
            if hit.get('app_present_type')=='Dropped Frame': continue
            if not hit.get('present_ns'): break
            if key=='t1':
                placement=[r for r in places if r['elapsed_ns']<=event['elapsed_ns']]
                if not placement or not (0<=placement[-1]['x']<720 and 0<=placement[-1]['y']<1280):
                    hit['reason']="body placement at draw not proven visible"
                    break
            elif abs((event['y']-before[-1]['y'])*VIDEO_SIZE[1]/1280-timing['first_scroll_displacement_px'])>2:
                hit['reason']="first recorded displacement differs from marked Text placement"
                break
            # Screenrecord encodes the virtual composition before the physical present fence.
            # Accept that ordering only when the matching camera buffer became ready during exactly
            # the same active display frame's interval. Never infer token from nearest timestamp.
            overlapping=[r for r in present if r['sf_begin_ns'] is not None and r['present_ns'] is not None
                         and max(r['app_end_ns'],r['sf_begin_ns'])<=high<=r['present_ns']]
            if low<=hit['present_ns']<=high:
                chosen=hit;hit['pixel_confirmation']="physical-present-within-pixel-bracket"
            elif low<high and len(overlapping)==1 and overlapping[0]['token']==hit['token']:
                chosen=hit;hit['pixel_confirmation']="unique-buffer-ready-during-matching-virtual-composition"
            else:
                hit['reason']="incompatible or non-unique camera/display timing"
                hit['overlapping_camera_tokens']=[r['token'] for r in overlapping]
            break
        result[key]=dict(pixel_interval_ns=[low,high],status="bound" if chosen else "ambiguous",
                         selected=chosen,attempts=attempts[:6])
    return result


def _input_log(d,pid):
    data=d.adb("logcat","-d","--pid",pid,"-v","raw","-s","ReaderABInput:I","*:S").decode(errors="replace")
    return [list(map(int,m.groups())) for m in re.finditer(r"(?m)^([01]) (\d+) (\d+) (\d+)\s*$",data)]


def _host_load():
    import os,subprocess,time
    if os.name!="nt" or not shutil.which("pwsh"): return {"available":False}
    script=('Get-Process -Name java,qemu-system* -ErrorAction SilentlyContinue | '
            'Select-Object Id,ProcessName,CPU | ConvertTo-Json -Compress -AsArray')
    run=subprocess.run(["pwsh","-NoProfile","-NonInteractive","-Command",script],capture_output=True,
                       text=True,creationflags=subprocess.CREATE_NO_WINDOW)
    return {"host_monotonic":time.monotonic(),"processes":json.loads(run.stdout.strip() or "[]")}


def _pull_finalized_recording(d, source, target, expected_boot, expected_size=None, retry_seconds=25, pause=1):
    """Verify the same guest's complete file and preserve failed transfers for inspection."""
    import hashlib
    import subprocess
    import time
    deadline = time.monotonic() + retry_seconds
    attempt = 0
    known = None
    last_error = None
    while True:
        attempt += 1
        partial = target.with_suffix(target.suffix + f".attempt-{attempt}")
        try:
            if d.shell("cat", "/proc/sys/kernel/random/boot_id") != expected_boot:
                raise ValueError("AVD boot changed during transfer: " + source)
            size = int(d.shell("stat", "-c", "%s", source))
            if size < 1024 or (expected_size is not None and size != expected_size):
                raise ValueError("device recording was not finalized: " + source)
            sha = d.shell("sha256sum", source).split()[0]
            if not re.fullmatch(r"[0-9a-f]{64}", sha) or (known is not None and known != (size,sha)):
                raise ValueError("finalized guest recording changed: " + source)
            known = (size, sha)
            d.adb("pull", source, str(partial))
            if d.shell("cat", "/proc/sys/kernel/random/boot_id") != expected_boot:
                raise ValueError("AVD boot changed while reading: " + source)
            with partial.open("rb") as file:
                received = hashlib.file_digest(file, "sha256").hexdigest()
            if partial.stat().st_size != size or received != sha:
                raise OSError("truncated or corrupted adb pull")
            partial.replace(target)
            return
        except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
            last_error = exc
            with (target.parent / "transfer-errors.txt").open("a",encoding="utf-8") as log:
                print(f"{source} attempt={attempt} partial_bytes={partial.stat().st_size if partial.exists() else 0}",
                      repr(exc), repr(getattr(exc,"stderr",None)), file=log)
            if time.monotonic() >= deadline:
                raise RuntimeError(f"Guest recording could not be retrieved: {source}; see transfer-errors.txt") from last_error
            time.sleep(pause)


def _capture(d,out,reference_dir,boot_id,trace_shell):
    import cv2,time,uuid
    from contextlib import ExitStack
    reference=json.loads((reference_dir/"forum-reference.json").read_text(encoding="utf-8"))
    template_file=reference_dir/"forum-body.png"
    patch=cv2.imread(str(template_file),cv2.IMREAD_GRAYSCALE)
    x,y=_forum_button(d)
    d.showcase("entry-control")
    pid=d.shell("pidof",d.package)
    if not pid.isdigit(): raise ValueError("expected exactly one reader process")
    start_log=len(_input_log(d,pid))
    d.shell("dumpsys","gfxinfo",d.package,"reset")
    host_before=_host_load()
    (out/"host-load.json").write_text(json.dumps({"before":host_before},indent=2),encoding="utf-8")
    unique=uuid.uuid4().hex
    clip="/data/local/tmp/reader-ab-"+unique+".mp4"
    trace="/data/misc/perfetto-traces/reader-ab-"+unique+".pftrace"
    (out/"capture-meta.json").write_text(json.dumps(dict(
        serial=d.serial,package=d.package,pid=pid,boot=boot_id,entry_point=[x,y],guest_video=clip,guest_trace=trace
    ),indent=2),encoding="utf-8")
    textconf='''buffers { size_kb: 98304 fill_policy: RING_BUFFER }
duration_ms: 75000
data_sources { config { name: "android.surfaceflinger.frametimeline" } }
data_sources { config { name: "linux.process_stats" process_stats_config { scan_all_processes_on_start: true } } }
data_sources { config { name: "linux.ftrace" ftrace_config {
  ftrace_events: "sched/sched_switch" ftrace_events: "sched/sched_waking"
  atrace_categories: "gfx" atrace_categories: "view" atrace_categories: "wm" atrace_categories: "input" atrace_categories: "dalvik"
  atrace_apps: "PACKAGE"
} } }'''.replace("PACKAGE",d.package)
    (out/"perfetto.pbtxt").write_text(textconf,encoding="ascii")
    def gfx(name):
        (out/(name+"-gfxinfo.txt")).write_bytes(d.adb("shell","dumpsys","gfxinfo",d.package,"framestats"))
    def collect():
        # On user builds the perfetto client can advertise a stopped producer while
        # its output file is still empty. Wait for the client's final byte count
        # before reading or removing the sole device copy.
        deadline=time.monotonic()+15
        expected=None
        while time.monotonic()<deadline:
            messages=d.adb("logcat","-d","--pid",tracepid,"-v","raw","-s","perfetto:I","*:S").decode(errors="replace")
            match=re.search(r"Wrote (\d+) bytes into "+re.escape(trace)+r"(?:\s|$)",messages)
            if match:
                expected=int(match[1])
                break
            time.sleep(.22)
        if expected is None or expected<1024:
            raise ValueError("Perfetto did not confirm a complete capture: "+trace)
        _pull_finalized_recording(d,clip,out/"flow.mp4",boot_id)
        _pull_finalized_recording(d,trace,out/"flow.pftrace",boot_id,expected)
        d.shell("rm","-f",clip,trace)
    hostshot=out/"live-monitor"; hostshot.mkdir()
    logs=[]; paint=[]
    screenshots=[]
    def physical_match():
        screenshots.append(time.monotonic_ns())
        d.adb("emu","screenrecord","screenshot",str(hostshot))
        name=max(hostshot.glob("*.png"),key=lambda f:f.stat().st_mtime_ns)
        frame=cv2.imread(str(name),cv2.IMREAD_GRAYSCALE)
        if frame is None: return False
        frame=cv2.resize(frame,VIDEO_SIZE)
        score,pos=_score(frame,patch,reference["rect"])
        return score>=.79 and all(abs(a-b)<=2 for a,b in zip(pos,reference["rect"][:2]))
    with ExitStack() as cleanup:
        tracepid=d.adb("shell","perfetto","--background-wait","--txt","-c","-","-o",trace,
                       data=textconf.encode("ascii")).decode().splitlines()[-1].strip()
        if not tracepid.isdigit(): raise ValueError("Perfetto did not start: "+tracepid)
        recpid=d.shell(f"screenrecord --size 432x768 --bit-rate 6000000 --time-limit 75 {clip} >/dev/null 2>&1 & echo $!")
        if not recpid.isdigit(): raise ValueError("screenrecord did not start")
        def finish():
            nonlocal logs
            d.shell("kill","-2",recpid)
            d.shell("kill","-15",tracepid)
            for _ in range(60):
                alive=d.shell(f"if kill -0 {recpid} 2>/dev/null || kill -0 {tracepid} 2>/dev/null; then echo yes; fi")
                if alive!="yes": break
                time.sleep(.1)
            (out/"host-load.json").write_text(json.dumps({"before":host_before,"after":_host_load()},indent=2),
                                              encoding="utf-8")
            logs=_input_log(d,pid)[start_log:]
            (out/"motion-events.json").write_text(json.dumps(logs),encoding="utf-8")
            paint_log=d.adb("logcat","-d","--pid",pid,"-v","raw","-s","ReaderABPaint:I","*:S").decode(errors="replace")
            for line in paint_log.splitlines():
                m=re.fullmatch(r"(\d+) (\d+) (draw|place)(?: (-?[\d.]+) (-?[\d.]+))?",line)
                if m:
                    item=dict(elapsed_ns=int(m[1]),tid=int(m[2]),point=m[3])
                    if m[4] is not None: item.update(x=float(m[4]),y=float(m[5]))
                    paint.append(item)
            if paint: (out/"paint-events.json").write_text(json.dumps(paint,indent=2),encoding="utf-8")
            # Persist input/paint/scheduler observations before a device recording transfer can fail.
            collect()
        cleanup.callback(finish)
        import subprocess,threading
        draw_observed=threading.Event()
        listener_done=threading.Event()
        listener=subprocess.Popen(["adb","-s",d.serial,"logcat","-T","1","--pid",pid,"-v","raw",
                                   "-s","ReaderABInput:I","ReaderABPaint:I","*:S"],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
        def seek_draw(lines):
            tapped=False
            for data in lines:
                words=data.decode(errors="replace").split()
                if len(words)==4 and words[0]=="1" and words[2:]==[str(x),str(y)]:
                    tapped=True
                if tapped and len(words)==3 and words[1]==pid and words[2]=="draw":
                    draw_observed.set()
                    return
        def observe():
            try: seek_draw(listener.stdout)
            finally: listener_done.set()
        watcher=threading.Thread(target=observe,daemon=True)
        watcher.start()
        def stop_listener():
            listener.terminate()
            listener.wait(timeout=4)
            watcher.join(timeout=4)
        cleanup.callback(stop_listener)
        time.sleep(.4)
        if physical_match(): raise ValueError("reader marker present on pre-tap search page")
        start_wait=time.monotonic_ns()
        d.shell("input","tap",x,y)
        until=time.monotonic()+18
        recovered_stream=False
        while draw_observed and not draw_observed.wait(timeout=.2):
            if time.monotonic()>until:
                d.showcase("missing-target-draw")
                raise ValueError("no first matching target-body draw after the actual entry tap")
            if listener_done.is_set():
                # Only recover a lost stream. Healthy trials do no ADB work while waiting for draw.
                import subprocess
                try:
                    if d.shell("cat","/proc/sys/kernel/random/boot_id")!=boot_id:
                        raise ValueError("guest rebooted while waiting for body draw")
                    backlog=d.adb("logcat","-d","--pid",pid,"-v","raw","-s","ReaderABInput:I","ReaderABPaint:I","*:S")
                    seek_draw(backlog.splitlines());recovered_stream=True
                except (subprocess.CalledProcessError,subprocess.TimeoutExpired) as error:
                    with (out/"observer-stream-errors.txt").open("a",encoding="utf-8") as log:
                        print(repr(error),repr(getattr(error,"stderr",None)),file=log)
        stream_ready=time.monotonic_ns()
        while not physical_match():
            if time.monotonic()>until:
                d.showcase("no-first-content")
                raise ValueError("real reader body did not appear at the registered first-floor position")
            time.sleep(.04)
        (out/"observer-cost.json").write_text(json.dumps(dict(
            monitor="wait_for_draw", before_entry=1,
            recovered_draw_stream=recovered_stream,
            screenshots_after_entry=len(screenshots)-1, total_screenshots=len(screenshots),
            post_tap_to_first_probe_ms=(screenshots[1]-start_wait)/1e6,
            draw_stream_wait_ms=(stream_ready-start_wait)/1e6,
            post_tap_to_success_ms=(time.monotonic_ns()-start_wait)/1e6, probes_host_ns=screenshots),indent=2),encoding="utf-8")
        # Every interval, including live observation and input injection latency, stays in T0->T2.
        sx,sy0,sy1=360,950,430
        d.shell("input","swipe",sx,sy0,sx,sy1,220)
        time.sleep(.72);gfx("entry-first-swipe")
        for number in (2,3):
            d.shell("dumpsys","gfxinfo",d.package,"reset")
            d.shell("input","swipe",sx,sy0,sx,sy1,220)
            time.sleep(.72);gfx(f"swipe-{number}")
        # Default scrolling reader toggles its menu anywhere on the non-interactive background.
        # The center may contain rate, reply, image or link actions, depending on the fling.
        scroll_tree=d.snapshot()
        clickable=[d.bounds(n) for n in scroll_tree.iter("node") if n.get("clickable")=="true"]
        menu_point=next(((x,y) for x in (6,714,10,710) for y in (640,530,740,420,850)
                         if not any(a<=x<r and b<=y<t for a,b,r,t in clickable)),None)
        if menu_point is None: raise ValueError("no unoccupied reader margin for settings menu")
        d.shell("input","tap",*menu_point)
        tree=d.snapshot()
        gear=d.find(tree,"設定")
        if gear is None:
            d.showcase("missing-settings-control",tree)
            raise ValueError("normal reader menu did not expose settings")
        gx,gy=_center(d.bounds(gear))
        d.showcase("settings-control",tree)
        d.shell("dumpsys","gfxinfo",d.package,"reset")
        d.shell("input","tap",gx,gy)
        time.sleep(.9);gfx("settings")
        settings=d.snapshot();d.showcase("settings-open",settings)
        labels=[d.label(n) for n in settings.iter("node") if d.label(n)]
        if not any(any(v in s for v in ("字體","字型","字号","字級","行距","閱讀方式","文字大小")) for s in labels):
            raise ValueError("reader settings panel content missing: "+repr(labels[-20:]))
    if d.shell("pidof",d.package)!=pid:
        raise ValueError("measured reader process exited or restarted during capture")
    return _finish_capture(out,d.package,logs,paint,trace_shell,reference,template_file,patch,(x,y),(gx,gy))


def _finish_capture(out,package,logs,paint,trace_shell,reference,template_file,patch,entry_point,settings_point):
    x,y=entry_point
    gx,gy=settings_point
    sx,sy0,sy1=360,950,430
    choose=lambda action,px,py:[n for k,n,fx,fy in logs if k==action and abs(fx-px)<=2 and abs(fy-py)<=2]
    starts,downs,ups,gears=choose(1,x,y),choose(0,sx,sy0),choose(1,sx,sy1),choose(1,gx,gy)
    if len(starts)!=1 or len(downs)!=3 or len(ups)!=3 or len(gears)!=1:
        raise ValueError("incomplete Android input timestamps: "+repr(logs))
    windows=[dict(name="entry",start_ns=starts[0],end_ns=downs[0])]
    windows.extend(dict(name=f"swipe-{i+1}",start_ns=down,end_ns=up+600_000_000)
                   for i,(down,up) in enumerate(zip(downs,ups)))
    windows.extend(dict(name=f"swipe-{i+1}-drag",start_ns=down,end_ns=up)
                   for i,(down,up) in enumerate(zip(downs,ups)))
    windows.append(dict(name="settings",start_ns=gears[0],end_ns=gears[0]+850_000_000))
    (out/"windows.json").write_text(json.dumps(windows,indent=2),encoding="utf-8")
    times=_video_timing(out/"flow.mp4",{"start_up":starts[0],"swipe_down":downs[0],"gear_up":gears[0]},reference,patch)
    if not paint:
        raise ValueError("Actual body-draw marker missing from observer APK")
    endpoints=_physical_endpoints(out,package,times,paint,str(trace_shell))
    (out/"physical-endpoints.json").write_text(json.dumps(endpoints,indent=2),encoding="utf-8")
    times["physical_present_ns"]={k:v["selected"]["present_ns"] if v["selected"] else None for k,v in endpoints.items()}
    import hashlib
    times["body_sha256"]=hashlib.sha256(reference["text"].encode("utf-8")).hexdigest()
    times["marker_pixels_sha256"]=hashlib.sha256(patch.tobytes()).hexdigest()
    times["template_png_sha256"]=hashlib.sha256(template_file.read_bytes()).hexdigest()
    times["thread"]=reference.get("thread")
    times["body_rect_px"]=reference["rect"]
    times["history_restore"]=reference.get("first_floor_restore_percent")
    windows[-1]["end_ns"]=times["settings_stable_ns"]
    slide=times.get("reader_slide",{})
    if slide.get("status")=="bracket_only":
        windows.append(dict(name="entry-slide-bracket",start_ns=slide["start_ns"],end_ns=slide["end_ns"]))
    if slide.get("status")=="observed":
        windows.extend((dict(name="entry-slide",start_ns=slide["start_ns"],end_ns=slide["end_ns"]),
                        dict(name="entry-slide-middle",start_ns=slide["middle_start_ns"],end_ns=slide["middle_end_ns"])))
    windows.extend((dict(name="entry-through-content",start_ns=starts[0],end_ns=times["t1_ns"]),
                    dict(name="entry-through-settle",start_ns=starts[0],end_ns=times["settled_ns"])))
    (out/"windows.json").write_text(json.dumps(windows,indent=2),encoding="utf-8")
    (out/"timing.json").write_text(json.dumps(times,indent=2),encoding="utf-8")
    return times


def android_summary(argv):
    import numpy as np
    p=argparse.ArgumentParser(description="Compare paired full-path latency taking capture intervals and A/A noise into account")
    p.add_argument("--aa",required=True,type=Path)
    p.add_argument("--ab",required=True,type=Path)
    p.add_argument("--output",required=True,type=Path)
    args=p.parse_args(argv)
    def pairs(path,purpose):
        env=json.loads((path.parent/"environment.json").read_text(encoding="utf-8"))
        if env["purpose"]!=purpose or type(env["rounds"]) is not int or not 1<=env["rounds"]<=1000:
            raise ValueError("invalid declared matrix purpose or round count")
        rows=json.loads(path.read_text(encoding="utf-8")) if path.is_file() else []
        rounds=range(1,env["rounds"]+1)
        lookup={(r["round"],r["side"]):r for r in rows}
        if len(lookup)!=len(rows) or any(side not in ("baseline","candidate") or n not in rounds for n,side in lookup):
            raise ValueError("duplicate or unknown side in paired capture")
        complete=[]; invalid=[]
        for n in rounds:
            both=[lookup.get((n,s)) for s in ("baseline","candidate")]
            if all(row and row.get("status")=="OK" for row in both):
                for r in both:
                    raw=path.parent/f"{n:02}-{r['side']}"
                    result=json.loads((raw/"result.json").read_text(encoding="utf-8"))
                    timing=json.loads((raw/"timing.json").read_text(encoding="utf-8"))
                    if (result!={k:v for k,v in r.items() if k not in ("round","side")} or
                            any(result.get(k)!=v for k,v in timing.items()) or
                            result["binary_sha256"]!=env[r["side"]+"_sha256"] or
                            result["route"]!=env["route"] or (env.get("boot_id") and r.get("boot_id")!=env["boot_id"])):
                        raise ValueError("aggregated row differs from its recorded trial or declared APK")
                    if env.get("wait_for_body_draw"):
                        observer=json.loads((raw/"observer-cost.json").read_text(encoding="utf-8"))
                        if (observer["monitor"]!="wait_for_draw" or observer["before_entry"]!=1
                                or observer["screenshots_after_entry"]<1 or observer["draw_stream_wait_ms"]<=0
                                or observer["post_tap_to_first_probe_ms"]<observer["draw_stream_wait_ms"]):
                            raise ValueError("reported low-interference monitor disagrees with its per-trial raw record")
                    windows=json.loads((raw/"windows.json").read_text(encoding="utf-8"))
                    by_name={w["name"]:w for w in windows}
                    if (by_name["entry"]["start_ns"]!=r["t0_ns"] or by_name["swipe-1-drag"]["start_ns"]!=r["gesture_ns"]
                            or by_name["settings"]["end_ns"]!=r["settings_stable_ns"]
                            or not r["t0_ns"]<r["t1_ns"]<=r["gesture_ns"]<r["t2_ns"]):
                        raise ValueError("capture and actual input windows disagree")
                    for k in ("t1","t2"):
                        if (r["t0_"+k+"_ms"]!=(r[k+"_ns"]-r["t0_ns"])/1e6
                                or not r[k+"_previous_ns"]<r[k+"_ns"]):
                            raise ValueError("reported latency disagrees with bracketed frame timestamps")
                    if any(not (raw/file).is_file() or (raw/file).stat().st_size<1000 for file in ("flow.mp4","flow.pftrace")):
                        raise ValueError("recording or trace missing from trial")
                    stamps=_video_timestamps(raw/"flow.mp4")
                    brackets=set(zip(stamps,stamps[1:]))
                    if any((r[k+"_previous_ns"],r[k+"_ns"]) not in brackets for k in ("t1","t2")):
                        raise ValueError("reported video interval absent from Android screenrecord metadata")
                    if "observed_body_pixels" in r:
                        import cv2,hashlib
                        video=cv2.VideoCapture(str(raw/"flow.mp4"))
                        try:
                            for stage,field in (("first","t1_ns"),("settled","settled_ns")):
                                marker=r["observed_body_pixels"][stage]
                                if stamps[marker["frame_index"]]!=r[field]: raise ValueError("incorrect marker frame")
                                video.set(cv2.CAP_PROP_POS_FRAMES,marker["frame_index"])
                                ok,bgr=video.read()
                                if not ok: raise ValueError("missing marker video frame")
                                x,y,w,h=marker["rect"]
                                gray=cv2.cvtColor(bgr,cv2.COLOR_BGR2GRAY)[y:h,x:w]
                                if gray.size==0 or hashlib.sha256(gray.tobytes()).hexdigest()!=marker["gray_sha256"]:
                                    raise ValueError("reported first/settled pixels differ from original recording")
                        finally:
                            video.release()
                    if env.get("physical_markers_required") and "physical_present_ns" not in r:
                        raise ValueError("physical body marker missing in precision matrix")
                    if 'physical_present_ns' in r:
                        import os
                        paint=json.loads((raw/'paint-events.json').read_text(encoding='utf-8'))
                        stored=json.loads((raw/'physical-endpoints.json').read_text(encoding='utf-8'))
                        fresh=_physical_endpoints(raw,r['package'],timing,paint,os.environ.get('READER_TRACE_SHELL'))
                        physical={k:v['selected']['present_ns'] if v['selected'] else None for k,v in fresh.items()}
                        if stored!=fresh or physical!=r['physical_present_ns']:
                            raise ValueError("physical endpoint differs from trace or measured body marker")
                complete.append(both)
            else:
                invalid.append(n)
        return complete,invalid
    configs=[json.loads((p.parent/"environment.json").read_text(encoding="utf-8")) for p in (args.aa,args.ab)]
    for flag in ("wait_for_body_draw","public_body_replay","fixture"):
        if configs[0].get(flag)!=configs[1].get(flag):
            raise ValueError("A/A and A/B used different observation or fixture conditions")
    aa,aa_invalid=pairs(args.aa,"aa"); ab,ab_invalid=pairs(args.ab,"ab")
    if not aa or not ab:
        args.output.write_text(json.dumps(dict(verdict="inconclusive",aa_pairs=len(aa),ab_pairs=len(ab),
            aa_invalid=aa_invalid,ab_invalid=ab_invalid,reason="no complete A/A or A/B pair")),encoding="utf-8")
        raise SystemExit(2)
    baselines={a["binary_sha256"] for a,b in ab}
    candidates={b["binary_sha256"] for a,b in ab}
    noise_binaries={r["binary_sha256"] for pair in aa for r in pair}
    if len(baselines)!=1 or len(candidates)!=1 or baselines==candidates or noise_binaries!=baselines:
        raise ValueError("mixed or incomparable APK hashes")
    allrows=[row for pair in aa+ab for row in pair]
    routes={r["route"] for r in allrows}
    bodies={r["body_sha256"] for r in allrows}
    if len(routes)!=1 or len(bodies)!=1 or not re.fullmatch(r"[a-f0-9]{64}",next(iter(bodies))):
        raise ValueError("mixed routes or reference post text")
    observed_summary={}
    for key in ("first","settled"):
        events=[r["observed_body_pixels"][key] for r in allrows if "observed_body_pixels" in r]
        if events:
            from collections import Counter
            observed_summary[key]={"samples":len(events),"rects":dict(Counter(",".join(map(str,e["rect"])) for e in events)),
                "actual_video_gray_sha256_counts":dict(Counter(e["gray_sha256"] for e in events)),
                "min_similarity":min(e["similarity"] for e in events),"max_similarity":max(e["similarity"] for e in events)}
    geometry={tuple(r["body_rect_px"]) for r in allrows}
    pixels={r["marker_pixels_sha256"] for r in allrows}
    if routes=={"forum"} and any(r["thread"]["threadId"]!=535389 or r["thread"]["postId"]!=40718127 for r in allrows):
        raise ValueError("not all samples share the registered forum TID and PID")
    comparable_geometry=len(geometry)==1 and len(pixels)==1
    boots={r.get("boot_id") for r in allrows}
    comparable_boot=len(boots)==1
    optimization_states={tuple(r["dexopt"]) if r.get("dexopt") else () for r in allrows}
    comparable_optimization=len(optimization_states)==1 and (() not in optimization_states or not any("dexopt" in r for r in allrows))
    report=dict(aa_pairs=len(aa),ab_pairs=len(ab),aa_invalid=aa_invalid,ab_invalid=ab_invalid,
                baseline_sha256=next(iter(baselines)),candidate_sha256=next(iter(candidates)),
                threshold_ms=1,route=next(iter(routes)),body_sha256=next(iter(bodies)),
                body_rects_px=sorted(geometry),marker_pixel_sha256s=sorted(pixels),
                same_final_body_position=comparable_geometry,android_art_states=[list(state) for state in sorted(optimization_states)],
                same_art_state=comparable_optimization,device_boot_ids=list(boots),same_boot=comparable_boot,metrics={})
    grid=[min(r["t0_ns"]%1_000_000,1_000_000-r["t0_ns"]%1_000_000) for r in allrows]
    report["visual_evidence"]={"template_encoded_png_sha256s":sorted({r["template_png_sha256"] for r in allrows if "template_png_sha256" in r}),
        "template_decoded_gray_sha256s":sorted(pixels),"actual_video_markers":observed_summary,
        "coordinate_space":"432x768 grayscale screenrecord, [left,top,right,bottom) inclusive start exclusive end"}
    environment=json.loads((args.ab.parent/"environment.json").read_text(encoding="utf-8"))
    report["artifacts"]={k:environment[k] for k in ("baseline_apk_bytes","candidate_apk_bytes","baseline_sha256",
                         "candidate_sha256","avd_name","boot_id","device_fingerprint") if k in environment}
    report["monitor"]="wait_for_draw" if configs[1].get("wait_for_body_draw") else "poll_screenshots"
    report["clock"]={"t0":"Android ACTION_UP eventTimeNanos translated to BOOTTIME; adb input tap",
                     "max_t0_distance_to_whole_millisecond_ns":max(grid),
                     "observed_t0_on_millisecond_grid":max(grid)<100_000,
                     "physical_endpoint":"SurfaceFlinger actual display-frame present fence, uniquely mapped from body Text draw or placement, with camera evidence",
                     "fallback_endpoint":"consecutive guest screenrecord frames bracketing pixel onset"}
    rng=np.random.default_rng(49163)
    draws=rng.integers(0,len(ab),(10000,len(ab)))
    for name,prev,end in (("t0_t1_ms","t1_previous_ns","t1_ns"),("t0_t2_ms","t2_previous_ns","t2_ns")):
        key=end.removesuffix('_ns')
        physical_requested=any("physical_present_ns" in r for r in allrows)
        def endpoint(r):
            ns=r.get("physical_present_ns",{}).get(key)
            if ns is not None:
                return (ns-r["t0_ns"])/1e6
            return r[name]
        def lower_bound(r):
            if r.get("physical_present_ns",{}).get(key) is not None: return endpoint(r)
            return (max(r["t0_ns"],r[prev])-r["t0_ns"])/1e6
        durations=lambda pairset: np.array([[endpoint(r) for r in both] for both in pairset],dtype=float)
        v=durations(ab); noise=durations(aa)
        lo=np.array([[lower_bound(r) for r in both] for both in ab])
        quant=np.array([[(r[end]-r[prev])/1e6 for r in both] for both in ab])
        missing=sum(r.get("physical_present_ns",{}).get(key) is None for r in allrows)
        metrics={"frame_interval_ms":{"median":float(np.median(quant)),"max":float(quant.max())},
                 "unique_physical_matches":len(allrows)-missing,"samples":len(allrows),
                 "unbound_physical_samples":missing,"physical_timing_requested":physical_requested}
        for set_name,pairset,observed in (("aa",aa,noise),("ab",ab,v)):
            deltas=observed[:,1]-observed[:,0]
            limit=int(np.argmax(abs(deltas)))
            a,b=pairset[limit]
            order_medians={}
            for side,parity in (("baseline",1),("candidate",0)):
                series=[float(d) for d,(first,_) in zip(deltas,pairset) if first["round"] % 2 == parity]
                if series:
                    order_medians[side]=float(np.percentile(series,50,method="inverted_cdf"))
            metrics[set_name+"_paired_candidate_minus_baseline_ms"]={
                "p50":float(np.percentile(deltas,50,method="inverted_cdf")),
                "p95":float(np.percentile(deltas,95,method="inverted_cdf")),
                "min":float(min(deltas)),"max":float(max(deltas)),
                "candidate_slower_by_over_1ms":int(np.sum(deltas>1)),
                "candidate_faster_by_over_1ms":int(np.sum(deltas < -1)),
                "absolute_delta_within_1ms":int(np.sum(np.abs(deltas)<=1)),
                "p50_by_first_side":order_medians,
                "largest_absolute_pair":{"round":a["round"],"baseline_ms":float(observed[limit,0]),
                    "candidate_ms":float(observed[limit,1]),"delta_ms":float(deltas[limit]),
                    "baseline_raw":f"{a['round']:02}-baseline","candidate_raw":f"{b['round']:02}-candidate"}}
        for percentile in (50,95):
            q=lambda array,axis=0:np.percentile(array,percentile,axis=axis,method="inverted_cdf")
            # Each timestamp is bracketed by the preceding capture and first matching capture.
            # Resample whole date-matched pairs, retaining interleaved side order per round.
            lower=q(lo[:,1][draws],1)-q(v[:,0][draws],1)
            upper=q(v[:,1][draws],1)-q(lo[:,0][draws],1)
            base,candidate=q(v)
            aa_delta=float(q(noise[:,1])-q(noise[:,0]))
            aa_radius=float(np.percentile(abs(noise[:,1]-noise[:,0]),95,method="inverted_cdf"))
            low=float(np.percentile(lower,2.5))-aa_radius
            high=float(np.percentile(upper,97.5))+aa_radius
            status=("inconclusive" if aa_invalid or ab_invalid or len(ab)<40 or len(aa)<7 or not comparable_geometry or not comparable_optimization or not comparable_boot or (physical_requested and missing) else
                    "regression" if low>1 else "within_1ms" if high<=1 else "inconclusive")
            metrics[f"p{percentile}"]=dict(baseline_ms=float(base),candidate_ms=float(candidate),
                candidate_minus_baseline_ms=float(candidate-base),aa_delta_ms=aa_delta,
                aa_p95_absolute_paired_difference_ms=aa_radius,uncertainty_ms=[low,high],verdict=status)
        report["metrics"][name]=metrics
    results={metric[q]["verdict"] for metric in report["metrics"].values() for q in ("p50","p95")}
    report["verdict"]="regression" if "regression" in results else "inconclusive" if "inconclusive" in results else "within_1ms"
    args.output.write_text(json.dumps(report,indent=2),encoding="utf-8")
    print(json.dumps(report,indent=2))
    raise SystemExit({"within_1ms":0,"regression":1,"inconclusive":2}[report["verdict"]])


if __name__ == "__main__":
    if sys.argv[1:2] == ["android-forum"]:
        android_forum_benchmark(sys.argv[2:])
    elif sys.argv[1:2] == ["android-summary"]:
        android_summary(sys.argv[2:])
    else:
        main()
