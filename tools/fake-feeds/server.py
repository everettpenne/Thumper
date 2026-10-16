#!/usr/bin/env python3
"""A stand-in for the outside world, so Thumper can be tried and tested without the real internet: Apple-shaped search and charts, a podcast feed with a real
(generated) MP3 with range support, an Atom blog, and a web page that advertises its feed. Every address it hands out uses the Host the request came in on,
so it works from the emulator (10.0.2.2) and from the Mac (localhost) alike.
    python3 tools/fake-feeds/server.py [port]        # default 8097; needs ffmpeg and Pillow once, to make the audio and the pictures"""
import colorsys, io, json, os, re, subprocess, sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
AUDIO = os.path.join(HERE, "episode.mp3")          # 5 minutes: episodes 3 and 4
SHORT = os.path.join(HERE, "short.mp3")            # 6 seconds: episodes 1 and 2, so a test can watch one end and the next begin
AUDIO_REQUESTS = []
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8097

def make_audio():
    if not os.path.exists(SHORT):
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=520:duration=6", "-c:a", "libmp3lame", "-b:a", "48k", SHORT], check=True)
    if not os.path.exists(AUDIO):
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=330:duration=300", "-c:a", "libmp3lame", "-b:a", "48k", AUDIO], check=True)

def art(seed, size=600):
    hue = sum(map(ord, seed)) % 360
    img = Image.new("RGB", (size, size)); d = ImageDraw.Draw(img)
    for y in range(size):
        r, g, b = colorsys.hsv_to_rgb(hue / 360, .55, .35 + .5 * y / size); d.line([(0, y), (size, y)], fill=(int(r * 255), int(g * 255), int(b * 255)))
    d.text((24, 24), seed, fill="white")
    buf = io.BytesIO(); img.save(buf, "JPEG"); return buf.getvalue()

NOTES = '<p>In this episode we talk about <b>desert</b> logistics.</p><p>Links: <a href="https://example.com/notes">the notes</a></p><ul><li>Water</li><li>Sand</li></ul>'

def show_feed(host):
    items = "".join(f"""<item><title>Episode {n}: {t}</title><guid isPermaLink="false">fake-ep-{n}</guid><pubDate>{d}</pubDate>
      <enclosure url="http://{host}/audio/ep{n}.mp3" type="audio/mpeg" length="1000"/><itunes:duration>{dur}</itunes:duration><itunes:episode>{n}</itunes:episode>
      <content:encoded><![CDATA[{NOTES}]]></content:encoded></item>""" for n, t, d, dur in
      ((4, "The Thumper", "Wed, 14 Oct 2026 08:00:00 +0000", "05:00"), (3, "Spice Routes", "Wed, 07 Oct 2026 08:00:00 +0000", "05:00"),
       (2, "Worm Sign", "Wed, 30 Sep 2026 08:00:00 +0000", "05:00"), (1, "Pilot", "Wed, 23 Sep 2026 08:00:00 +0000", "05:00")))
    return f"""<?xml version="1.0" encoding="UTF-8"?><rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" xmlns:content="http://purl.org/rss/1.0/modules/content/">
<channel><title>Fake Show</title><link>http://{host}/</link><description>&lt;p&gt;A show about the desert.&lt;/p&gt;</description><itunes:author>Fake Host</itunes:author>
<itunes:image href="http://{host}/art/show.jpg"/>{items}</channel></rss>"""

