#!/usr/bin/env python3
"""Tiny mock of Home Assistant's WebSocket + /media HTTP endpoints (stdlib only).
Exercises: auth, browse_media, resolve_media, server pings between messages,
fragmented frames, 64-bit-length frames, unrelated events before the result."""
import asyncio, base64, hashlib, json, os, ssl, struct, sys

TOKEN = "test-token"
GUID = b"258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
HERE = os.path.dirname(os.path.abspath(__file__))
IMG = open(os.path.join(HERE, "sample.jpg"), "rb").read()
# Fake HA frontend page: a <home-assistant> element with hass.callWS, like the real one,
# but logged in already. Used to test the app's WebView-session (JS bridge) path.
PAGE = b"""<!doctype html><html><body style="background:#123;color:#fff;font:30px sans-serif">
<h1>Mock HA</h1><div id=log></div><script>
var el=document.createElement('home-assistant');document.body.appendChild(el);
var F='media-source://media_source/local/slideshow';
el.hass={connection:{},callWS:function(m){document.getElementById('log').innerHTML+=m.type+'<br>';
 return new Promise(function(ok,bad){setTimeout(function(){
  if(m.type=='media_source/browse_media'){ if(m.media_content_id!=F) return bad({code:'browse_media_failed',message:'no'});
   ok({title:'slideshow',children:[{media_class:'image',media_content_type:'image/jpeg',media_content_id:F+'/a.jpg',can_expand:false},
    {media_class:'image',media_content_type:'image/png',media_content_id:F+'/b.png',can_expand:false},
    {media_class:'image',media_content_type:'image/jpeg',media_content_id:F+'/c.jpg',can_expand:false},
    {media_class:'video',media_content_type:'video/mp4',media_content_id:F+'/v.mp4',can_expand:false}]});}
  else if(m.type=='media_source/resolve_media'){var n=m.media_content_id.split('/').pop();
   ok({url:'/media/local/slideshow/'+n+'?authSig=SIG',mime_type:'image/jpeg'});}
  else bad({code:'unknown_command'});},50);});}};
</script></body></html>"""

async def read_frame(r):
    b0, b1 = await r.readexactly(2)
    op = b0 & 0x0F; ln = b1 & 0x7F
    assert b1 & 0x80, "client frames must be masked"
    if ln == 126: ln = struct.unpack(">H", await r.readexactly(2))[0]
    elif ln == 127: ln = struct.unpack(">Q", await r.readexactly(8))[0]
    mask = await r.readexactly(4); data = bytearray(await r.readexactly(ln))
    for i in range(ln): data[i] ^= mask[i & 3]
    return op, bytes(data)

def frame(op, data, fin=True):
    h = bytes([(0x80 if fin else 0) | op]); n = len(data)
    if n < 126: h += bytes([n])
    elif n < 65536: h += bytes([126]) + struct.pack(">H", n)
    else: h += bytes([127]) + struct.pack(">Q", n)
    return h + data

async def recv_text(r, w):
    while True:
        op, d = await read_frame(r)
        if op == 1: return d.decode()
        if op == 10: continue  # pong
        if op == 8: raise EOFError

async def send_text(w, obj, fragmented=False, ping_first=False):
    data = json.dumps(obj).encode()
    if ping_first:
        w.write(frame(9, b"hb"))
    if fragmented and len(data) > 10:
        w.write(frame(1, data[:7], fin=False)); w.write(frame(0, data[7:20], fin=False)); w.write(frame(0, data[20:]))
    else:
        w.write(frame(1, data))
    await w.drain()

