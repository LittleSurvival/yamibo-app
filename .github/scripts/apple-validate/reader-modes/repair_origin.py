"""Serve fixture images over a trusted loopback HTTPS origin on the owned simulator."""

from functools import partial
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import ssl
import subprocess
import threading


class ImageHandler(BaseHTTPRequestHandler):
    def __init__(self, *args, origin, **kwargs):
        self.origin = origin
        super().__init__(*args, **kwargs)

    def do_GET(self):
        self.origin.requests.append({"path": self.path, "armed": self.origin.armed})
        if not self.origin.armed or self.path != f"/{self.origin.case}/marker-3.png":
            self.send_error(404)
            return
        data = self.origin.image.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", "image/png")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        if self.path != "/arm" or self.origin.armed:
            self.send_error(400)
            return
        self.origin.armed = True
        self.send_response(204)
        self.end_headers()


class RepairOrigin:
    def __init__(self, simulator, directory):
        root = Path(directory)
        self.image = root / "third.png"
        self.requests = []
        self.case = ""
        openssl = ["openssl"]
        def issue(args):
            subprocess.run(openssl + args, cwd=root, check=True, timeout=45)
        (root / "ca.cnf").write_text(
            "[req]\ndistinguished_name=dn\nx509_extensions=ca\n[dn]\n[ca]\n"
            "basicConstraints=critical,CA:TRUE\nkeyUsage=critical,keyCertSign,cRLSign\n")
        issue(["req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2", "-sha256", "-config", "ca.cnf",
               "-subj", "/CN=Reader fixture CA", "-keyout", "ca.key", "-out", "ca.pem"])
        issue(["req", "-newkey", "rsa:2048", "-nodes", "-config", "ca.cnf", "-subj", "/CN=localhost",
               "-keyout", "server.key", "-out", "server.csr"])
        (root / "server.ext").write_text(
            "basicConstraints=critical,CA:FALSE\nkeyUsage=digitalSignature,keyEncipherment\n"
            "extendedKeyUsage=serverAuth\nsubjectAltName=IP:127.0.0.1,DNS:localhost\n")
        issue(["x509", "-req", "-in", "server.csr", "-CA", "ca.pem", "-CAkey", "ca.key", "-CAcreateserial",
               "-days", "2", "-sha256", "-extfile", "server.ext", "-out", "server.pem"])
        subprocess.run(["xcrun", "simctl", "keychain", simulator, "add-root-cert", str(root / "ca.pem")],
                       check=True, timeout=90)
        self.armed = False
        self.server = ThreadingHTTPServer(("127.0.0.1", 18083), partial(ImageHandler, origin=self))
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(root / "server.pem", root / "server.key")
        self.server.socket = context.wrap_socket(self.server.socket, server_side=True)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def begin(self, case):
        self.case = case
        self.armed = False
        self.requests.clear()
        return f"https://127.0.0.1:{self.server.server_port}/{case}"

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
