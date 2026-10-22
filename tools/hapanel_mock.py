#!/usr/bin/env python3
"""
Symulator tabletu HA Panel – to samo API HTTP co aplikacja na Androidzie.
Do testowania integracji HA (encje, przyciski, media_player, notify) i automatyzacji
bez tabletu. Tylko biblioteka standardowa Pythona 3.8+.

Uruchom:   python3 hapanel_mock.py            (port 8765)
           python3 hapanel_mock.py --port 8765 --token SEKRET
           python3 hapanel_mock.py --mdns       (HA wykryje symulator sam; pip install zeroconf)
W HA dodaj integrację HA Panel z adresem IP komputera, na którym to działa.
Panel sterowania w przeglądarce:  http://IP:8765/ui
"""
import argparse, json, threading, time, random
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

S = {
    "battery": 80, "charging": True, "motion": False, "lux": 120.0, "sleeping": False,
    "brightness": 128, "auto": False, "volume": 60, "playing": False,
    "wifi_rssi": -55, "ip": "127.0.0.1", "version": "2.5-mock", "url": "",
    "configured": False, "missing": "", "popup": "", "night": False, "clock": False,
    "battery_temp": 28.5, "battery_health": "good", "mem_free": 812, "uptime": 12.3,
    "last_load": int(time.time()), "webview": "mock", "webhook": False,
    "night_enabled": False, "fallback": False, "proximity": False, "clock_out": "", "clock_in": "", "clock_info": "",
}
CFG = {}
TOKEN = ""
WEBHOOK = ""
SHOT = __import__("base64").b64decode("/9j/4AAQSkZJRgABAgAAAQABAAD//gAQTGF2YzYwLjMxLjEwMgD/2wBDAAgMDA4MDhAQEBAQEBMSExQUFBMTExMUFBQVFRUZGRkVFRUUFBUVGBgZGRscGxoaGRocHB4eHiQkIiIqKiszMz7/xABMAAEBAAAAAAAAAAAAAAAAAAAABwEBAQAAAAAAAAAAAAAAAAAAAAMQAQAAAAAAAAAAAAAAAAAAAAARAQAAAAAAAAAAAAAAAAAAAAD/wAARCADIAUADASIAAhEAAxEA/9oADAMBAAIRAxEAPwCLgLJAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAP/Z")
PIN = ""
PAIR_CODE = "123456"
LOCK = threading.Lock()
ARGS = None
LOG = []


def log(msg):
    line = time.strftime("%H:%M:%S ") + msg
    print(line, flush=True)
    LOG.append(line)
    del LOG[:-40]


def stop_playing_later(sec=3.0):
    def _():
        time.sleep(sec)
        with LOCK:
            S["playing"] = False
    threading.Thread(target=_, daemon=True).start()


def post_event(event, **kw):
    """Wysyła zdarzenie na webhook HA (jak aplikacja na tablecie)."""
    url = WEBHOOK
    if not url: return
    def _go():
        import urllib.request
        body = json.dumps({"event": event, **kw}).encode()
        try:
            urllib.request.urlopen(urllib.request.Request(url, body, {"Content-Type": "application/json"}), timeout=3)
            log(f"WEBHOOK -> {event} {kw}")
        except Exception as e:
            log(f"WEBHOOK błąd: {e}")
    threading.Thread(target=_go, daemon=True).start()


