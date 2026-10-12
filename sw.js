// Home Poker: riceve le notifiche e apre l'app sul punto giusto
self.addEventListener('install',()=>self.skipWaiting());
self.addEventListener('activate',e=>e.waitUntil(self.clients.claim()));
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
