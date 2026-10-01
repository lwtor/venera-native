"""Loopback-only image server for D02 download state checks.

Generate the repository test images first. /slow/ waits 20 seconds before returning an
image, leaving time to pause queued pages while in-flight requests finish; /retry/ returns
503 for its first three GETs, then serves the image on the next GET.
The failure counter resets when this process restarts.
"""

from collections import defaultdict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from mimetypes import guess_type
from pathlib import Path
from threading import Lock
from time import sleep
from urllib.parse import parse_qs, urlsplit


IMAGE_ROOT = Path(__file__).resolve().parents[1] / "test-images" / "out"
ALLOWED_IMAGES = {
    "page_normal_1080x1440.jpg",
    "page_long_1080x6000.png",
    "page_wide_1920x1080.png",
}
retry_counts = defaultdict(int)
retry_lock = Lock()


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        parts = urlsplit(self.path).path.strip("/").split("/")
        mode, name = ("plain", parts[0]) if len(parts) == 1 else parts if len(parts) == 2 else (None, None)
        if mode not in {"plain", "slow", "retry"} or name not in ALLOWED_IMAGES:
            self.send_error(404)
            return

        if mode == "slow":
            sleep(20)
        elif mode == "retry":
            run_id = parse_qs(urlsplit(self.path).query).get("run", ["default"])[0]
            with retry_lock:
                retry_key = (run_id, name)
                retry_counts[retry_key] += 1
                attempt = retry_counts[retry_key]
            if attempt <= 3:
                self.send_error(503, "Fixture-controlled failure")
                return

        image = IMAGE_ROOT / name
        if not image.is_file():
            self.send_error(404, "Generate test images first")
            return
        body = image.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", guess_type(name)[0] or "application/octet-stream")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", 8765), Handler).serve_forever()