def handle(path, q):
    global TOKEN, PIN, WEBHOOK
    p = lambda k: (q.get(k) or [None])[0]
    if path == "/info":   # bez tokena, jak w aplikacji
        return 200, {"id": "hapanel-mock", "name": "mock", "version": S["version"], "auth": bool(TOKEN), "paired": bool(TOKEN), "pin": bool(PIN)}
    if TOKEN and p("token") != TOKEN:
        return 401, {"error": "token"}
    with LOCK:
        if path == "/status":
            d = dict(S); d["paired"] = bool(TOKEN); d["pin"] = bool(PIN)
            return 200, d
        if path == "/wake":
            S["sleeping"] = False; log("EKRAN: wybudzony")
        elif path == "/sleep":
            S["sleeping"] = True; log("EKRAN: uśpiony (czarna nakładka)")
        elif path == "/brightness":
            try: v = int(p("value"))
            except (TypeError, ValueError): return 400, {"error": "value 0-255"}
            S["brightness"] = max(0, min(255, v)); S["auto"] = False; log(f"JASNOŚĆ: {S['brightness']} (auto wyłączone)")
        elif path == "/auto":
            S["auto"] = p("on") != "0"; log(f"AUTO-JASNOŚĆ: {S['auto']}")
        elif path == "/load":
            S["url"] = p("url") or S["url"]; log(f"ŁADUJ ADRES: {S['url']}")
        elif path == "/reload":
            log("ODŚWIEŻ dashboard")
        elif path == "/restart":
            log("RESTART panelu")
        elif path == "/tts":
            if p("wake") == "1": S["sleeping"] = False
            if p("volume"): S["volume"] = max(0, min(100, int(p("volume"))))
            log(f"TTS (mówi, głośność {S['volume']}%): {p('text')}")
        elif path == "/toast":
            if p("wake") == "1": S["sleeping"] = False
            log(f"TOAST: {p('text')}")
        elif path == "/playsound":
            if not p("url"): return 400, {"error": "url"}
            if p("volume"): S["volume"] = max(0, min(100, int(p("volume"))))
            S["playing"] = True; log(f"GRA dźwięk: {p('url')}"); stop_playing_later()
        elif path == "/stopsound":
            S["playing"] = False; log("STOP dźwięku")
        elif path == "/volume":
            try: v = int(p("value"))
            except (TypeError, ValueError): return 400, {"error": "value 0-100"}
            S["volume"] = max(0, min(100, v)); log(f"GŁOŚNOŚĆ: {S['volume']}")
        elif path == "/config":   # zdalna konfiguracja z integracji HA
            if not TOKEN and p("code") != PAIR_CODE:
                log("KONFIGURACJA odrzucona: zły kod parowania")
                return 403, {"error": "code"}
            if p("pin") is not None:
                if p("pin") == "": PIN = ""; log("PIN usunięty")
                elif len(p("pin")) < 4: return 400, {"error": "pin min 4"}
                else: PIN = p("pin"); log("PIN ustawiony (wartość nie jest nigdzie wyświetlana)")
            for k in ("dashboard", "fallback", "homeAfter", "sleepAfter", "motion", "motionThreshold", "wakeOnMotion",
                      "auto", "lockTask", "nightEnabled", "wakeOnProximity", "nightStart", "nightEnd", "nightBrightness", "nightWake",
                      "css", "js", "zoom"):
                if p(k) is not None: CFG[k] = p(k)
            if p("dashboard"): S["url"] = p("dashboard"); S["configured"] = True
            if p("auto") is not None: S["auto"] = p("auto") == "1"
            if p("nightEnabled") is not None: S["night_enabled"] = p("nightEnabled") == "1"
            if p("night") is not None: S["night_enabled"] = p("night") == "1"
            if p("apiToken"): TOKEN = p("apiToken")
            if p("webhook") is not None:
                WEBHOOK = p("webhook"); S["webhook"] = bool(WEBHOOK)
                if WEBHOOK: post_event("start", version=S["version"])
            log(f"KONFIGURACJA z HA: {CFG} (token: {'ustawiony' if TOKEN else 'brak'})")
        elif path == "/popup":
            if not p("url"): return 400, {"error": "url"}
            if p("wake") != "0": S["sleeping"] = False
            sec = int(p("seconds") or 30)
            S["popup"] = p("url")
            log(f"POPUP: {p('url')} na {sec}s (wake={p('wake') != '0'}, tytuł={p('title') or '-'})")
            if sec > 0:
                def _close():
                    time.sleep(sec)
                    with LOCK:
                        if S.get("popup") == p("url"): S["popup"] = ""; log("POPUP: zamknięty po czasie")
                threading.Thread(target=_close, daemon=True).start()
        elif path == "/export":
            d = {"dashboard": S["url"], "fallback": "", "homeAfter": "0", "sleepAfter": "0", "motion": "0",
                 "motionThreshold": "5", "wakeOnMotion": "1", "auto": "0", "lockTask": "1", "nightEnabled": "0",
                 "nightStart": "22:00", "nightEnd": "06:30", "nightBrightness": "10", "nightWake": "60",
                 "css": "", "js": "", "zoom": "100"}
            d.update({k: v for k, v in CFG.items() if k in d})
            return 200, d
        elif path == "/update":
            if not (p("url") or "").startswith("https://github.com/"): return 400, {"error": "url"}
            log(f"AKTUALIZACJA: pobieranie {p('url')} (symulacja; na tablecie pojawi się potwierdzenie instalacji)")
        elif path in ("/closepopup", "/popup_close"):
            S["popup"] = ""; log("POPUP: zamknięty")
        elif path == "/clock":
            for k, key in (("out", "clock_out"), ("in", "clock_in"), ("info", "clock_info")):
                if p(k) is not None: S[key] = p(k)
            log(f"ZEGAR NOCNY: na zewnątrz {S['clock_out']}°, w domu {S['clock_in']}°, info: {S['clock_info'] or '-'}")
        elif path == "/simulate":   # tylko w symulatorze: ?motion=1&battery=40&lux=300&charging=0
            for k, cast in (("motion", lambda x: x == "1"), ("charging", lambda x: x == "1"),
                            ("battery", int), ("lux", float), ("wifi_rssi", int),
                            ("configured", lambda x: x == "1"), ("missing", str),
                            ("proximity", lambda x: x == "1"), ("fallback", lambda x: x == "1")):
                if p(k) is not None: S[k] = cast(p(k))
            if p("motion") is not None: post_event("motion", state=S["motion"])
            if p("touch") == "1": post_event("touch")
            if p("proximity") is not None: post_event("proximity", state=S["proximity"])
            if p("low_battery") == "1": post_event("low_battery", level=S["battery"])
            log(f"SYMULACJA: motion={S['motion']} battery={S['battery']} charging={S['charging']} lux={S['lux']}")
        else:
            return 404, {"error": "not found"}
    return 200, {"ok": True}