def blog_feed(host):
    entries = "".join(f"""<entry><title>{t}</title><link rel="alternate" href="http://{host}/blog/{n}"/><id>blog-{n}</id><updated>{d}</updated><author><name>Liet</name></author>
      <content type="html">&lt;p&gt;{t}: a long enough paragraph of text to read, about the &lt;b&gt;water&lt;/b&gt; and the sand.&lt;/p&gt;&lt;img src="http://{host}/art/blog{n}.jpg" alt="pic"/&gt;&lt;p&gt;Second paragraph with a &lt;a href="https://example.com"&gt;link&lt;/a&gt;.&lt;/p&gt;</content></entry>"""
        for n, t, d in ((3, "Third post", "2026-10-14T10:00:00Z"), (2, "Second post", "2026-10-10T10:00:00Z"), (1, "First post", "2026-10-01T10:00:00Z")))
    return f"""<?xml version="1.0"?><feed xmlns="http://www.w3.org/2005/Atom"><title>The Sand Report</title><subtitle>Notes from the desert</subtitle><link rel="alternate" href="http://{host}/blog"/>{entries}</feed>"""

class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *a): pass
    def send(self, code, body=b"", ctype="application/json", extra=None):
        if not isinstance(body, bytes): body = body.encode() if isinstance(body, str) else json.dumps(body).encode()
        self.send_response(code); self.send_header("Content-Type", ctype); self.send_header("Content-Length", str(len(body)))
        for k, v in (extra or {}).items(): self.send_header(k, v)
        self.end_headers(); self.wfile.write(body)
    def do_GET(self):
        u = urlparse(self.path); q = parse_qs(u.query); p = u.path; host = self.headers.get("Host", f"localhost:{PORT}")
        print("REQ", self.path[:120], flush=True)
        shows = [{"collectionId": 1, "collectionName": "Fake Show", "artistName": "Fake Host", "feedUrl": f"http://{host}/feeds/show.xml", "artworkUrl600": f"http://{host}/art/show.jpg", "primaryGenreName": "Technology"},
                 {"collectionId": 2, "collectionName": "Sand Stories", "artistName": "Another Host", "feedUrl": f"http://{host}/feeds/show.xml?second=1", "artworkUrl600": f"http://{host}/art/second.jpg", "primaryGenreName": "Education"}]
        if p == "/search":
            term = (q.get("term") or [""])[0].lower(); return self.send(200, {"resultCount": 2, "results": [s for s in shows if term in s["collectionName"].lower() or term in "fake sand"]})
        if p.startswith("/api/v2/") and p.endswith("/podcasts.json"): return self.send(200, {"feed": {"results": [{"id": "2"}, {"id": "1"}]}})
        if p == "/lookup": return self.send(200, {"results": shows})
        if p == "/feeds/show.xml": return self.send(200, show_feed(host), "application/rss+xml", {"ETag": '"show-1"'})
        if p == "/feeds/blog.xml": return self.send(200, blog_feed(host), "application/atom+xml")
        if p == "/blog": return self.send(200, f'<html><head><title>Blog</title><link rel="alternate" type="application/atom+xml" href="/feeds/blog.xml"></head><body>hi</body></html>', "text/html")
        if p.startswith("/blog/"): return self.send(200, "<html><body>post</body></html>", "text/html")
        m = re.match(r"/art/(.+)\.jpg", p)
        if m: return self.send(200, art(m[1]), "image/jpeg")
        if p == "/admin/audio-requests": return self.send(200, {"count": len(AUDIO_REQUESTS), "paths": AUDIO_REQUESTS})
        if p.startswith("/audio/"): AUDIO_REQUESTS.append(p); return self.audio(SHORT if re.match(r"/audio/ep[12]\.mp3", p) else AUDIO)
        return self.send(404)
    def audio(self, path):
        size = os.path.getsize(path); rng = self.headers.get("Range"); start, end = 0, size - 1
        if rng and (m := re.match(r"bytes=(\d*)-(\d*)", rng)):
            if m[1]: start = int(m[1])
            if m[2]: end = int(m[2])
        with open(path, "rb") as f: f.seek(start); data = f.read(end - start + 1)
        extra = {"Accept-Ranges": "bytes"}
        if rng: extra["Content-Range"] = f"bytes {start}-{end}/{size}"
        self.send(206 if rng else 200, data, "audio/mpeg", extra)

if __name__ == "__main__":
    make_audio(); print(f"Fake feeds on :{PORT}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), H).serve_forever()
