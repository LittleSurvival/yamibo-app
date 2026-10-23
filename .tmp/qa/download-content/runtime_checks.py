import json, re, subprocess, time, xml.etree.ElementTree as ET
from pathlib import Path
ROOT=Path(__file__).parent
SERIAL="emulator-5554"
def adb(*args):
    return subprocess.check_output(["adb", "-s", SERIAL, *args], timeout=30)
def dump(name):
    for attempt in range(6):
        text=adb("exec-out", "uiautomator", "dump", "/dev/tty").decode("utf-8",errors="replace")
        if "<?xml" in text and "</hierarchy>" in text: break
        time.sleep(.5)
    else: raise RuntimeError(text)
    xml=text[text.index("<?xml"):text.rindex("</hierarchy>")+12]
    (ROOT/(name+".xml")).write_text(xml,encoding="utf-8")
    return ET.fromstring(xml)
def labels(tree):
    return [node.get("text") for node in tree.iter("node") if node.get("text")]
def tap(tree, text):
    candidates=[node for node in tree.iter("node") if node.get("text")==text]
    assert len(candidates)==1,(text,labels(tree))
    coords=[int(n) for n in re.findall(r"\d+",candidates[0].get("bounds"))]
    adb("shell","input","tap",str((coords[0]+coords[2])//2),str((coords[1]+coords[3])//2))
def screenshot(name):
    (ROOT/(name+".png")).write_bytes(adb("exec-out","screencap","-p"))
if __name__=="__main__":
    print(json.dumps(labels(dump("current")),ensure_ascii=False))
