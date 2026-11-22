import json
import struct
import sys
import zlib
from pathlib import Path

root = Path(sys.argv[1]) / 'YamiboDownloads'
root.mkdir(parents=True, exist_ok=True)

def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False), encoding='utf-8')

def chunk(kind, data):
    return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))

pixels = b''.join(b'\0' + (bytes([20, 180, 220]) if y % 200 > 15 else bytes([20, 20, 20])) * 800 for y in range(1200))
png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 800, 1200, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(pixels)) + chunk(b'IEND', b'')
images = [{"sourceUrl": f"https://reader.invalid/marker-{i}.png", "fileName": f"{i:04d}.png", "bytes": len(png)} for i in range(1, 4)]
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
    path.write_bytes(png)
write(directory / "thread_page.json", {"thread": {"tid": 140, "title": title, "forum": {"fid": 30, "name": "QA Forum", "url": "forum.php?fid=30"}}, "posts": posts, "pageNav": {"currentPage": 1, "totalPages": 1}})
write(root / "queue.json", [])