pongs = 0
async def handle_ws(r, w):
    global pongs
    await send_text(w, {"type": "auth_required", "ha_version": "2026.11.0"})
    m = json.loads(await recv_text(r, w))
    if m.get("access_token") != TOKEN:
        await send_text(w, {"type": "auth_invalid", "message": "Invalid access token or password"}); w.close(); return
    await send_text(w, {"type": "auth_ok", "ha_version": "2026.11.0"})
    while True:
        try: raw = await recv_text(r, w)
        except (EOFError, asyncio.IncompleteReadError): break
        m = json.loads(raw); mid = m["id"]
        # unrelated event first, to check id filtering
        await send_text(w, {"id": 999, "type": "event", "event": {}}, ping_first=True)
        if m["type"] == "media_source/browse_media":
            cid = m["media_content_id"]
            if cid != "media-source://media_source/local/slideshow":
                await send_text(w, {"id": mid, "type": "result", "success": False,
                                    "error": {"code": "browse_media_failed", "message": "Unknown source directory."}}); continue
            kids = [
                {"title": "a.jpg", "media_class": "image", "media_content_type": "image/jpeg",
                 "media_content_id": cid + "/a.jpg", "can_play": True, "can_expand": False},
                {"title": "b.png", "media_class": "image", "media_content_type": "image/png",
                 "media_content_id": cid + "/b.png", "can_play": True, "can_expand": False},
                {"title": "clip.mp4", "media_class": "video", "media_content_type": "video/mp4",
                 "media_content_id": cid + "/clip.mp4", "can_play": True, "can_expand": False},
                {"title": "sub", "media_class": "directory", "media_content_type": "",
                 "media_content_id": cid + "/sub", "can_play": False, "can_expand": True},
            ]
            # pad to force a 64-bit length frame (>65535 bytes)
            res = {"title": "slideshow", "media_class": "directory", "children": kids, "pad": "x" * 70000}
            await send_text(w, {"id": mid, "type": "result", "success": True, "result": res}, fragmented=True)
        elif m["type"] == "media_source/resolve_media":
            name = m["media_content_id"].rsplit("/", 1)[1]
            await send_text(w, {"id": mid, "type": "result", "success": True,
                                "result": {"url": "http://internal.example:8123/media/local/slideshow/%s?authSig=SIG" % name,
                                           "mime_type": "image/jpeg"}})
        else:
            await send_text(w, {"id": mid, "type": "result", "success": False, "error": {"code": "unknown_command", "message": "?"}})
    w.close()

async def handle(r, w):
    req = await r.readuntil(b"\r\n\r\n")
    lines = req.decode().split("\r\n"); path = lines[0].split()[1]
    hdr = {l.split(":", 1)[0].lower(): l.split(":", 1)[1].strip() for l in lines[1:] if ":" in l}
    if path == "/api/websocket" and hdr.get("upgrade", "").lower() == "websocket":
        acc = base64.b64encode(hashlib.sha1(hdr["sec-websocket-key"].encode() + GUID).digest()).decode()
        w.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                 "Sec-WebSocket-Accept: %s\r\n\r\n" % acc).encode()); await w.drain()
        await handle_ws(r, w); return
    if path == "/" or path.startswith("/lovelace"):
        w.write(b"HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: %d\r\nConnection: close\r\n\r\n" % len(PAGE) + PAGE)
        await w.drain(); w.close(); return
    if path.startswith("/media/local/slideshow/") and "authSig=SIG" in path:
        name = path.split("?")[0].rsplit("/", 1)[1]
        fp = os.path.join(HERE, name)
        body = IMG
        if os.environ.get("MOCK_REAL_IMAGES") and name in ("a.jpg", "b.png", "c.jpg"):
            body = open(fp, "rb").read()
        print("GET", path, "auth=" + hdr.get("authorization", "-"), len(body), flush=True)
        w.write(b"HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: %d\r\nConnection: close\r\n\r\n" % len(body) + body)
    else:
        w.write(b"HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
    await w.drain(); w.close()

async def main():
    port = int(sys.argv[1]); ctx = None
    if len(sys.argv) > 3:
        ctx = ssl.create_default_context(ssl.Purpose.CLIENT_AUTH); ctx.load_cert_chain(sys.argv[2], sys.argv[3])
    srv = await asyncio.start_server(handle, "127.0.0.1", port, ssl=ctx)
    print("ready", port, flush=True)
    async with srv: await srv.serve_forever()

asyncio.run(main())
