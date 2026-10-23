import json, re, time
from pathlib import Path
import runtime_checks as q
PKG="me.thenano.yamibo.yamibo_app.downloadqa.debug"
results=[]
for mode in ["novel","thread","manga","tag","rss"]:
    q.adb("shell","am","start","-S","-f","0x10008000","-n",PKG+"/me.thenano.yamibo.yamibo_app.MainActivity","--es","qa-reader",mode)
    for attempt in range(8):
        tree=q.dump("final-"+mode)
        text=" ".join(q.labels(tree))
        if ("QA_NOVEL_START" in text if mode in ["novel","thread"] else "300" in text): break
    else: raise AssertionError((mode,text))
    q.screenshot("final-"+mode)
    root=next(tree.iter("node"))
    x1,y1,x2,y2=map(int,re.findall(r"\d+",root.get("bounds")))
    if mode in ["novel","thread"]:
        q.adb("shell","input","swipe",str((x1+x2)//2),str(y1+(y2-y1)*3//4),str((x1+x2)//2),str(y1+(y2-y1)//3),"400")
    else:
        q.adb("shell","input","swipe",str(x1+(x2-x1)*4//5),str((y1+y2)//2),str(x1+(x2-x1)//5),str((y1+y2)//2),"350")
    after=q.labels(q.dump("final-"+mode+"-navigation"))
    assert ("QA_NOVEL_START" not in " ".join(after)) if mode in ["novel","thread"] else any("2 / 300" in s or "2/300" in s for s in after), (mode,after)
    results.append({"mode":mode,"loaded":True,"navigation":True})
# Exercise an older full-thread download without an author-specific directory.
q.adb("shell","run-as",PKG,"rm","-r","files/qa_downloads/YamiboDownloads/thread_43_page_1_author_7")
q.adb("shell","am","start","-S","-f","0x10008000","-n",PKG+"/me.thenano.yamibo.yamibo_app.MainActivity","--es","qa-reader","manga")
for attempt in range(8):
    text=" ".join(q.labels(q.dump("final-manga-full-thread-fallback")))
    if "300" in text: break
else: raise AssertionError(text)
q.screenshot("final-manga-full-thread-fallback")
results.append({"mode":"manga-full-thread-fallback","loaded":True})
q.adb("shell","run-as",PKG,"tar","-xf","/data/local/tmp/yamibo-download-fixtures.tar","-C","files/qa_downloads")
q.ROOT.joinpath("final-reader-checks.json").write_text(json.dumps(results,indent=2),encoding="utf-8")
print(json.dumps(results))
