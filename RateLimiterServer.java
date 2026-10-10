```java
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class RateLimiterServer {

    static final int CAPACITY = 5;
    static final long REFILL_MS = 30000;

    static final String PAGE = """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Rate Limiter</title>

<style>
body {
    font-family: Arial;
    max-width: 680px;
    margin: 30px auto;
    padding: 0 16px;
}
#users {
    display: flex;
    gap: 10px;
    margin: 14px 0;
}
.card {
    flex: 1;
    border: 2px solid #ccc;
    border-radius: 10px;
    padding: 10px;
    cursor: pointer;
}
.card.on {
    border-color: #2b6cb0;
    background: #f0f6ff;
}
.bx {
    font-size: 22px;
    letter-spacing: 3px;
    margin: 6px 0;
}
.st {
    font-size: 14px;
    font-weight: bold;
}
.ct {
    font-size: 12px;
    color: #555;
    margin-top: 4px;
}
button {
    padding: 10px 16px;
    margin: 4px 4px 4px 0;
    cursor: pointer;
}
button:disabled {
    opacity: .5;
    cursor: not-allowed;
}
#msg {
    padding: 12px;
    border-radius: 8px;
    background: #eee;
    margin-top: 10px;
}
.ok {
    color: #11743a;
}
.no {
    color: #b42318;
}
#msg.ok {
    background: #e6f9ec;
}
#msg.no {
    background: #fdeaea;
}
#clientData {
    border: 1px solid #ccc;
    border-radius: 10px;
    padding: 15px;
    margin: 15px 0;
    background: #f8fafc;
    overflow-wrap: anywhere;
}
#clientData p {
    padding: 8px;
    background: white;
    border-radius: 5px;
}
</style>
</head>

<body>

<h2>Token Bucket Rate Limiter</h2>

<p>
Each user has 5 tokens. A request uses 1 token.
One token comes back every __SEC__ seconds.
Switch users without stopping their requests.
</p>

<div id="users"></div>

<div>Active: <b id="who"></b></div>

<button id="send">Send Request</button>
<button id="spam">Spam 10</button>

<div id="msg">Ready</div>

<!-- Client details panel -->
<div id="clientData">
    <h3>Client Details</h3>
    <p>Select a user to view client data.</p>
</div>

<script>
var CAP = __CAP__;
var REFILL = __REFILL__;
var active = 0;

var sid = Date.now().toString(36);
var users = [];
var cards = [];

function $(x) {
    return document.getElementById(x);
}

// Create three independent clients
for (var i = 0; i < 3; i++) {

    users.push({
        id: "user" + (i + 1) + "-" + sid,
        tokens: CAP,
        stamp: Date.now(),
        until: 0,
        ok: 0,
        no: 0,
        msg: "Ready",
        cls: "ok",
        busy: false
    });

    var d = document.createElement("div");

    d.className = "card";

    d.innerHTML =
        "<b>User " + (i + 1) +
        "</b><div class='bx'></div>" +
        "<div class='st'></div>" +
        "<div class='ct'></div>";

    d.onclick = (function(k) {
        return function() {
            pick(k);
        };
    })(i);

    $("users").appendChild(d);
    cards.push(d);
}

// Calculate current token count
function cur(s) {
    return Math.min(
        CAP,
        s.tokens + (Date.now() - s.stamp) / REFILL
    );
}

// Update user cards and client details
function tick() {

    var now = Date.now();

    users.forEach(function(s, i) {

        var c = cards[i];
        var t = cur(s);
        var n = Math.floor(t + 0.001);
        var left = s.until - now;

        c.querySelector(".bx").textContent =
            "\u25A0".repeat(n) +
            "\u25A1".repeat(CAP - n);

        var st = c.querySelector(".st");

        if (left > 0) {

            st.textContent =
                "Blocked: wait " +
                (left / 1000).toFixed(1) + " s";

            st.className = "st no";

        } else {

            if (s.until !== 0) {
                s.until = 0;
                s.msg = "You can send requests again!";
                s.cls = "ok";
            }

            st.textContent =
                "Ready (" + t.toFixed(1) + " / " + CAP + ")";

            st.className = "st ok";
        }

        c.querySelector(".ct").textContent =
            "Allowed " + s.ok + " | Blocked " + s.no;

        c.className =
            "card" + (i === active ? " on" : "");
    });

    var a = users[active];
    var aLeft = a.until - now;

    $("who").textContent = "User " + (active + 1);

    // Display selected client's details
    $("clientData").innerHTML = `
        <h3>User ${active + 1} - Client Details</h3>

        <p><b>Client ID:</b> ${a.id}</p>

        <p><b>Total Requests:</b> ${a.ok + a.no}</p>

        <p><b>Allowed Requests:</b> ${a.ok}</p>

        <p><b>Blocked Requests:</b> ${a.no}</p>

        <p><b>Available Tokens:</b>
        ${cur(a).toFixed(2)} / ${CAP}</p>

        <p><b>Request Status:</b>
        ${aLeft > 0 ? "Blocked" :
          a.busy ? "Processing" : "Ready"}</p>
    `;

    if (aLeft > 0) {

        $("msg").textContent =
            "Too many requests. Please wait " +
            (aLeft / 1000).toFixed(1) + " s";

        $("msg").className = "no";

    } else {

        $("msg").textContent = a.msg;
        $("msg").className = a.cls;
    }

    $("send").disabled = a.busy || aLeft > 0;
    $("spam").disabled = a.busy || aLeft > 0;
}

// Refresh UI every 200 milliseconds
setInterval(tick, 200);

// Switch selected client
function pick(i) {
    active = i;
    tick();
}

// Send request for a specific client
function hitUser(s) {

    return fetch(
        "/allow?clientId=" + encodeURIComponent(s.id)
    )
    .then(function(x) {

        return x.json().then(function(d) {
            return {
                status: x.status,
                data: d
            };
        });
    })
    .then(function(result) {

        var d = result.data;
        var now = Date.now();

        s.stamp = now;

        if (d.allowed) {

            s.ok++;
            s.tokens = d.remaining;
            s.until = 0;
            s.msg = "Allowed. Remaining: " + d.remaining;
            s.cls = "ok";

        } else {

            s.no++;
            s.tokens = d.remaining;
            s.until = now + d.retryAfterMs;
            s.msg = d.message || "Rate limit reached";
            s.cls = "no";
        }

        tick();

        return d.allowed;
    })
    .catch(function() {

        s.msg = "Request failed. Check the server.";
        s.cls = "no";

        tick();

        return false;
    });
}

// Send one request
function hit() {
    return hitUser(users[active]);
}

$("send").onclick = hit;

// Send up to 10 requests for selected client
$("spam").onclick = function() {

    var s = users[active];

    if (s.busy || s.until > Date.now()) {
        return;
    }

    s.busy = true;

    var n = 0;

    function next() {

        if (n >= 10 || s.until > Date.now()) {
            s.busy = false;
            tick();
            return;
        }

        n++;

        hitUser(s).then(function(allowed) {

            if (allowed) {
                setTimeout(next, 120);
            } else {
                s.busy = false;
                tick();
            }
        });
    }

    next();
};

tick();
</script>

</body>
</html>
""";

    static void send(
            HttpExchange ex,
            int code,
            String type,
            String body
    ) throws IOException {

        byte[] b = body.getBytes(StandardCharsets.UTF_8);

        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set(
                "Access-Control-Allow-Origin", "*"
        );

        ex.sendResponseHeaders(code, b.length);

        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    public static void main(String[] args) throws Exception {

        int port = Integer.parseInt(
                System.getenv().getOrDefault("PORT", "8080")
        );

        TokenBucketRateLimiter limiter =
                new TokenBucketRateLimiter(CAPACITY, REFILL_MS);

        HttpServer server =
                HttpServer.create(new InetSocketAddress(port), 0);

        String page = PAGE
                .replace("__CAP__", String.valueOf(CAPACITY))
                .replace("__REFILL__", String.valueOf(REFILL_MS))
                .replace("__SEC__", String.valueOf(REFILL_MS / 1000));

        // Home page
        server.createContext("/", ex -> {

            if (!ex.getRequestMethod().equals("GET")) {
                send(ex, 405, "text/plain", "Method not allowed");
                return;
            }

            send(
                    ex,
                    200,
                    "text/html; charset=UTF-8",
                    page
            );
        });

        // Rate limiter API
        server.createContext("/allow", ex -> {

            if (!ex.getRequestMethod().equals("GET")) {
                send(
                        ex,
                        405,
                        "application/json",
                        "{\"message\":\"Method not allowed\"}"
                );
                return;
            }

            String q = ex.getRequestURI().getQuery();

            String id = (q != null && q.startsWith("clientId="))
                    ? q.substring(9)
                          .replaceAll("[^a-zA-Z0-9_-]", "")
                    : "";

            if (id.isEmpty()) {
                id = "anonymous";
            }

            if (id.length() > 40) {
                id = id.substring(0, 40);
            }

            TokenBucketRateLimiter.Result r =
                    limiter.tryAcquire(id);

            String msg = r.allowed()
                    ? "Request allowed"
                    : "Too many requests. Wait "
                      + r.waitMs() / 1000.0 + " seconds";

            String body =
                    "{\"clientId\":\"" + id
                    + "\",\"allowed\":" + r.allowed()
                    + ",\"remaining\":" + r.remaining()
                    + ",\"retryAfterMs\":" + r.waitMs()
                    + ",\"message\":\"" + msg + "\"}";

            if (!r.allowed()) {
                ex.getResponseHeaders().set(
                        "Retry-After",
                        String.valueOf(
                                (r.waitMs() + 999) / 1000
                        )
                );
            }

            send(
                    ex,
                    r.allowed() ? 200 : 429,
                    "application/json; charset=UTF-8",
                    body
            );
        });

        // Handle simultaneous requests
        server.setExecutor(
                java.util.concurrent.Executors
                        .newCachedThreadPool()
        );

        server.start();

        System.out.println("Running on port " + port);
    }
}
```
