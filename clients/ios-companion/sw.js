"use strict";
const CACHE="raahat-companion-v3";
const ASSETS=["./","./index.html","./app.js","./manifest.webmanifest","./icon.svg"];
const NETWORK_FIRST_PATHS=new Set(["/","/index.html","/app.js","/manifest.webmanifest"]);

self.addEventListener("install",event=>{
 event.waitUntil(caches.open(CACHE).then(cache=>cache.addAll(ASSETS)));
 self.skipWaiting();
});
self.addEventListener("activate",event=>{
 event.waitUntil((async()=>{
  const keys=await caches.keys();
  await Promise.all(keys.filter(key=>key!==CACHE).map(key=>caches.delete(key)));
  await self.clients.claim();
 })());
});
self.addEventListener("fetch",event=>{
 const request=event.request;
 if(request.method!=="GET")return;
 const url=new URL(request.url);
 if(url.origin!==location.origin)return; // Never intercept the cross-origin API request.

 const networkFirst=request.mode==="navigate"||NETWORK_FIRST_PATHS.has(url.pathname);
 if(networkFirst){
  event.respondWith((async()=>{
   try{
    const response=await fetch(request,{cache:"no-store"});
    if(response.ok){
     const cache=await caches.open(CACHE);
     await cache.put(request,response.clone());
    }
    return response;
   }catch(error){
    const cached=await caches.match(request);
    if(cached)return cached;
    throw error;
   }
  })());
  return;
 }
 event.respondWith(caches.match(request).then(cached=>cached||fetch(request).then(async response=>{
  if(response.ok){
   const cache=await caches.open(CACHE);
   await cache.put(request,response.clone());
  }
  return response;
 })));
});
