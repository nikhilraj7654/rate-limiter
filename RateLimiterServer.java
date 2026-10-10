import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class RateLimiterServer {

    static final int CAPACITY = 5;
    static final long REFILL_MS = 10000; // 1 token comes back every 10 seconds

    static final String PAGE = """
<!DOCTYPE html><html><head><meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Rate Limiter</title>
<style>
body{font-family:Arial;max-width:680px;margin:30px auto;padding:0 16px}
#users{display:flex;gap:10px;margin:14px 0}
.card{flex:1;border:2px solid #ccc;border-radius:10px;padding:10px;cursor:pointer}
.card.on{border-color:#2b6cb0;background:#f0f6ff}
.bx{font-size:22px;letter-spacing:3px;margin:6px 0}
.st{font-size:14px;font-weight:bold}.ct{font-size:12px;color:#555;margin-top:4px}
button{padding:10px 16px;margin:4px 4px 4px 0;cursor:pointer}
button:disabled{opacity:.5;cursor:not-allowed}
#msg{padding:12px;border-radius:8px;background:#eee;margin-top:10px}
.ok{color:#11743a}.no{color:#b42318}
#msg.ok{background:#e6f9ec}#msg.no{background:#fdeaea}
</style></head><body>
<h2>Token Bucket Rate Limiter</h2>
<p>Each user has 5 tokens. A request uses 1 token. One token comes back every __SEC__ seconds. Click a user to switch.</p>
<div id="users"></div>
<div>Active: <b id="who"></b></div>
<button id="send">Send Request</button><button id="spam">Spam 10</button>
<div id="msg">Ready</div>
<script>
var CAP = __CAP__, REFILL = __REFILL__, run = 0, busy = false, active = 0;
var sid = Date.now().toString(36);
var users = [], cards = [];
function $(x) { return document.getElementById(x); }

for (var i = 0; i < 3; i++) {
  users.push({ id: "user" + (i + 1) + "-" + sid, tokens: CAP, stamp: Date.now(),
               until: 0, ok: 0, no: 0, msg: "Ready", cls: "" });
  var d = document.createElement("div");
  d.innerHTML = "<b>User " + (i + 1) + "</b><div class='bx'></div><div class='st'></div><div class='ct'></div>";
  d.onclick = (function (k) { return function () { pick(k); }; })(i);
  $("users").appendChild(d);
  cards.push(d);
}

function cur(s) { return Math.min(CAP, s.tokens + (Date.now() - s.stamp) / REFILL); }

function tick() {
  var now = Date.now();
  users.forEach(function (s, i) {
    var c = cards[i], t = cur(s), n = Math.floor(t + 0.001), left = s.until - now;
    c.querySelector(".bx").textContent = "\\u25A0".repeat(n) + "\\u25A1".repeat(CAP - n);
    var st = c.querySelector(".st");
    if (left > 0) {
      st.textContent = "Blocked: wait " + (left / 1000).toFixed(1) + " s";
      st.className = "st no";
    } else {
      if (s.until !== 0) { s.until = 0; s.msg = "You can send requests again!"; s.cls = "ok"; }
      st.textContent = "Ready (" + t.toFixed(1) + " / " + CAP + ")";
      st.className = "st ok";
    }
    c.querySelector(".ct").textContent = "Allowed " + s.ok + " | Blocked " + s.no;
    c.className = "card" + (i === active ? " on" : "");
  });

  var a = users[active], aLeft = a.until - now;
  $("who").textContent = "User " + (active + 1);
  if (aLeft > 0) {
    $("msg").textContent = "Too many requests. Please wait " + (aLeft / 1000).toFixed(1) + " s";
    $("msg").className = "no";
  } else {
    $("msg").textContent = a.msg;
    $("msg").className = a.cls;
  }
  $("send").disabled = busy || aLeft > 0;
  $("spam").disabled = busy || aLeft > 0;
}
setInterval(tick, 200);

// Switching stops any running spam but keeps every user's own status
function pick(i) { run++; busy = false; active = i; tick(); }

function hit() {
  var s = users[active];
  return fetch("/allow?clientId=" + s.id)
    .then(function (x) { return x.json(); })
    .then(function (d) {
      var now = Date.now();
      s.stamp = now;
      if (d.allowed) {
        s.ok++; s.tokens = d.remaining; s.until = 0;
        s.msg = "Allowed. Remaining: " + d.remaining; s.cls = "ok";
      } else {
        s.no++; s.tokens = 1 - d.retryAfterMs / REFILL; s.until = now + d.retryAfterMs;
      }
      tick();
      return d.allowed;
    })
    .catch(function () { return false; });
}

$("send").onclick = hit;
$("spam").onclick = function () {
  var r = run, n = 0;
  busy = true;
  (function next() {
    if (r !== run) return;
    if (n++ >= 10) { busy = false; return; }
    hit().then(function (ok) {
      if (r !== run) return;
      if (ok) setTimeout(next, 120); else busy = false;
    });
  })();
};
tick();
</script></body></html>
""";

    static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type);
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(CAPACITY, REFILL_MS);
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        String page = PAGE.replace("__CAP__", String.valueOf(CAPACITY))
                          .replace("__REFILL__", String.valueOf(REFILL_MS))
                          .replace("__SEC__", String.valueOf(REFILL_MS / 1000));

        server.createContext("/", ex -> send(ex, 200, "text/html; charset=UTF-8", page));

        server.createContext("/allow", ex -> {
            String q = ex.getRequestURI().getQuery();
            String id = (q != null && q.startsWith("clientId="))
                    ? q.substring(9).replaceAll("[^a-zA-Z0-9_-]", "") : "";
            if (id.isEmpty()) id = "anonymous";
            if (id.length() > 40) id = id.substring(0, 40);

            TokenBucketRateLimiter.Result r = limiter.tryAcquire(id);
            String msg = r.allowed() ? "Request allowed"
                    : "Too many requests. Wait " + r.waitMs() / 1000.0 + " seconds";
            String body = "{\"clientId\":\"" + id + "\",\"allowed\":" + r.allowed()
                    + ",\"remaining\":" + r.remaining()
                    + ",\"retryAfterMs\":" + r.waitMs()
                    + ",\"message\":\"" + msg + "\"}";
            if (!r.allowed()) {
                ex.getResponseHeaders().add("Retry-After", String.valueOf((r.waitMs() + 999) / 1000));
            }
            send(ex, r.allowed() ? 200 : 429, "application/json", body);
        });

        server.start();
        System.out.println("Running on port " + port);
    }
}
