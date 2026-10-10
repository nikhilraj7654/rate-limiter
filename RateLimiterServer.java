import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.concurrent.ConcurrentHashMap;

public class RateLimiterServer {

    static final int CAPACITY = 5;
    static final long REFILL_MS = 30000;

    static class Client {
        int allowed, blocked;
        long first = System.currentTimeMillis(), last;
        String ip = "-", browser = "-", remaining = "" + CAPACITY;
        LinkedList<String> history = new LinkedList<>();
    }

    static final ConcurrentHashMap<String, Client> clients = new ConcurrentHashMap<>();

    static final String PAGE = String.join("\n",
        "<!DOCTYPE html><html><head><meta charset='UTF-8'>",
        "<meta name='viewport' content='width=device-width,initial-scale=1'><title>Rate Limiter</title>",
        "<style>",
        "body{font-family:Arial;max-width:650px;margin:30px auto;padding:0 16px}",
        ".group{border:1px solid #ddd;border-radius:12px;padding:12px;margin-bottom:20px}",
        ".row{display:flex;gap:10px;margin-bottom:12px}",
        ".card{flex:1;border:2px solid #ccc;border-radius:10px;padding:10px;cursor:pointer}",
        ".on{border-color:#2b6cb0;background:#f0f6ff}",
        ".ok{color:#11743a}.no{color:#b42318}",
        "button{padding:10px 12px;margin:2px}",
        "button:disabled{opacity:.5}",
        "pre{background:#f5f5f5;padding:10px;border-radius:8px;white-space:pre-wrap;word-break:break-all}",
        "</style></head><body>",
        "<h2>Token Bucket Rate Limiter</h2>",
        "<p>Each user and client has __CAP__ tokens. 1 token comes back every __SEC__ seconds. Select one, then click a button.</p>",
        "<div id='app'></div>",
        "<script>",
        "var CAP=__CAP__,RF=__REFILL__,sid=Date.now(),groups=[];",
        "function clock(t){return t?new Date(t).toLocaleTimeString():'-';}",
        "function sleep(ms){return new Promise(function(r){setTimeout(r,ms);});}",
        "function tokens(u){return Math.min(CAP,u.tokens+(Date.now()-u.time)/RF);}",
        "function createGroup(title,label,prefix){",
        "  var g={label:label,active:0,items:[],cards:[]};",
        "  var box=document.createElement('div');box.className='group';",
        "  box.innerHTML='<h3>'+title+'</h3><div class=row></div>'",
        "   +'<button class=b1>Send Request</button><button class=b2>Spam Allow 10</button>'",
        "   +'<button class=b3>Get Details</button><button class=b4>Spam Details 10</button>'",
        "   +'<h4 class=msg></h4><pre class=det></pre>';",
        "  document.getElementById('app').appendChild(box);",
        "  g.row=box.querySelector('.row');g.msg=box.querySelector('.msg');g.det=box.querySelector('.det');",
        "  g.b1=box.querySelector('.b1');g.b2=box.querySelector('.b2');",
        "  for(let i=0;i<3;i++){",
        "    g.items.push({id:prefix+(i+1)+'-'+sid,tokens:CAP,time:Date.now(),until:0,status:'-',msg:'Ready',ok:0,no:0,run:0,mode:'',server:null});",
        "    var c=document.createElement('div');",
        "    c.onclick=function(){g.active=i;render();};",
        "    g.row.appendChild(c);g.cards.push(c);",
        "  }",
        "  function cur(){return g.items[g.active];}",
        "  g.b1.onclick=function(){var u=cur();u.run++;u.mode='';callAllow(u);};",
        "  box.querySelector('.b3').onclick=function(){var u=cur();u.run++;u.mode='';callDetails(u);};",
        "  g.b2.onclick=function(){spam(cur(),'allow');};",
        "  box.querySelector('.b4').onclick=function(){spam(cur(),'details');};",
        "  groups.push(g);",
        "}",
        "async function spam(u,kind){",
        "  var mine=++u.run;u.mode=kind;",
        "  for(var n=0;n<10;n++){",
        "    if(mine!==u.run)return;",
        "    if(kind=='allow'){if(!(await callAllow(u)))break;}",
        "    else await callDetails(u);",
        "    await sleep(120);",
        "  }",
        "  if(mine===u.run)u.mode='';",
        "  render();",
        "}",
        "function renderGroup(g){",
        "  var now=Date.now();",
        "  g.items.forEach(function(u,i){",
        "    var w=u.until-now;",
        "    g.cards[i].className=(i==g.active)?'card on':'card';",
        "    g.cards[i].innerHTML='<b>'+g.label+' '+(i+1)+'</b><br>Tokens: '+Math.floor(tokens(u)+0.001)+'/'+CAP+'<br>'",
        "      +(w>0?'<span class=no>Blocked '+(w/1000).toFixed(1)+' s</span>':'