UI = """<!doctype html><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<title>HA Panel – symulator</title>
<style>body{font:16px system-ui;margin:20px;max-width:640px}button{margin:3px;padding:8px 12px}
pre{background:#eee;padding:10px;overflow:auto}</style>
<h2>HA Panel – symulator tabletu</h2>
<div>Popup: <button onclick="fetch('/closepopup'+(tk?'?token='+tk:''))">zamknij</button> (pokazuje się w stanie jako "popup")</div>
<div>Konfiguracja: <button onclick="s('configured=0')">jak po świeżej instalacji</button> Uprawnienia: <button onclick="s('missing=mikrofon, bateria')">brakuje</button><button onclick="s('missing=')">komplet</button></div>
<div>Zdarzenia na webhook: <button onclick="s('touch=1')">dotknięcie</button><button onclick="s('low_battery=1')">niska bateria</button></div>
<div>Ruch: <button onclick="s('motion=1')">jest ruch</button><button onclick="s('motion=0')">brak ruchu</button></div>
<div>Ładowanie: <button onclick="s('charging=1')">podłączone</button><button onclick="s('charging=0')">odłączone</button></div>
<div>Bateria: <button onclick="s('battery=20')">20%</button><button onclick="s('battery=50')">50%</button><button onclick="s('battery=85')">85%</button></div>
<div>Światło: <button onclick="s('lux=5')">5 lx</button><button onclick="s('lux=150')">150 lx</button><button onclick="s('lux=800')">800 lx</button></div>
<h3>Stan</h3><pre id=st></pre><h3>Dziennik komend z HA</h3><pre id=lg></pre>
<script>
const tk=new URLSearchParams(location.search).get('token')||'';const q=tk?'&token='+tk:'';
async function s(x){await fetch('/simulate?'+x+q);r()}
async function r(){const a=await (await fetch('/status?'+q.slice(1))).json();st.textContent=JSON.stringify(a,null,1);
 lg.textContent=(await (await fetch('/_log?'+q.slice(1))).text())}
setInterval(r,1500);r();
</script>"""


