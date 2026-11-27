"""Run five process-cold tag-12/candidate pairs on a single owned simulator.

Every arm gets an independent container, source tree and retained raw recording.
A failed arm stays in the ledger with its original artifacts.
"""
import json
import os
import math
import random
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time


def run(command, **kwargs):
    return subprocess.run(command, check=True, **kwargs)


def order(pair, control=False):
    first,second=("candidate-a","candidate-b") if control else ("tag12","candidate")
    return (first,second) if pair%2 else (second,first)


def assess(path):
    result = json.loads((path/"reader-perf.json").read_text())
    assert result["requested"]==1 and result["usable"]==1
    assert not result["acquisition_failures"] and result["clock_residual_p95_ms"] <= 35
    assert result["settled_glyph"]["usable"]==1
    sample = result["samples"][0]
    assert sample["controlled_network"]["thread_retrieved"]
    timings={"t1_ms": sample["glyph_t1_t0_ms"], "t2_ms": sample["t2_t0_ms"],
            "gesture_response_ms": sample["gesture_to_response_ms"],
            "harness_delay_ms": sample["glyph_harness_delay_ms"],
            "clock_residual_p95_ms":result["clock_residual_p95_ms"]}
    if "board" in sample.get("navigation_to_correct_content_ms",{}):
        timings["board_touch_to_content_ms"]=sample["navigation_to_correct_content_ms"]["board"]
    return timings


def main(mode,control=False):
    root = Path(os.environ["GITHUB_WORKSPACE"]).resolve()
    output = Path(os.environ["APPLE_VALIDATE_OUTPUT"]).resolve()
    block = int(os.environ.get("READER_PAIR_BLOCK") or "0")
    assert mode in ("Novel","Forum") and 0<=block<8
    receipts = os.environ["READER_FRAMEWORK_RECEIPT"].split(",")
    assert len(receipts)==(1 if control else 2) and all(re.fullmatch(r"\d+:[a-f0-9]{40}:[a-f0-9]{64}",v) for v in receipts)
    if control:
        assert block==0
        receipt_by_arm={"candidate-a":receipts[0],"candidate-b":receipts[0]}
    else:
        assert receipts[0]!=receipts[1]
        receipt_by_arm=dict(zip(("candidate","tag12"),receipts))
    runtime="com.apple.CoreSimulator.SimRuntime.iOS-26-4"
    kind="com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro"
    sim=subprocess.check_output(["xcrun","simctl","create",f"ReaderPairs-{mode}-{block}",kind,runtime],text=True).strip()
    ledger={"run":os.environ["GITHUB_RUN_ID"],"attempt":os.environ["GITHUB_RUN_ATTEMPT"],"block":block,
            "mode":mode,"simulator":sim,"device_type":kind,"runtime":runtime,"receipts":receipt_by_arm,"pairs":[]}
    def save(): (output/"paired-reader.json").write_text(json.dumps(ledger,indent=2))
    bundle="com.yamibo.bbs.readermodesqa"
    nominal=7 if control else 5
    count=int(os.environ.get("READER_ROUND_COUNT") or nominal)
    assert 1<=count<=nominal
    first_index=1 if control else block*5+1
    pair_indices=range(first_index,first_index+count)
    ledger["planned_in_block"]=count
    ledger["short_smoke"]=count<nominal
    ledger["comparison"]="same-version" if control else "tag12"
    try:
        run(["xcrun","simctl","boot",sim]);run(["xcrun","simctl","bootstatus",sim,"-b"],timeout=900)
        for pair in pair_indices:
            p={"index":pair,"order":list(order(pair,control)),"arms":{},"valid":False}
            ledger["pairs"].append(p);save()
            for arm in order(pair,control):
                raw=output/f"{pair:02d}-{arm}";raw.mkdir()
                row={"relative_evidence":raw.name};p["arms"][arm]=row
                if subprocess.run(["xcrun","simctl","get_app_container",sim,bundle,"data"],capture_output=True).returncode==0:
                    run(["xcrun","simctl","uninstall",sim,bundle])
                with tempfile.TemporaryDirectory(prefix=f"reader-{pair}-{arm}-") as temp:
                    temp=Path(temp);work=temp/"workspace"
                    run(["git","-C",str(root),"worktree","add","--quiet","--detach",str(work),"HEAD"])
                    try:
                        env=dict(os.environ,APPLE_VALIDATE_OUTPUT=str(raw),RUNNER_TEMP=str(temp),
                            READER_PAIRED_SIMULATOR=sim,READER_ROUND_COUNT="",READER_FRAMEWORK_RECEIPT=receipt_by_arm[arm],
                            READER_PAIR_DERIVED=str(temp/"derived"),
                            READER_FRAMEWORK_CACHE=str(Path(os.environ["RUNNER_TEMP"])/"reader-framework-cache"))
                        args=(["--tag-12"] if arm=="tag12" else [])+["--network","fast","--perf",mode,"cold","1","Release"]
                        start=time.monotonic()
                        script=".github/scripts/apple-validate/reader-modes/run_perf.sh"
                        with (raw/"paired-run.log").open("w") as log:
                            ret=subprocess.run(["bash",script,*args],cwd=work,env=env,stdout=log,stderr=subprocess.STDOUT,timeout=720)
                        row["exit_code"]=ret.returncode;row["total_seconds"]=time.monotonic()-start
                        if ret.returncode==0:
                            row["metrics"]=assess(raw)
                            row["corpus"]=json.loads((raw/"contract.json").read_text())
                            assert json.loads((raw/"simulator.json").read_text())["udid"]==sim
                            assert json.loads((raw/"reused-framework.json").read_text())["receipt"]==receipt_by_arm[arm]
                    except (AssertionError,ValueError,KeyError,FileNotFoundError,subprocess.TimeoutExpired) as error:
                        row["error"]=str(error) or type(error).__name__
                    finally:
                        run(["git","-C",str(root),"worktree","remove","--force",str(work)])
                    save()
            a,b=(p["arms"][name] for name in order(1,control))
            p["valid"]=all("metrics" in arm and "error" not in arm and arm["exit_code"]==0 for arm in [a,b]) and a["corpus"]==b["corpus"]
            if p["valid"]:
                p["second_minus_first_ms"]={k:b["metrics"][k]-a["metrics"][k] for k in a["metrics"] if k not in ["clock_residual_p95_ms"] and k in b["metrics"]}
            print("READER_PAIR "+json.dumps({k:v for k,v in p.items() if k!="arms"}),flush=True);save()
    finally:
        subprocess.run(["xcrun","simctl","shutdown",sim],timeout=60);subprocess.run(["xcrun","simctl","delete",sim],timeout=60)
    if sum(v["valid"] for v in ledger["pairs"])!=len(pair_indices):raise SystemExit("Incomplete paired block")


