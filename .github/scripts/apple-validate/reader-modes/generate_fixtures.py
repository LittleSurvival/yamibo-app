import json
import argparse
import struct
import zlib
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("directory", type=Path)
parser.add_argument("--cbz", action="store_true")
parser.add_argument("--damaged", action="store_true")
parser.add_argument("--origin", default="https://reader.invalid")
parser.add_argument("--repair-image", type=Path)
parser.add_argument("--bench", action="store_true", help="50 floors, 20 deterministic 1080x1600 JPEGs and about 64 KiB of encoded forum content")
parser.add_argument("--pure-text",action="store_true")
args = parser.parse_args()
root = args.directory / 'YamiboDownloads'
root.mkdir(parents=True, exist_ok=True)

def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False), encoding='utf-8')

def chunk(kind, data):
    return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))

def marker_png(rgb):
    pixels = b''.join(b'\0' + (bytes(rgb) if y % 200 > 15 else bytes([20, 20, 20])) * 800 for y in range(1200))
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 800, 1200, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(pixels)) + chunk(b'IEND', b'')

markers = [marker_png(rgb) for rgb in [(20, 180, 220), (220, 40, 180), (50, 170, 40)]]
if args.bench:
    import io
    import hashlib
    import numpy as np
    from PIL import Image
    colors = [(20, 180, 220), (220, 40, 180)] + [(110+i*3, 100, 72+i*3) for i in range(18)]
    markers = []
    target = 256*1024
    # One deterministic luminance field per image preserves the encoded floor witness.
    for i, color in enumerate(colors):
        rng = np.random.default_rng(49129+i)
        noise = rng.integers(-36,37, size=(1600,1080,1), dtype=np.int16)
        pixels = np.clip(noise + np.array(color,dtype=np.int16), 0, 255).astype(np.uint8)
        pixels[np.arange(1600)%240 < 12] = 26
        img = Image.fromarray(pixels)
        low, high = 2, 97
        best = b''
        while low <= high:
            quality = (low+high)//2
            file = io.BytesIO()
            img.save(file,format="JPEG",quality=quality,subsampling=2)
            data = file.getvalue()
            if not best or abs(len(data)-target) < abs(len(best)-target): best = data
            if len(data)<target: low = quality+1
            else: high = quality-1
        markers.append(best)
if args.repair_image:
    args.repair_image.write_bytes(markers[2])
images = [{"sourceUrl": f"{args.origin}/marker-{i}.png", "fileName": f"{i:04d}.png", "bytes": len(png)} for i, png in enumerate(markers, 1)]
if args.bench:
    for i, row in enumerate(images, 1):
        row.update(sourceUrl=f"{args.origin}/bench-{i}.jpg",fileName=f"{i:04d}.jpg")
directory = root / "thread_140_page_1_author_7"
title = "QA Three Readers"
key = {"tid": 140, "page": 1, "authorId": 7}
write(directory / "manifest.json", {"key": key, "title": title, "downloadedAt": 1, "sourceTotalPages": 1, "pageKind": "LastAtDownloadTime", "images": images})
posts = []
for offset, image in enumerate(images, 1):
    html = f'<p>READER_FLOOR_{offset}</p><img src="{image["sourceUrl"]}">'
    posts.append({"pid": 1400 + offset, "floor": offset, "title": title, "author": {"uid": 7, "name": "QA Author"}, "timeCreate": {"text": "2026-10-24", "epoch": 0}, "contentHtml": html, "images": [{"url": image["sourceUrl"]}], "tags": {}, "poll": None})
    path = directory / "images" / image["fileName"]
    path.parent.mkdir(exist_ok=True)
    path.write_bytes(markers[offset - 1])
if args.bench:
    for floor in range(len(posts)+1,51):
        posts.append(dict(posts[0],pid=1400+floor,floor=floor,images=[],contentHtml=f"<p>READER_FLOOR_{floor}</p>"))
    # Put padding on later floors so the visible first page stays identical at both scales.
    for post in posts[3:]:
        post["contentHtml"] += "<p>" + ("Bench reader body. " * 70) + "</p>"
    record = {"floors":len(posts), "content_bytes":sum(len(post["contentHtml"].encode()) for post in posts),
              "size":[1080,1600], "images":[{"bytes":len(b),"sha256":hashlib.sha256(b).hexdigest()} for b in markers]}
    print("PERF_FIXTURE " + json.dumps(record),flush=True)
write(directory / "thread_page.json", {"thread": {"tid": 140, "title": title, "forum": {"fid": 30, "name": "QA Forum", "url": "forum.php?fid=30"}}, "posts": posts, "pageNav": {"currentPage": 1, "totalPages": 1}})
if args.pure_text:
    assert args.bench
    textdir=root/"thread_141_page_1_author_7"
    texttitle="QA Text Only"
    write(textdir/"manifest.json",{"key":{"tid":141,"page":1,"authorId":7},"title":texttitle,"downloadedAt":1,
              "sourceTotalPages":1,"pageKind":"LastAtDownloadTime","images":[]})
    body=[dict(post,pid=2000+i,title=texttitle,images=[],contentHtml=f"<p>READER_TEXT_{i}</p><p>Pure text reader paragraph number {i}.</p>") for i,post in enumerate(posts,1)]
    write(textdir/"thread_page.json",{"thread":{"tid":141,"title":texttitle,"forum":{"fid":30,"name":"QA Forum","url":""}},
           "posts":body,"pageNav":{"currentPage":1,"totalPages":1}})
write(root / "queue.json", [])


if args.cbz:
    import zipfile
    import shutil
    with zipfile.ZipFile(directory / "images.cbz", "w", compression=zipfile.ZIP_STORED) as archive:
        for image, data in zip(images, markers):
            archive.writestr(image["fileName"], data[:10] if args.damaged and image is images[-1] else data)
            image["packed"] = True
    shutil.rmtree(directory / "images")
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    manifest.update(archiveId="fixture-cbz", images=images)
    write(directory / "manifest.json", manifest)
