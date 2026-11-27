"""Controlled local replay only. Unknown resources return an error; no traffic is forwarded."""
import argparse, hashlib, json, re, subprocess, sys, threading, time
from html import escape
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs,urlsplit
D=Path(__file__).resolve().parent
F=D/"network-fixture"
ORIGIN="http://127.0.0.1:18928"
HOME='<div class="forumlist"><div class="subforumshow" href="#boards"><h2><a>讀者測試</a></h2></div><ul id="boards"><li><a class="murl" href="forum.php?mod=forumdisplay&amp;fid=30"><span class="mtit">QA Forum</span></a></li></ul></div>'
ROW='<li class="list"><div class="threadlist_top"><div class="muser"><h3><a class="mmc" href="home.php?mod=space&amp;uid=7">QA Author</a></h3></div></div><a href="forum.php?mod=viewthread&amp;tid=140"><div class="threadlist_tit"><em>QA Three Readers</em></div></a></li>'
BOARD='<div class="forumdisplay-top"><h2>QA Forum</h2></div><a id="a_newthread" href="forum.php?mod=post&amp;fid=30">New thread</a><ul class="threadlist">'+ROW+'</ul>'
SEARCH='<div class="threadlist_box"><h2><em><span class="emfont">QA Three Readers</span> 找到 1 个相关内容</em></h2></div><a href="search.php?mod=forum&amp;searchid=7001"></a><ul class="threadlist">'+ROW+'</ul>'
PROFILE='<div class="avatar_bg"><span class="name">QA Author</span></div><ul class="myinfo_list"><li>UID<span>7</span></li><li>用户组<span>QA</span></li></ul><input name="formhash" value="00000000">'
def html(x):return ('<!doctype html><html><head><meta charset="utf-8"></head><body>'+x+'</body></html>').encode()
def generate(generator):
    F.mkdir(exist_ok=True)
    ret=subprocess.run([sys.executable,str(generator),str(F),"--bench","--origin",ORIGIN],check=True,capture_output=True)
    (F/"generator.log").write_bytes(ret.stdout)
    thread=F/"YamiboDownloads/thread_140_page_1_author_7"
    sample=json.loads((thread/"thread_page.json").read_text(encoding="utf8"))
    def make(p):
        content=p["contentHtml"].split("<p>Bench reader body. ")[0]
        pid=p["pid"];n=p["floor"]
        return f'<div class="plc" id="pid{pid}"><div class="authi"><span class="mtit"><i class="y">{n}</i></span><span class="z"><a href="home.php?mod=space&amp;uid=7">QA Author</a></span><i class="mtime">2026-10-24</i></div><div class="message"><div id="postmessage_{pid}">{content}FILL{n:02}</div></div></div>'
    base=html('<link rel="canonical" href="forum.php?mod=viewthread&amp;tid=140"><div class="header"><h2><a href="forum.php?mod=forumdisplay&amp;fid=30">QA Forum</a></h2></div><div class="view_tit">QA Three Readers</div>'+''.join(map(make,sample["posts"])))
    remaining=65536-len(re.sub(rb'FILL\d\d',b'',base))
    for i in range(1,51):
        count=(remaining//47+(i-4<remaining%47)) if i>=4 else 0
        value=(b'Bench reader body. '*(count//19+2))[:count]
        base=base.replace(f'FILL{i:02}'.encode(),value)
    assert len(base)==65536 and len(sample["posts"])==50
    (F/"thread.html").write_bytes(base)
    contract={"tid":140,"pids":[p["pid"] for p in sample["posts"]],"body_bytes":len(base),"body_sha256":hashlib.sha256(base).hexdigest(),"jpeg":[[p.name,len(p.read_bytes()),hashlib.sha256(p.read_bytes()).hexdigest()] for p in sorted((thread/"images").glob("*.jpg"))],"aggregate_payload_bits_per_s":10000000,"response_delay_ms":100,"catalog_hold_ms":5000}
    assert len(contract["jpeg"])==20
    (F/"contract.json").write_text(json.dumps(contract,indent=2),encoding="utf8")
class Link:
    def __init__(self):self.mu=threading.Lock();self.ready=0.;self.case="warmup";self.hold=False
    def send(self,out,payload):
        for i in range(0,len(payload),8192):
            block=payload[i:i+8192]
            with self.mu:
                self.ready=max(self.ready,time.perf_counter())+len(block)*8/10000000
                when=self.ready
            wait=when-time.perf_counter()
            if wait>0:time.sleep(wait)
            out.write(block);out.flush()
class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def request_body(self):
        path=urlsplit(self.path);q=parse_qs(path.query);mod=q.get("mod",[""])[0]
        if self.command=="POST":
            length=int(self.headers.get("Content-Length","0"))
            if length>65536:raise ValueError("unexpected request size")
            self.rfile.read(length)
        if path.path.startswith("/__case/"):
            case=path.path.split("/")[-1]
            if not re.fullmatch("[a-z0-9-]{1,80}",case):self.send_error(400);return
            self.server.link.case=case;self.server.link.hold=case.endswith("-slow")
            self.send_response(204);self.end_headers();return
        case=self.server.link.case;hold=self.server.link.hold;start=time.monotonic_ns()
        code=200;headers={};ctype="text/html; charset=utf-8"
        if path.path in ("/","/forum.php") and mod in ("","index"):kind="board-index";data=html(HOME)
        elif path.path=="/forum.php" and mod=="forumdisplay":kind="board";data=html(BOARD)
        elif path.path=="/forum.php" and mod=="viewthread" and q.get("tid")==["140"]:kind="thread";data=(F/"thread.html").read_bytes()
        elif path.path=="/home.php":kind="profile";data=html(PROFILE)
        elif path.path=="/search.php" and self.command=="POST":kind="catalog-post";data=b'';code=302;headers["Location"]="search.php?mod=forum&searchid=7001"
        elif path.path=="/search.php":kind="catalog-results";data=html(SEARCH)
        elif (m:=re.fullmatch(r"/bench-(\d{1,2})\.jpg",path.path)) and 1<=int(m[1])<=20:
            kind="image-"+m[1];ctype="image/jpeg";data=(F/f"YamiboDownloads/thread_140_page_1_author_7/images/{int(m[1]):04}.jpg").read_bytes()
        else:kind="unknown";data=b"unmapped fixture resource";code=404
        held=hold and kind=="catalog-post";time.sleep(.1+(5 if held else 0))
        self.send_response(code);self.send_header("Content-Type",ctype);self.send_header("Content-Length",str(len(data)))
        self.send_header("Cache-Control","no-store");[self.send_header(k,v) for k,v in headers.items()];self.end_headers()
        complete=True
        try:self.server.link.send(self.wfile,data)
        except (BrokenPipeError,ConnectionResetError,ConnectionAbortedError):complete=False
        record={"run":case,"resource":kind,"method":self.command,"status":code,"bytes_planned":len(data),"complete":complete,"begin_host_ns":start,"end_host_ns":time.monotonic_ns(),"catalog_held":held}
        with self.server.link.mu:
            with (F/"requests.ndjson").open("a",encoding="utf8") as f:f.write(json.dumps(record)+"\n")
    do_GET=request_body;do_POST=request_body
if __name__=="__main__":
    ap=argparse.ArgumentParser();ap.add_argument("--generate",type=Path);args=ap.parse_args()
    if args.generate:generate(args.generate);print(F/"contract.json")
    else:
        s=ThreadingHTTPServer(("127.0.0.1",18928),Handler);s.link=Link();s.serve_forever()
