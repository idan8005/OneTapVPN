"use strict";
const $=id=>document.getElementById(id);
let profileFile=null;
function setStatus(id,message,kind=""){const el=$(id);el.textContent=message;el.className="status "+kind;}
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