def summarize(paths):
    """Combine distinct preregistered attempts. Refuse duplicated or mixed comparisons."""
    rows={}
    hosts={}
    host_by_index={}
    smoke_schedule=set()
    key=None
    for path in map(Path,paths):
        group=json.loads(path.read_text(encoding="utf8"))
        identity=(group["mode"],group["comparison"],group["runtime"],group["device_type"],group["receipts"],group.get("short_smoke",False))
        if key is None: key=identity
        if identity!=key:raise ValueError("Mixed route, runners or build receipts")
        hosts[group["run"]]=group["simulator"]
        if group.get("short_smoke"):
            first_index=1 if group["comparison"]=="same-version" else group["block"]*5+1
            smoke_schedule.update(range(first_index,first_index+group["planned_in_block"]))
        for pair in group["pairs"]:
            if pair["index"] in rows:
                raise ValueError("Overlapping preregistered pair index: "+str(pair["index"]))
            rows[pair["index"]]=pair
            host_by_index[pair["index"]]=group["run"]
    control=key[1]=="same-version"
    requested=7 if control else 40
    indices=smoke_schedule if key[5] else set(range(1,requested+1))
    if not set(rows)<=indices:raise ValueError("Unscheduled trial index")
    usable=[p for p in rows.values() if p["valid"]]
    def rank(values,q):
        return sorted(values)[math.ceil(q*len(values))-1]
    def quantiles(values):
        return {"p50":rank(values,.5),"p95":rank(values,.95)}
    result={"mode":key[0],"comparison":key[1],"receipts":key[4],"short_smoke":key[5],"requested":len(indices),"formal_target":requested,"attempted":len(rows),
            "usable":len(usable),"missing_indices":sorted(indices-set(rows)),
            "rejected_indices":sorted(p["index"] for p in rows.values() if not p["valid"]),
            "host_simulator_by_run":hosts,"metrics":{}}
    first,second=order(1,control)
    if usable:
        for metric in set.intersection(*(set(p["second_minus_first_ms"]) for p in usable)):
            a=[p["arms"][first]["metrics"][metric] for p in usable]
            b=[p["arms"][second]["metrics"][metric] for p in usable]
            delta=[y-x for x,y in zip(a,b)]
            rng=random.Random(140)
            medians=[rank(rng.choices(delta,k=len(delta)),.5) for _ in range(2000)]
            blocks={}
            for pair,value in zip(usable,delta):
                blocks.setdefault(host_by_index[pair["index"]],[]).append(value)
            block_medians=[]
            if len(blocks)>1:
                for _ in range(2000):
                    sampled=rng.choices(list(blocks.values()),k=len(blocks))
                    block_medians.append(rank([v for block in sampled for v in block],.5))
            result["metrics"][metric]={first:quantiles(a),second:quantiles(b),"paired_differences":quantiles(delta),
                "difference_of_p95":rank(b,.95)-rank(a,.95),
                "within_sample_paired_median_bootstrap95_ms":[rank(medians,.025),rank(medians,.975)],
                "hosted_job_block_median_bootstrap95_ms":[rank(block_medians,.025),rank(block_medians,.975)] if block_medians else None,
                "hosted_job_blocks":len(blocks)}
    return result


if __name__=="__main__":
    if sys.argv[1]=="--summarize":print(json.dumps(summarize(sys.argv[2:]),indent=2))
    else: main(sys.argv[1],"--control" in sys.argv[2:])
