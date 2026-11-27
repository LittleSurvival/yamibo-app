"""Populate the same SQLite catalog that the iOS reader queries, inside its owned simulator container."""
import hashlib
import json
from pathlib import Path
import sqlite3
import shutil
import zipfile

TYPE = "me.thenano.yamibo.yamibo_app.repository.download."
OFFLINE_TID = 780001
GEN = "a50ad814-82f1-4cc3-a259-1668dbfb0c09"
STORAGE = "ios:Documents/YamiboApp"

def encoded(value):
    return json.dumps(value, separators=(",",":"), ensure_ascii=False).encode()

def seed_next_chapter(fixture):
    # Preserve a distinct visible page and a real stored image for the normal chapter selector.
    source=fixture/"YamiboDownloads/thread_140_page_1_author_7"
    target=source.with_name("thread_300004_page_1_author_7")
    primary_manifest=source/"manifest.json"
    old=json.loads(primary_manifest.read_bytes())
    old["downloadedAt"]=2000000
    primary_manifest.write_bytes(encoded(old))
    page=json.loads((source/"thread_page.json").read_bytes())
    title="QA CHAPTER 0005"
    chosen=dict(old["images"][1])
    original=source/"images"/chosen["fileName"]
    (target/"images").mkdir(parents=True)
    shutil.copy2(original,target/"images"/chosen["fileName"])
    chosen.update(sourceUrl=chosen["sourceUrl"].rsplit("/",1)[0]+"/other-chapter-"+chosen["fileName"])
    item=dict(old,key=dict(old["key"],tid=300004),title=title,downloadedAt=1999999,images=[chosen])
    post=dict(page["posts"][1],pid=3000041,floor=1,title=title,images=[{"url":chosen["sourceUrl"]}],
              contentHtml=f'<p>CHAPTER_0005_FIRST</p><img src="{chosen["sourceUrl"]}">')
    page=dict(page,thread=dict(page["thread"],tid=300004,title=title),posts=[post])
    (target/"manifest.json").write_bytes(encoded(item))
    (target/"thread_page.json").write_bytes(encoded(page))
    return {"tid":300004,"pid":3000041,"title":title,"image_sha256":hashlib.sha256(original.read_bytes()).hexdigest()}

def seed(fixture, database, chapters, copies, output):
    assert chapters in (58,1000) and copies in (0,10000)
    assert fixture.name=="YamiboApp" and database.name=="yamibo.db"
    neighbor=seed_next_chapter(fixture)
    db=sqlite3.connect(database)
    chapters_rows=[]
    # Both scales have the same selected chapter and immediate neighbours.
    for i in range(chapters):
        tid=140 if i==3 else 300000+i
        title="QA Three Readers" if tid==140 else f"QA CHAPTER {i+1:04d}"
        summary={"tid":tid,"fid":30,"title":title,"hasPoll":False,"url":"","author":{"uid":7,"name":"QA Author"}}
        chapters_rows.append((7,30,tid,776,encoded(summary).decode(),i))
    with db:
        db.execute("INSERT INTO SmartComicWork(id,owner,fid,title,completeAt,lastTid) VALUES(776,7,30,'QA Fixture Work',10,300004)")
        db.executemany("INSERT INTO SmartComicMember(owner,fid,tid,workId,summary,ordinal) VALUES(?,?,?,?,?,?)",chapters_rows)
        if copies:
            rows=[]; floors=[]
            for group in range(copies//100):
                tid=OFFLINE_TID+group
                directory=fixture/f"YamiboDownloads/library/7/threads/{tid}"
                directory.mkdir(parents=True,exist_ok=False)
                header={"thread":{"tid":tid,"title":"Unrelated benchmark archive","forum":{"fid":31,"name":"Other","url":""}},"posts":[]}
                state={"generation":GEN,"claims":[],"hashes":{},"batches":{},"observations":{}}
                with zipfile.ZipFile(directory/f"posts-{GEN}.zip","w",compression=zipfile.ZIP_STORED) as archive:
                    for page in range(1,101):
                        pid=2000000+group*100+page
                        key={"tid":tid,"page":page}
                        item={"type":TYPE+"ThreadPageDownloadManifest","key":key,"title":header["thread"]["title"],
                              "downloadedAt":1000,"sourceTotalPages":100,"pageKind":"Normal","archiveId":GEN,"shared":True,"owner":7}
                        state["claims"].append({"item":item,"header":header,"floors":[{"pid":pid,"floor":page,"pinned":False}]})
                        blob=encoded({"pid":pid,"floor":0,"title":"","author":{"uid":7,"name":"QA Author"},
                                      "timeCreate":{"text":"","epoch":0},"contentHtml":f"<p>UNRELATED {page}</p>","images":[],"tags":{}})
                        archive.writestr(f"{pid}.json",blob); state["hashes"][pid]=hashlib.sha256(blob).hexdigest()
                        state["batches"][pid]=GEN; state["observations"][pid]=1000
                        sid=f"7/thread_{tid}_page_{page}_author_all"
                        rows.append((STORAGE,sid,7,tid,0,page,encoded(dict(type=TYPE+"ThreadPageDownloadKey",**key)).decode(),GEN))
                        floors.append((STORAGE,sid,pid))
                (directory/"manifest.json").write_bytes(encoded(state))
            db.executemany("INSERT INTO ReaderDownloadPage(storage,item,owner,tid,author,page,taskKey,generation) VALUES(?,?,?,?,?,?,?,?)",rows)
            db.executemany("INSERT INTO ReaderDownloadFloor(storage,item,pid) VALUES(?,?,?)",floors)
    recorded_chapters=db.execute("SELECT count(*) FROM SmartComicMember WHERE owner=7 AND fid=30 AND workId=776").fetchone()[0]
    recorded_copies=db.execute("SELECT count(*) FROM ReaderDownloadPage WHERE storage=? AND owner=7 AND tid BETWEEN ? AND ?",(STORAGE,OFFLINE_TID,OFFLINE_TID+99)).fetchone()[0]
    assert (chapters,copies)==(recorded_chapters,recorded_copies)
    db.close()
    (output/"catalog-scale.json").write_bytes(encoded({"chapters":recorded_chapters,"indexed_unrelated_download_pages":recorded_copies,"selectable_next_chapter":neighbor,
        "unrelated_pack_count":copies//100,"unrelated_physical_post_members":copies,"uid":7,"fid":30,"selected_tid":140,"storage":STORAGE,
        "unrelated_tid_min":OFFLINE_TID,"unrelated_tid_max":OFFLINE_TID+99,"scope":"100 distinct unrelated TID directories, each with a 100-page ZIP, manifest and 100 indexed PID rows when measuring 10,000 downloads."}))