class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass

    def _send(self, code, body, ctype="application/json"):
        data = body if isinstance(body, bytes) else (body if isinstance(body, str) else json.dumps(body)).encode()
        self.send_response(code); self.send_header("Content-Type", ctype if ctype.startswith("image") else ctype + "; charset=utf-8")
        self.send_header("Content-Length", str(len(data))); self.send_header("Connection", "close")
        self.end_headers(); self.wfile.write(data)

    def do_GET(self):
        u = urlparse(self.path); q = parse_qs(u.query)
        if u.path in ("/", "/ui"): return self._send(200, UI, "text/html")
        if u.path == "/_log": return self._send(200, "\n".join(reversed(LOG)), "text/plain")
        if u.path == "/screenshot":
            if TOKEN and (q.get("token") or [None])[0] != TOKEN: return self._send(401, {"error": "token"})
            log("ZRZUT EKRANU pobrany")
            return self._send(200, SHOT, "image/jpeg")
        code, body = handle(u.path, q)
        self._send(code, body)


def start_mdns(port):
    """Ogłasza symulator w sieci (mDNS), żeby HA go wykrył. Wymaga: pip install zeroconf"""
    try:
        from zeroconf import Zeroconf, ServiceInfo
    except ImportError:
        log("mDNS wyłączony: zainstaluj bibliotekę (pip install zeroconf)")
        return None
    import socket
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1)); ip = s.getsockname()[0]
    except Exception:
        ip = "127.0.0.1"
    finally:
        s.close()
    info = ServiceInfo("_hapanel._tcp.local.", "HAPanel-mock._hapanel._tcp.local.",
                       addresses=[socket.inet_aton(ip)], port=port,
                       properties={"id": "hapanel-mock", "ver": "2.2-mock"}, server="hapanel-mock.local.")
    zc = Zeroconf(); zc.register_service(info)
    log(f"mDNS: ogłoszono _hapanel._tcp pod {ip}:{port}")
    return zc


def drift():
    while True:   # powolna zmiana baterii, jak w prawdziwym tablecie
        time.sleep(60)
        with LOCK:
            S["battery"] = min(100, S["battery"] + 1) if S["charging"] else max(0, S["battery"] - 1)


if __name__ == "__main__":
    ap = argparse.ArgumentParser(); ap.add_argument("--port", type=int, default=8765); ap.add_argument("--token", default="")
    ap.add_argument("--code", default="123456", help="kod parowania (jak na ekranie tabletu)")
    ap.add_argument("--mdns", action="store_true", help="ogłoś w sieci (wykrywanie przez HA), wymaga pip install zeroconf")
    ARGS = ap.parse_args()
    TOKEN = ARGS.token
    PAIR_CODE = ARGS.code
    if ARGS.mdns: start_mdns(ARGS.port)
    threading.Thread(target=drift, daemon=True).start()
    log(f"Kod parowania: {PAIR_CODE} (HA poprosi o niego przy dodawaniu)")
    log(f"Symulator HA Panel na porcie {ARGS.port} (token: {'tak' if ARGS.token else 'brak'}) – panel: /ui")
    ThreadingHTTPServer(("0.0.0.0", ARGS.port), H).serve_forever()
