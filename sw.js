// Home Poker: si apre subito (anche con poca rete) e riceve le notifiche.
// L'app e le librerie restano sul telefono; a ogni apertura si scarica in silenzio
// la versione nuova, che l'app propone con "Aggiorna".
const CACHE='hp-v2';
const SHELL=['./','index.html','manifest.webmanifest','icon.png','icon.svg'];
self.addEventListener('install',e=>{self.skipWaiting();e.waitUntil(caches.open(CACHE).then(c=>c.addAll(SHELL).catch(()=>{})))});
self.addEventListener('activate',e=>e.waitUntil((async()=>{
  for(const k of await caches.keys())if(k!==CACHE)await caches.delete(k);
  await self.clients.claim();
})()));
const isLib=u=>u.hostname==='cdn.jsdelivr.net'||u.hostname==='fonts.googleapis.com'||u.hostname==='fonts.gstatic.com';
self.addEventListener('fetch',e=>{
  const r=e.request;if(r.method!=='GET')return;
  const u=new URL(r.url);
  // librerie e caratteri: versione fissa, si prendono dal telefono
  if(isLib(u)){e.respondWith(caches.open(CACHE).then(async c=>{const hit=await c.match(r);if(hit)return hit;const res=await fetch(r);if(res.ok||res.type==='opaque')c.put(r,res.clone());return res}));return}
  if(u.origin!==location.origin)return;
  // controllo aggiornamenti dell'app: sempre dalla rete
  if(u.searchParams.has('vcheck'))return;
  const page=r.mode==='navigate'||/\/(index\.html)?$/.test(u.pathname);
  if(page){
    // nuova versione scelta con "Aggiorna": dalla rete, e diventa quella salvata
    if(u.searchParams.has('v')){e.respondWith(fetch(r).then(res=>{if(res.ok)caches.open(CACHE).then(c=>c.put('index.html',res.clone()));return res}).catch(()=>caches.match('index.html')));return}
    // apertura normale: subito dal telefono, intanto si aggiorna la copia
    e.respondWith(caches.open(CACHE).then(async c=>{
      const hit=await c.match('index.html');
      const net=fetch(r).then(res=>{if(res.ok)c.put('index.html',res.clone());return res});
      if(hit){e.waitUntil(net.catch(()=>{}));return hit}
      return net;
    }));
    return;
  }
  if(/\.(png|svg|webmanifest)$/.test(u.pathname))e.respondWith(caches.match(r).then(hit=>hit||fetch(r)));
});
self.addEventListener('push',e=>{
  let d={};try{d=e.data?e.data.json():{}}catch(x){d={title:'Home Poker',body:e.data&&e.data.text()}}
  e.waitUntil(self.registration.showNotification(d.title||'Home Poker',{body:d.body||'',icon:'icon.png',badge:'icon.png',tag:d.tag||undefined,renotify:!!d.tag,data:{url:d.url||'./'}}));
});
self.addEventListener('notificationclick',e=>{
  e.notification.close();
  const url=new URL((e.notification.data&&e.notification.data.url)||'./',self.registration.scope).href;
  e.waitUntil(self.clients.matchAll({type:'window',includeUncontrolled:true}).then(cs=>{
    for(const c of cs){if(c.url.startsWith(self.registration.scope)){c.postMessage({url});return c.focus()}}
    return self.clients.openWindow(url);
  }));
});
