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
images = [{'sourceUrl': f'https://bbs.yamibo.com/data/attachment/forum/qa-{i}.png', 'fileName': f'{i:04d}.png', 'bytes': len(png)} for i in range(1, 301)]

def page(tid, title, image_list):
    html = '<p>QA_NOVEL_START</p>' + '<p>Downloaded reading paragraph for offline verification.</p>' * 2500 + '<p>QA_NOVEL_END</p>' if tid == 42 else ''.join('<img src="' + i['sourceUrl'] + '">' for i in image_list)
    return {'thread': {'tid': tid, 'title': title, 'forum': {'fid': 1, 'name': 'QA Forum', 'url': 'forum.php?fid=1'}},
            'posts': [{'pid': tid, 'floor': 1, 'title': title, 'author': {'uid': 7, 'name': 'QA Author'}, 'timeCreate': {'text': '2026-10-24', 'epoch': 0},
                       'contentHtml': html, 'images': [{'url': i['sourceUrl']} for i in image_list], 'tags': {}, 'poll': None}],
            'pageNav': {'currentPage': 1, 'totalPages': 1}}

for tid, title, image_list in [(42, 'QA NOVEL', []), (43, 'QA MANGA', images)]:
    for author in [None, 7]:
        key = {'tid': tid, 'page': 1, 'authorId': author}
        directory = root / f'thread_{tid}_page_1_author_{author if author else "all"}'
        write(directory / 'manifest.json', {'key': key, 'title': title, 'downloadedAt': 1, 'sourceTotalPages': 1, 'pageKind': 'Normal', 'images': image_list})
        write(directory / 'thread_page.json', page(tid, title, image_list))
        for image in image_list:
            path = directory / 'images' / image['fileName']
            path.parent.mkdir(exist_ok=True)
            path.write_bytes(png)

for kind in ['tag', 'rss']:
    key = {'tagId': 8, 'tid': 43, 'authorId': 7} if kind == 'tag' else {'subscriptionId': 1, 'tid': 43, 'authorId': 7}
    prefix = 'tag_manga_8' if kind == 'tag' else 'rss_1'
    directory = root / prefix / 'chapter_43_author_7'
    manifest = {'key': key, 'title': 'QA MANGA', 'imageCount': len(images), 'downloadedAt': 1, 'images': images}
    manifest.update({'tagName': 'QA TAG', 'tagPage': 1} if kind == 'tag' else {'subscriptionTitle': 'QA RSS', 'subscriptionQuery': 'qa', 'subscriptionPage': 1})
    write(directory / 'manifest.json', manifest)
    for image in images:
        path = directory / 'images' / image['fileName']
        path.parent.mkdir(exist_ok=True)
        path.write_bytes(png)
write(root / 'queue.json', [])
print(str(root))
