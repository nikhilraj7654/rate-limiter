import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

public class RateLimiterServer {

    static final int CAPACITY = 5;
    static final long REFILL_MS = 30000;

    static final String PAGE = """
<!DOCTYPE html><html><head><meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Rate Limiter</title>
<style>
body{font-family:Arial;max-width:680px;margin:30px auto;padding:0 16px}
#users{display:flex;gap:10px;margin:14px 0}
.card{flex:1;border:2px solid #ccc;border-radius:10px;padding:10px;cursor:pointer}
.card.on{border-color:#2b6cb0;background:#f0f6ff}
.bx{font-size:22px;letter-spacing:3px;margin:6px 0}
.ok{color:#11743a}.no{color:#b42318}
button{padding:10px 16px;margin:4px 4px 4px 0;cursor:pointer}
button:disabled{opacity:.5;cursor:not-allowed}
#msg{padding:12px;border-radius:8px;background:#eee;margin-top:10px}
#det{margin-top:16px;border:1px solid #ddd;border-radius:10px;padding:10px}
td{padding:5px 4px;border-bottom:1px solid #eee;font-size:13px;word-break:break-all}
td:first-child{width:38%;color:#555;font-weight:bold}
</style></head><body>
<h2>Token Bucket Rate Limiter</h2>
<p>Each user has 5 tokens. One token comes back every __SEC__ seconds. Click a user to see its details.</p>
<div id="users"></div>
<button id="send">Send Request</button><button id="spam">Spam 10</button>
<div id="msg">Ready</div>
<div id="det"><b id="dt"></b><table id="tab" width="100%"></table></div>
<script>
var CAP = __CAP__, RF = __REFILL__, act = 0, U = [];
var sid = Date.now().toString(36);
function $(x) { return document.getElementById(x); }
function esc(t) { return String(t).replace(/&/g, "&amp;").replace(/</g, "&lt;"); }
function time(t) { return t ? new Date(t).toLocaleTimeString() : "-"; }
function cur(s) { return Math.min(CAP, s.tok + (Date.now() - s.stamp) / RF); }

for (let i = 0; i < 3; i++) {
  U.push({ id: "user" + (i + 1) + "-" + sid, tok: CAP, stamp: Date.now(), until: 0, ok: 0, no: 0,
           busy: false, last: 0, code: "-", retry: "-", msg: "Ready", ip: "-", ua: "-", start: Date.now() });
  var d = document.createElement("div");
  d.onclick = function () { act = i; tick(); };
  $("users").appendChild(d);
}

function tick() {
  var now = Date.now();
  U.forEach(function (s, i) {
    var t = cur(s), n = Math.floor(t + 0.001), left = s.until - now;
    var d = $("users").children[i];
    d.className = "card" + (i === act ? " on" : "");
    d.innerHTML = "<b>User " + (i + 1) + "</b><div class='bx'>" + "\u25A0".repeat(n) + "\u25A1".repeat(CAP - n) +
      "</div><b class='" + (left > 0 ? "no'>Blocked: wait " + (left / 1000).toFixed(1) + " s" : "ok'>Ready (" + t.toFixed(1) + "/" + CAP + ")") +
      "</b><div style='font-size:12px'>Allowed " + s.ok + " | Blocked " + s.no + "</div>";
  });
  var a = U[act], left = a.until - now;
  $("msg").textContent = left > 0 ? "Too many requests. Please wait " + (left / 1000).toFixed(1) + " s" : a.msg;
  $("msg").className = left > 0 ? "no" : "";
  $("send").disabled = $("spam").disabled = a.busy || left > 0;
  $("dt").textContent = "Client Details: User " + (act + 1);
  $("tab").innerHTML = [
    ["Client ID", a.id], ["Status", left > 0 ? "Blocked (" + (left / 1000).toFixed(1) + " s left)" : "Ready"],
    ["Tokens", cur(a).toFixed(2) + " / " + CAP], ["Refill", "1 token / " + RF / 1000 + " s"],
    ["Total requests", a.ok + a.no], ["Last request", time(a.last)], ["Last HTTP status", a.code],
    ["Retry after (ms)", a.retry], ["IP (seen by server)", a.ip], ["Browser (seen by server)", a.ua],
    ["Session started", time(a.start)]
  ].map(function (r) { return "<tr><td>" + r[0] + "</td><td>" + esc(r[1]) + "</td></tr>"; }).join("");
}
setInterval(tick, 200);

function hit(s) {
  return fetch("/allow?clientId=" + encodeURIComponent(s.id))
    .then(function (x) { return x.json().then(function (d) { return [x.status, d]; }); })
    .then(function (r) {
      var d = r[1], now = Date.now();
      s.stamp = s.last = now; s.code = r[0]; s.retry = d.retryAfterMs;
      s.ip = d.ip; s.ua = d.userAgent; s.tok = d.remaining; s.msg = d.message;
      if (d.allowed) { s.ok++; s.until = 0; s.msg = "Allowed. Remaining: " + d.remaining; }
      else { s.no++; s.until = now + d.retryAfterMs; }
      tick();
      return d.allowed;
    })
    .catch(function () { s.msg = "Request failed. Check the server."; s.code = "failed"; tick(); return false; });
}

$("send").onclick = function () { hit(U[act]); };
$("spam").onclick = function () {
  var s = U[act], n = 0;
  s.busy = true;
  (function next() {
    if (n++ >= 10) { s.busy = false; return; }
    hit(s).then(function (ok) { if (ok) setTimeout(next, 120); else s.busy = false; });
  })();
};
tick();
</script></body></html>
""";

    static void send(HttpExchange ex, int code, String type, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replaceAll("\\p{Cntrl}", "");
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(CAPACITY, REFILL_MS);
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        String page = PAGE.replace("__CAP__", "" + CAPACITY)
                          .replace("__REFILL__", "" + REFILL_MS)
                          .replace("__SEC__", "" + REFILL_MS / 1000);

        server.createContext("/", ex -> send(ex, 200, "text/html; charset=UTF-8", page));

        server.createContext("/allow", ex -> {
            String q = ex.getRequestURI().getQuery();
            String id = (q != null && q.startsWith("clientId=")) ? q.substring(9).replaceAll("[^a-zA-Z0-9_-]", "") : "";
            if (id.isEmpty()) id = "anonymous";
            if (id.length() > 40) id = id.substring(0, 40);

            // Render is behind a proxy, so X-Forwarded-For comes first
            String ip = ex.getRequestHeaders().getFirst("X-Forwarded-For");
            ip = (ip != null && !ip.isEmpty()) ? ip.split(",")[0].trim()
                                               : ex.getRemoteAddress().getAddress().getHostAddress();
            String ua = ex.getRequestHeaders().getFirst("User-Agent");

            TokenBucketRateLimiter.Result r = limiter.tryAcquire(id);
            String msg = r.allowed() ? "Request allowed" : "Too many requests. Wait " + r.waitMs() / 1000.0 + " seconds";
            String body = "{\"allowed\":" + r.allowed() + ",\"remaining\":" + r.remaining()
                    + ",\"retryAfterMs\":" + r.waitMs() + ",\"ip\":\"" + esc(ip)
                    + "\",\"userAgent\":\"" + esc(ua) + "\",\"message\":\"" + msg + "\"}";
            if (!r.allowed()) ex.getResponseHeaders().set("Retry-After", "" + (r.waitMs() + 999) / 1000);
            send(ex, r.allowed() ? 200 : 429, "application/json; charset=UTF-8", body);
        });

        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("Running on port " + port);
    }
}
