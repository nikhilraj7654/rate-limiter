```java
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

public class RateLimiterServer {

    static final int CAPACITY = 5;
    static final long REFILL_MS = 30000;

    static final String PAGE = """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Token Bucket Rate Limiter</title>
<style>
body{font-family:Arial;max-width:680px;margin:30px auto;padding:0 16px}
#users{display:flex;gap:10px}
.card{flex:1;border:2px solid #ccc;border-radius:10px;padding:10px;cursor:pointer}
.card.on{border-color:#2671c8;background:#eef5ff}
button{padding:10px;margin:10px 5px 10px 0;cursor:pointer}
button:disabled{opacity:.5}
#data,#msg{padding:12px;background:#f1f5f9;border-radius:8px;overflow-wrap:anywhere}
</style>
</head>
<body>
<h2>Token Bucket Rate Limiter</h2>
<p>Each client has 5 tokens. One token refills every 30 seconds.</p>
<div id="users"></div>
<p>Active: <b id="who"></b></p>
<button id="send">Send Request</button>
<button id="spam">Spam 10</button>
<div id="msg">Ready</div>
<div id="data"></div>

<script>
const CAP=__CAP__, users=[], cards=[];
let active=0, sid=Date.now().toString(36);
const $=id=>document.getElementById(id);

for(let i=0;i<3;i++){
 let u={id:"user"+(i+1)+"-"+sid,ok:0,no:0,
 tokens:CAP,stamp:Date.now(),until:0,busy:false};
 users.push(u);

 let c=document.createElement("div");
 c.className="card";
 c.innerHTML="<b>User "+(i+1)+"</b><p></p><small></small>";
 c.onclick=()=>{active=i;tick()};
 $("users").appendChild(c);
 cards.push(c);
}

function cur(u){
 return Math.min(CAP,u.tokens+(Date.now()-u.stamp)/30000);
}

function tick(){
 let now=Date.now(),u=users[active],left=u.until-now;

 users.forEach((x,i)=>{
  cards[i].className="card"+(i===active?" on":"");
  cards[i].querySelector("p").textContent=
   "Tokens: "+cur(x).toFixed(1)+"/"+CAP;
  cards[i].querySelector("small").textContent=
   "Allowed: "+x.ok+" | Blocked: "+x.no;
 });

 $("who").textContent="User "+(active+1);
 $("data").innerHTML="<h3>Client Details</h3>"+
  "<p>Client ID: "+u.id+"</p>"+
  "<p>Total Requests: "+(u.ok+u.no)+"</p>"+
  "<p>Allowed: "+u.ok+"</p>"+
  "<p>Blocked: "+u.no+"</p>"+
  "<p>Tokens: "+cur(u).toFixed(1)+" / "+CAP+"</p>"+
  "<p>Status: "+(left>0?"Blocked":u.busy?"Processing":"Ready")+"</p>";

 $("msg").textContent=left>0?
  "Rate limited. Wait "+(left/1000).toFixed(1)+" seconds":"Ready";
 $("send").disabled=u.busy||left>0;
 $("spam").disabled=u.busy||left>0;
}

async function hit(u){
 try{
  let r=await fetch("/allow?clientId="+encodeURIComponent(u.id));
  let d=await r.json();
  u.stamp=Date.now();
  u.tokens=d.remaining;

  if(d.allowed){u.ok++;u.until=0}
  else{u.no++;u.until=Date.now()+d.retryAfterMs}

  tick();
  return d.allowed;
 }catch(e){
  $("msg").textContent="Request failed. Check server.";
  return false;
 }
}

$("send").onclick=()=>hit(users[active]);

$("spam").onclick=async()=>{
 let u=users[active];
 if(u.busy||u.until>Date.now())return;
 u.busy=true;tick();

 try{
  for(let i=0;i<10;i++){
   if(u.until>Date.now())break;
   if(!await hit(u))break;
   await new Promise(r=>setTimeout(r,120));
  }
 }finally{
  u.busy=false;tick();
 }
};

setInterval(tick,200);
tick();
</script>
</body>
</html>
""";

    static void send(HttpExchange ex, int code,
                     String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(
                System.getenv().getOrDefault("PORT", "8080"));

        TokenBucketRateLimiter limiter =
                new TokenBucketRateLimiter(CAPACITY, REFILL_MS);

        HttpServer server = HttpServer.create(
                new InetSocketAddress("0.0.0.0", port), 0);

        String page = PAGE.replace(
                "__CAP__", String.valueOf(CAPACITY));

        server.createContext("/", ex -> {
            if (!ex.getRequestMethod().equals("GET")) {
                send(ex, 405, "text/plain", "Method not allowed");
                return;
            }
            send(ex, 200, "text/html; charset=UTF-8", page);
        });

        server.createContext("/allow", ex -> {
            if (!ex.getRequestMethod().equals("GET")) {
                send(ex, 405, "application/json", "{}");
                return;
            }

            String q = ex.getRequestURI().getRawQuery();
            String id = "anonymous";

            if (q != null && q.startsWith("clientId=")) {
                id = URLDecoder.decode(q.substring(9),
                        StandardCharsets.UTF_8)
                        .replaceAll("[^a-zA-Z0-9_-]", "");
            }

            if (id.isEmpty()) id = "anonymous";
            if (id.length() > 40) id = id.substring(0, 40);

            TokenBucketRateLimiter.Result r = limiter.tryAcquire(id);

            String body = "{\"clientId\":\"" + id
                    + "\",\"allowed\":" + r.allowed()
                    + ",\"remaining\":" + r.remaining()
                    + ",\"retryAfterMs\":" + r.waitMs() + "}";

            if (!r.allowed()) {
                ex.getResponseHeaders().set("Retry-After",
                        String.valueOf((r.waitMs() + 999) / 1000));
            }

            send(ex, r.allowed() ? 200 : 429,
                    "application/json; charset=UTF-8", body);
        });

        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("Server running on port " + port);
    }
}
```
