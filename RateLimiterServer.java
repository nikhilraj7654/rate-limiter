import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class RateLimiterServer {

    static final int CAPACITY = 5;
    static final long REFILL_MS = 2000;

    static final String PAGE = """
<!DOCTYPE html><html><head><meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Rate Limiter</title>
<style>
body{font-family:Arial;max-width:520px;margin:30px auto;padding:0 16px}
button{padding:10px 16px;margin:4px 4px 4px 0;cursor:pointer}
button:disabled{opacity:.5;cursor:not-allowed}
#box{font-size:28px;letter-spacing:4px;margin:10px 0}
#msg{padding:12px;border-radius:8px;background:#eee}
.ok{background:#e6f9ec!important;color:#11743a}
.no{background:#fdeaea!important;color:#b42318}
</style></head><body>
<h2>Token Bucket Rate Limiter</h2>
<p>5 tokens per user. Each request uses 1. One token comes back every 2 seconds.</p>
<div>User: <b id="uid"></b> <button id="sw">Switch user</button></div>
<div id="box"></div>
<button id="send">Send Request</button><button id="spam">Spam 10</button>
<div id="msg">Ready</div>
<script>
var CAP = 5, id, run = 0, timer;
function $(x) { return document.getElementById(x); }
function show(n) { $("box").textContent = "■".repeat(n) + "□".repeat(CAP - n); }
function msg(t, c) { $("msg").textContent = t; $("msg").className = c; }
function lock(v) { $("send").disabled = v; $("spam").disabled = v; }

function start() {
  run++;
  clearInterval(timer);
  id = "user-" + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
  $("uid").textContent = id;
  show(CAP);
  lock(false);
  msg("New user started with a full bucket", "ok");
}

function wait(ms) {
  var end = Date.now() + ms;
  lock(true);
  clearInterval(timer);
  timer = setInterval(function () {
    var left = end - Date.now();
    if (left > 0) {
      msg("Too many requests. Please wait " + (left / 1000).toFixed(1) + " s", "no");
    } else {
      clearInterval(timer);
      show(1);
      lock(false);
      msg("You can send requests again!", "ok");
    }
  }, 100);
}

// resolves true only if the request was allowed
function hit() {
  var r = run, u = id;
  return fetch("/allow?clientId=" + u)
    .then(function (x) { return x.json(); })
    .then(function (d) {
      if (r !== run) return false;
      show(d.remaining);
      if (d.allowed) msg("Allowed. Remaining: " + d.remaining, "ok");
      else wait(d.retryAfterMs);
      return d.allowed;
    })
    .catch(function () { return false; });
}

$("send").onclick = hit;
$("sw").onclick = start;
$("spam").onclick = function () {
  var r = run, n = 0;
  (function next() {
    if (r !== run || n++ >= 10) return;
    hit().then(function (ok) { if (ok) setTimeout(next, 120); });
  })();
};
start();
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

        server.createContext("/", ex -> send(ex, 200, "text/html; charset=UTF-8", PAGE));

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
