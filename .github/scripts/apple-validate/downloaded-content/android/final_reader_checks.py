import json, os, re, time
from pathlib import Path
import runtime_checks as q
PKG="me.thenano.yamibo.yamibo_app.downloadqa.debug"
results=[]
negative = os.environ.get("SIDE_SEEK_EXPECTED") == "present"
def page_number(tree):
    numbers = {int(m.group(1)) for label in q.labels(tree) for m in [re.search(r"(\d+)\s*/\s*300", label)] if m}
    assert len(numbers) == 1, q.labels(tree)
    return numbers.pop()

def dismiss_image_menu(tree):
    if any(label in ["複製", "复制", "Copy"] for label in q.labels(tree)):
        root = next(tree.iter("node"))
        x1,y1,x2,y2=map(int,re.findall(r"\d+",root.get("bounds")))
        q.adb("shell","input","tap",str((x1+x2)//2),str(y1+(y2-y1)*15//100))

for mode in (["manga"] if negative else os.environ.get("SIDE_SEEK_MODES", "novel,thread,manga,tag,rss").split(",")):
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
        assert page_number(tree) == 1, q.labels(tree)
        edge = x2 - max(4, (x2-x1)*15//1000)
        for hold in ([True] if negative else [False, True]):
            for down in ([True] if negative else [True, False]):
                start=y1+(y2-y1)*(30 if down else 70)//100
                end=y1+(y2-y1)*(70 if down else 30)//100
                name=f"final-{mode}-edge-{'hold' if hold else 'short'}-{'down' if down else 'up'}"
                q.adb("shell","input","touchscreen","draganddrop" if hold else "swipe",str(edge),str(start),str(edge),str(end),"700" if hold else "250")
                after_edge=q.dump(name)
                dismiss_image_menu(after_edge)
                after_edge=q.dump(name+"-dismissed")
                page=page_number(after_edge)
                q.screenshot(name)
                results.append({"mode":mode,"gesture":name,"page":page})
                if negative:
                    assert page > 10, ("Original side-seek did not reproduce",page)
                    q.ROOT.joinpath("negative-control.json").write_text(json.dumps(results,indent=2),encoding="utf-8")
                    print(json.dumps(results));raise SystemExit(0)
                assert page == 1, (mode,name,page)
        q.adb("shell","input","swipe",str(x1+(x2-x1)*4//5),str((y1+y2)//2),str(x1+(x2-x1)//5),str((y1+y2)//2),"350")
    after=q.labels(q.dump("final-"+mode+"-navigation"))
    assert ("QA_NOVEL_START" not in " ".join(after)) if mode in ["novel","thread"] else any("2 / 300" in s or "2/300" in s for s in after), (mode,after)
    if mode not in ["novel", "thread"]:
        q.adb("shell","input","swipe",str(x1+(x2-x1)//5),str((y1+y2)//2),str(x1+(x2-x1)*4//5),str((y1+y2)//2),"350")
        assert page_number(q.dump("final-"+mode+"-reverse-navigation")) == 1
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
