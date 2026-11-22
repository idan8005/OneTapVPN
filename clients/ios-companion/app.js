"use strict";
const $=id=>document.getElementById(id);
let profileFile=null;

function setStatus(id,message,kind=""){const el=$(id);el.textContent=message;el.className="status "+kind;}
function safeText(value,max=160){return typeof value==="string"?value.trim().slice(0,max):"";}

function normalizeApiBase(raw){
 const value=String(raw||"").trim();
 if(!value)throw new Error("ابتدا نشانی سرویس API راحت وی‌پی‌ان را وارد کن.");
 let url;
 try{url=new URL(value);}catch{throw new Error("نشانی API درست نیست. نمونه: https://api.example.com");}
 const localHTTP=url.protocol==="http:"&&["localhost","127.0.0.1","[::1]"].includes(url.hostname);
 if(url.protocol!=="https:"&&!localHTTP)throw new Error("برای امنیت، نشانی API باید با HTTPS شروع شود.");
 if(url.username||url.password||url.search||url.hash||url.pathname!=="/")throw new Error("فقط نشانی اصلی سرویس را وارد کن؛ بدون مسیر، نام کاربری یا پارامتر.");
 return url.origin;
}

function serverIsDisplayable(server){
 if(!server||server.enabled!==true||server.healthy!==true)return false;
 if(typeof server.id!=="string"||!server.id.trim())return false;
 // VPN Gate is shown only after the backend's explicit TCP reachability probe.
 if(server.source==="vpn-gate"&&server.verification_level!=="tcp-reachable")return false;
 return true;
}

function addText(parent,tag,className,value){
 const element=document.createElement(tag);
 if(className)element.className=className;
 element.textContent=value;
 parent.appendChild(element);
 return element;
}

function renderServers(servers){
 const list=$("serverList");
 list.replaceChildren();
 const visible=servers.filter(serverIsDisplayable);
 if(visible.length===0){
  addText(list,"p","muted","در حال حاضر سرور فعال و تأییدشده‌ای در کاتالوگ وجود ندارد.");
  return 0;
 }
 for(const server of visible){
  const card=document.createElement("article");
  card.className="server-card";
  const title=safeText(server.name,100)||"سرور بدون نام";
  addText(card,"h3","",title);
  const region=[safeText(server.region,80),safeText(server.country_code,2)].filter(Boolean).join(" · ");
  if(region)addText(card,"p","server-meta",region);
  const tier=server.tier==="premium"?"پریمیوم":"رایگان";
  addText(card,"span","server-badge",tier);
  const host=safeText(server.endpoint_host,253);
  const port=Number(server.endpoint_port);
  if(host)addText(card,"p","server-meta",Number.isInteger(port)&&port>0?("نشانی: "+host+":"+port):("نشانی: "+host));
  const latency=server.source==="vpn-gate"?Number(server.probe_latency_ms):Number(server.latency_ms);
  if(Number.isFinite(latency)&&latency>0){
   addText(card,"p","server-meta",server.source==="vpn-gate"
    ?("زمان پاسخ TCP: "+latency.toLocaleString("fa-IR")+" میلی‌ثانیه")
    :("زمان پاسخ ثبت‌شده: "+latency.toLocaleString("fa-IR")+" میلی‌ثانیه"));
  }
  if(server.source==="vpn-gate"&&server.verification_level==="tcp-reachable"){
   addText(card,"p","server-note","پورت TCP پاسخ داده است؛ این بررسی، اتصال کامل OpenVPN یا کیفیت اینترنت را تأیید نمی‌کند.");
  }else{
   addText(card,"p","server-note","این وضعیت از کاتالوگ سرویس دریافت شده و هنوز در این گوشی آزمایش اتصال انجام نشده است.");
  }
  const checked=safeText(server.last_checked_at,64);
  if(checked){
   const date=new Date(checked);
   if(!Number.isNaN(date.getTime()))addText(card,"p","server-time","آخرین بررسی: "+date.toLocaleString("fa-IR"));
  }
  list.appendChild(card);
 }
 return visible.length;
}

async function loadServerCatalogue(){
 const button=$("loadServers");
 const list=$("serverList");
 button.disabled=true;
 list.replaceChildren();
 setStatus("catalogueStatus","در حال دریافت فهرست سرورها…");
 let timer;
 try{
  const base=normalizeApiBase($("apiBase").value);
  const controller=new AbortController();
  timer=setTimeout(()=>controller.abort(),12000);
  const response=await fetch(base+"/api/v1/servers",{
   method:"GET",
   headers:{Accept:"application/json"},
   cache:"no-store",
   credentials:"omit",
   redirect:"error",
   signal:controller.signal
  });
  if(!response.ok)throw new Error("سرویس API با خطای HTTP "+response.status+" پاسخ داد.");
  const payload=await response.json();
  if(!payload||!Array.isArray(payload.data)||!Number.isInteger(payload.count))throw new Error("ساختار پاسخ API معتبر نیست.");
  const count=renderServers(payload.data);
  setStatus("catalogueStatus","دریافت موفق بود · "+count.toLocaleString("fa-IR")+" سرور قابل نمایش از "+payload.count.toLocaleString("fa-IR")+" مورد.",count?"success":"warning");
 }catch(error){
  list.replaceChildren();
  const message=error.name==="AbortError"?"پاسخ API بیش از ۱۲ ثانیه طول کشید.":(error.message||"ارتباط با API برقرار نشد.");
  setStatus("catalogueStatus",message+" بررسی کن نشانی درست باشد، API در دسترس باشد و CORS آن برای وب‌سایت راحت مجاز شده باشد.","warning");
 }finally{
  if(timer)clearTimeout(timer);
  button.disabled=false;
 }
}

