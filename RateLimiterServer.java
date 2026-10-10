import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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
body { font-family: Arial; max-width: 600px; margin: 30px auto; padding: 0 16px; }
#users { display: flex; gap: 10px; margin-bottom: 12px; }
.card { flex: 1; border: 2px solid #ccc; border-radius: 10px; padding: 10px; cursor: pointer; }
.on { border-color: #2b6cb0; background: #f0f6ff; }
.ok { color: #11743a; }
.no { color: #b42318; }
button { padding: 10px 16px; }
pre { background: #f5f5f5; padding: 10px; border-radius: 8px; white-space: pre-wrap; word-break: break-all; }
</style>
</head>
<body>

<h2>Token Bucket Rate Limiter</h2>
<p>Each user has __CAP__ tokens. One token comes back every __SEC__ seconds. Click a user to see details.</p>

<div id="users"></div>
<button id="send">Send Request</button>
<button id="spam">Spam 10</button>
<h3 id="msg"></h3>
<pre id="details"></pre>

<script>
var CAP = __CAP__;
var REFILL = __REFILL__;
var active = 0;
var sid = Date.now();
var users = [];
var cards = [];

// 1. Create 3 users. Each has its own state.
for (let i = 0; i < 3; i++) {
    users.push({
        id: "user" + (i + 1) + "-" + sid,
        tokens: CAP,
        time: Date.now(),
        until: 0,
        allowed: 0,
        blocked: 0,
        status: "-",
        ip: "-",
        browser: "-",
        msg: "Ready",
        busy: false
    });

    var card = document.createElement("div");
    card.onclick = function () {
        active = i;
        render();
    };
    document.getElementById("users").appendChild(card);
    cards.push(card);
}

// 2. Current tokens = last known tokens + refill since last request
function tokens(u) {
    var refilled = (Date.now() - u.time) / REFILL;
    return Math.min(CAP, u.tokens + refilled);
}

// 3. Draw everything from the users array
function render() {
    var now = Date.now();

    // cards
    users.forEach(function (u, i) {
        var wait = u.until - now;
        var state = wait > 0
            ? "<span class='no'>Blocked " + (wait / 1000).toFixed(1) + " s</span>"
            : "<span class='ok'>Ready</span>";

        cards[i].className = (i == active) ? "card on" : "card";
        cards[i].innerHTML =
            "<b>User " + (i + 1) + "</b><br>" +
            "Tokens: " + Math.floor(tokens(u) + 0.001) + "/" + CAP + "<br>" +
            state;
    });

    // message and buttons for the active user
    var u = users[active];
    var wait = u.until - now;
    var disabled = u.busy || wait > 0;

    document.getElementById("msg").textContent =
        wait > 0 ? "Please wait " + (wait / 1000).toFixed(1) + " s" : u.msg;
    document.getElementById("send").disabled = disabled;
    document.getElementById("spam").disabled = disabled;

    // details panel
    document.getElementById("details").textContent = [
        "Client ID: " + u.id,
        "Tokens: " + tokens(u).toFixed(2) + " / " + CAP,
        "Allowed: " + u.allowed + "   Blocked: " + u.blocked,
        "Last HTTP status: " + u.status,
        "IP (seen by server): " + u.ip,
        "Browser (seen by server): " + u.browser
    ].join("\\n");
}

// refresh the screen every 200 ms so countdowns stay live
setInterval(render, 200);

// 4. Call the backend for one user
async function sendRequest(u) {
    var res = await fetch("/allow?clientId=" + u.id);
    var d = await res.json();

    u.time = Date.now();
    u.tokens = d.remaining;
    u.status = res.status;
    u.ip = d.ip;
    u.browser = d.userAgent;
    u.msg = d.message;

    if (d.allowed) {
        u.allowed++;
        u.until = 0;
    } else {
        u.blocked++;
        u.until = u.time + d.retryAfterMs;
    }

    render();
    return d.allowed;
}

// 5. Buttons
document.getElementById("send").onclick = function () {
    sendRequest(users[active]);
};

document.getElementById("spam").onclick = async function () {
    var u = users[active];
    u.busy = true;

    for (var n = 0; n < 10; n++) {
        var allowed = await sendRequest(u);
        if (!allowed) break;
        await new Promise(function (r) { setTimeout(r, 120); });
    }

    u.busy = false;
    render();
};

render();
</script>
</body>
</html>
""";

    // Send a response to the browser (handles its own exceptions)
    static void send(HttpExchange ex, int code, String type, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", type);
            ex.sendResponseHeaders(code, bytes.length);

            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Make text safe to put inside JSON
    static String clean(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replaceAll("\\p{Cntrl}", "");
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));

        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(CAPACITY, REFILL_MS);
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        String page = PAGE
                .replace("__CAP__", String.valueOf(CAPACITY))
                .replace("__REFILL__", String.valueOf(REFILL_MS))
                .replace("__SEC__", String.valueOf(REFILL_MS / 1000));

        // Home page
        server.createContext("/", ex -> send(ex, 200, "text/html; charset=UTF-8", page));

        // Rate limit check
        server.createContext("/allow", ex -> {
            // 1. Read clientId from the URL
            String query = ex.getRequestURI().getQuery();
            String id = "anonymous";
            if (query != null && query.startsWith("clientId=")) {
                id = query.substring(9).replaceAll("[^a-zA-Z0-9_-]", "");
                if (id.isEmpty()) id = "anonymous";
            }

            // 2. Client details (Render is behind a proxy, so check X-Forwarded-For first)
            String ip = ex.getRequestHeaders().getFirst("X-Forwarded-For");
            if (ip != null && !ip.isEmpty()) {
                ip = ip.split(",")[0].trim();
            } else {
                ip = ex.getRemoteAddress().getAddress().getHostAddress();
            }
            String browser = ex.getRequestHeaders().getFirst("User-Agent");

            // 3. Ask the token bucket
            TokenBucketRateLimiter.Result r = limiter.tryAcquire(id);

            // 4. Build the JSON response
            String message = r.allowed()
                    ? "Request allowed"
                    : "Too many requests. Wait " + r.waitMs() / 1000.0 + " seconds";

            String body = "{"
                    + "\"allowed\":" + r.allowed() + ","
                    + "\"remaining\":" + r.remaining() + ","
                    + "\"retryAfterMs\":" + r.waitMs() + ","
                    + "\"ip\":\"" + clean(ip) + "\","
                    + "\"userAgent\":\"" + clean(browser) + "\","
                    + "\"message\":\"" + message + "\""
                    + "}";

            // 5. 200 if allowed, 429 if blocked
            int code = r.allowed() ? 200 : 429;
            send(ex, code, "application/json; charset=UTF-8", body);
        });

        // Handle many users at the same time
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        System.out.println("Running on port " + port);
    }
}
