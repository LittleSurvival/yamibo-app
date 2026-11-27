"""Run five process-cold tag-12/candidate pairs on a single owned simulator.

Every arm gets an independent container, source tree and retained raw recording.
A failed arm stays in the ledger with its original artifacts.
"""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time


def run(command, **kwargs):
    return subprocess.run(command, check=True, **kwargs)


def order(pair):
    return ("tag12", "candidate") if pair % 2 else ("candidate", "tag12")


def assess(path):
    result = json.loads((path/"reader-perf.json").read_text())
    assert result["requested"]==1 and result["usable"]==1
    assert not result["acquisition_failures"] and result["clock_residual_p95_ms"] <= 35
    assert result["settled_glyph"]["usable"]==1
    sample = result["samples"][0]
    assert sample["controlled_network"]["thread_retrieved"]
    return {"t1_ms": sample["glyph_t1_t0_ms"], "t2_ms": sample["t2_t0_ms"],
            "gesture_response_ms": sample["gesture_to_response_ms"],
            "harness_delay_ms": sample["glyph_harness_delay_ms"],
            "clock_residual_p95_ms":result["clock_residual_p95_ms"]}


def main(mode):
    root = Path(os.environ["GITHUB_WORKSPACE"]).resolve()
    output = Path(os.environ["APPLE_VALIDATE_OUTPUT"]).resolve()
    block = int(os.environ["READER_PAIR_BLOCK"])
    assert mode in ("Novel","Forum") and 0<=block<8
    receipts = os.environ["READER_FRAMEWORK_RECEIPT"].split(",")
    assert len(receipts)==2 and all(re.fullmatch(r"\d+:[a-f0-9]{40}:[a-f0-9]{64}",v) for v in receipts)
    assert receipts[0]!=receipts[1]
    receipt_by_arm=dict(zip(("candidate","tag12"),receipts))
    runtime="com.apple.CoreSimulator.SimRuntime.iOS-26-4"
    kind="com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro"
    sim=subprocess.check_output(["xcrun","simctl","create",f"ReaderPairs-{mode}-{block}",kind,runtime],text=True).strip()
    ledger={"run":os.environ["GITHUB_RUN_ID"],"attempt":os.environ["GITHUB_RUN_ATTEMPT"],"block":block,
            "mode":mode,"simulator":sim,"device_type":kind,"runtime":runtime,"receipts":receipt_by_arm,"pairs":[]}
    def save(): (output/"paired-reader.json").write_text(json.dumps(ledger,indent=2))
    bundle="com.yamibo.bbs.readermodesqa"
    try:
        run(["xcrun","simctl","boot",sim]);run(["xcrun","simctl","bootstatus",sim,"-b"],timeout=300)
        for pair in range(block*5+1,block*5+6):
            p={"index":pair,"order":list(order(pair)),"arms":{},"valid":False}
            ledger["pairs"].append(p);save()
            for arm in order(pair):
                raw=output/f"{pair:02d}-{arm}";raw.mkdir()
                row={"relative_evidence":raw.name};p["arms"][arm]=row
                if subprocess.run(["xcrun","simctl","get_app_container",sim,bundle,"data"],capture_output=True).returncode==0:
                    run(["xcrun","simctl","uninstall",sim,bundle])
                with tempfile.TemporaryDirectory(prefix=f"reader-{pair}-{arm}-") as temp:
                    temp=Path(temp);work=temp/"workspace"
                    run(["git","-C",str(root),"worktree","add","--quiet","--detach",str(work),"HEAD"])
                    try:
                        env=dict(os.environ,APPLE_VALIDATE_OUTPUT=str(raw),RUNNER_TEMP=str(temp),
                            READER_PAIRED_SIMULATOR=sim,READER_FRAMEWORK_RECEIPT=receipt_by_arm[arm],
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
            a,b=p["arms"]["tag12"],p["arms"]["candidate"]
            p["valid"]=all("metrics" in arm and "error" not in arm and arm["exit_code"]==0 for arm in [a,b]) and a["corpus"]==b["corpus"]
            if p["valid"]:
                p["candidate_minus_tag12_ms"]={k:b["metrics"][k]-a["metrics"][k] for k in ["t1_ms","t2_ms","gesture_response_ms"]}
            print("READER_PAIR "+json.dumps({k:v for k,v in p.items() if k!="arms"}),flush=True);save()
    finally:
        subprocess.run(["xcrun","simctl","shutdown",sim],timeout=60);subprocess.run(["xcrun","simctl","delete",sim],timeout=60)
    if sum(v["valid"] for v in ledger["pairs"])!=5:raise SystemExit("Incomplete five-pair block")


if __name__=="__main__":
    main(sys.argv[1])