$("loadServers").addEventListener("click",loadServerCatalogue);

function validateOvpn(text){
 const trimmed=text.trim();
 if(!trimmed.startsWith("client")&&!/^\s*client\s*$/m.test(trimmed)) throw new Error("پروفایل به نظر فایل OpenVPN معتبر نیست؛ دستور client پیدا نشد.");
 if(!/^\s*remote\s+\S+\s+\d+/m.test(trimmed)) throw new Error("نشانی و پورت سرور (remote) در فایل پیدا نشد.");
 const external=[...trimmed.matchAll(/^\s*(ca|cert|key|tls-auth|tls-crypt|askpass|auth-user-pass)\s+([^\s#][^\r\n]*)/gm)]
 .filter(m=>!m[2].trim().startsWith("<")&&!["stdin","[/dev/fd/0]"].includes(m[2].trim()));
 if(external.length) throw new Error("این پروفایل به فایل جانبی نیاز دارد ("+external.map(x=>x[1]).join(", ")+"). این همراه وب فقط پروفایل‌های خودکفا با گواهی/کلید داخل فایل را می‌پذیرد.");
 if(!/^\s*proto\s+(udp|tcp)(4|6)?\s*$/mi.test(trimmed)) throw new Error("نوع پروتکل TCP/UDP در فایل مشخص نشده است.");
 return {remote:(trimmed.match(/^\s*remote\s+(\S+)\s+(\d+)/m)||[]).slice(1).join(":"),proto:(trimmed.match(/^\s*proto\s+(\S+)/mi)||[])[1]||"نامشخص"};
}
$("ovpn").addEventListener("change",async e=>{
 profileFile=null;$("shareOvpn").disabled=$("saveOvpn").disabled=true;
 const f=e.target.files?.[0];if(!f)return;
 if(f.size>1024*1024){setStatus("ovpnStatus","حجم فایل بیش از ۱ مگابایت است؛ فایل رد شد.","warning");return;}
 try{const t=await f.text();const info=validateOvpn(t);profileFile=new File([t],f.name,{type:"application/x-openvpn-profile"});$("shareOvpn").disabled=$("saveOvpn").disabled=false;setStatus("ovpnStatus","پروفایل از نظر ساختار پایه قابل قبول است.\nسرور: "+info.remote+"\nپروتکل: "+info.proto+"\n\nاین بررسی اتصال‌پذیری یا امنیت سرور را تأیید نمی‌کند.","success");}
 catch(err){setStatus("ovpnStatus",err.message,"warning");}
});
async function shareProfile(){
 if(!profileFile)return;
 try{if(navigator.share&&navigator.canShare?.({files:[profileFile]})){await navigator.share({files:[profileFile],title:"Raahat VPN OpenVPN profile"});}
 else downloadProfile();}
 catch(e){if(e.name!=="AbortError")setStatus("ovpnStatus","اشتراک‌گذاری انجام نشد. از دکمه ذخیره فایل استفاده کن.","warning");}
}
function downloadProfile(){if(!profileFile)return;const url=URL.createObjectURL(profileFile);const a=document.createElement("a");a.href=url;a.download=profileFile.name;a.click();setTimeout(()=>URL.revokeObjectURL(url),1500);}
$("shareOvpn").addEventListener("click",shareProfile);$("saveOvpn").addEventListener("click",downloadProfile);
function getVless(){const s=$("vless").value.trim();if(!/^vless:\/\/[0-9a-f-]{36}@[^\s#]+/i.test(s))throw new Error("لینک VLESS معتبر وارد کن.");return s;}
$("openHiddify").addEventListener("click",()=>{try{const s=getVless();setStatus("vlessStatus","لینک آماده است. اگر Hiddify نصب و scheme آن فعال باشد، برنامه باز می‌شود.","success");location.href="hiddify://import/"+encodeURIComponent(s)+"#Raahat";}catch(e){setStatus("vlessStatus",e.message,"warning");}});
$("copyVless").addEventListener("click",async()=>{try{const s=getVless();await navigator.clipboard.writeText(s);setStatus("vlessStatus","لینک کپی شد. آن را در Hiddify وارد کن.","success");}catch(e){setStatus("vlessStatus",e.message||"کپی انجام نشد.","warning");}});
if("serviceWorker" in navigator&&location.protocol.startsWith("http"))window.addEventListener("load",()=>navigator.serviceWorker.register("./sw.js").catch(()=>{}));
