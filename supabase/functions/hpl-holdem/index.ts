// @ts-nocheck
// Supabase Edge Function "hpl-holdem": il mazziere dei tavoli virtuali di Home Poker.
// Mescola e distribuisce le carte, controlla turni e puntate. Le carte coperte
// restano qui sul server: ogni giocatore riceve solo le sue.
// NB: il motore HE qui sotto è la stessa copia che sta in index.html: se cambi uno, aggiorna l'altro.
import { createClient } from "npm:@supabase/supabase-js@2.58.0";
import { createRemoteJWKSet, jwtVerify } from "npm:jose@5.9.6";

const HE=(()=>{
const RANKS='23456789TJQKA',SUITS='shdc',NS=9,NEXT_DELAY=4000,HIST=25;
const LONELY_MS=150000,AWAY_MS=420000;
const LV=[1,1.5,2,3,4,5,6,8,10,12,15,20,25,30,40,50,60,80,100,150,200];
const rv=c=>RANKS.indexOf(c[0])+2;
const RN={11:'J',12:'Q',13:'K',14:'A'};const rn=v=>RN[v]||String(v);
function rnd(n){const a=new Uint32Array(1),lim=Math.floor(0x100000000/n)*n;let x;do{crypto.getRandomValues(a);x=a[0]}while(x>=lim);return x%n}
function newDeck(){const d=[];for(const r of RANKS)for(const s of SUITS)d.push(r+s);for(let i=d.length-1;i>0;i--){const j=rnd(i+1);const t=d[i];d[i]=d[j];d[j]=t}return d}

/* ---- valutazione: punteggio confrontabile + nome in italiano ---- */
function straightHigh(vs){const u=[...new Set(vs)].sort((a,b)=>b-a);if(u[0]===14)u.push(1);let run=1;for(let i=1;i<u.length;i++){if(u[i]===u[i-1]-1){if(++run>=5)return u[i]+4}else run=1}return 0}
function evalHand(cards){
  cards=(cards||[]).filter(Boolean);if(!cards.length)return null;
  const vals=cards.map(rv),cnt={},bySuit={};
  vals.forEach(v=>cnt[v]=(cnt[v]||0)+1);
  cards.forEach(c=>(bySuit[c[1]]=bySuit[c[1]]||[]).push(rv(c)));
  let fl=null;for(const s in bySuit)if(bySuit[s].length>=5)fl=s;
  if(fl){const sh=straightHigh(bySuit[fl]);if(sh)return {s:[8,sh],n:sh===14?'Scala reale':'Scala colore al '+rn(sh)}}
  const g=Object.keys(cnt).map(v=>[+v,cnt[v]]).sort((a,b)=>b[1]-a[1]||b[0]-a[0]);
  const desc=vals.slice().sort((a,b)=>b-a);
  if(g[0][1]===4){const q=g[0][0];return {s:[7,q,desc.find(v=>v!==q)||0],n:'Poker di '+rn(q)}}
  if(g[0][1]===3&&g[1]&&g[1][1]>=2)return {s:[6,g[0][0],g[1][0]],n:'Full di '+rn(g[0][0])+' e '+rn(g[1][0])};
  if(fl){const f=bySuit[fl].sort((a,b)=>b-a).slice(0,5);return {s:[5,...f],n:'Colore al '+rn(f[0])}}
  const st=straightHigh(vals);if(st)return {s:[4,st],n:'Scala al '+rn(st)};
  if(g[0][1]===3){const t=g[0][0];return {s:[3,t,...desc.filter(v=>v!==t).slice(0,2)],n:'Tris di '+rn(t)}}
  if(g[0][1]===2&&g[1]&&g[1][1]===2){const a=g[0][0],b=g[1][0];return {s:[2,a,b,desc.find(v=>v!==a&&v!==b)||0],n:'Doppia coppia '+rn(a)+' e '+rn(b)}}
  if(g[0][1]===2){const p=g[0][0];return {s:[1,p,...desc.filter(v=>v!==p).slice(0,3)],n:'Coppia di '+rn(p)}}
  return {s:[0,...desc.slice(0,5)],n:'Carta alta '+rn(desc[0])};
}
/* Omaha: si usano per forza 2 carte proprie e 3 del tavolo */
function bestOmaha(hole,board){
  hole=(hole||[]).filter(Boolean);board=(board||[]).filter(Boolean);let best=null;
  if(board.length<3)return evalHand(hole.slice(0,2));
  for(let a=0;a<hole.length;a++)for(let b=a+1;b<hole.length;b++)for(let c=0;c<board.length;c++)for(let d=c+1;d<board.length;d++)for(let e=d+1;e<board.length;e++){
    const five=[hole[a],hole[b],board[c],board[d],board[e]],ev=evalHand(five);if(!best||cmp(ev.s,best.s)>0)best=Object.assign(ev,{cards:five})}
  return best;
}
/* limite al piatto: si rilancia al massimo della dimensione del piatto */
function potCap(st,i){
  if(st.cfg.limit!=='pot')return Infinity;
  const h=st.hand,P=h.p[i],pot=Object.values(h.p).reduce((a,x)=>a+x.contrib,0),toCall=Math.max(0,h.curBet-P.bet);
  return Math.max(h.curBet+pot+toCall,h.curBet+(h.bbAmt||st.cfg.bb));
}
function cmp(a,b){for(let i=0;i<Math.max(a.length,b.length);i++){const d=(a[i]||0)-(b[i]||0);if(d)return d}return 0}
/* le 5 carte che formano il punto (per evidenziarle) */
function best5(cards){
  cards=(cards||[]).filter(Boolean);if(cards.length<=5){const e=evalHand(cards);return e&&Object.assign(e,{cards:cards.slice()})}
  let best=null;const n=cards.length;
  for(let a=0;a<n;a++)for(let b=a+1;b<n;b++){const five=cards.filter((_,k)=>k!==a&&k!==b);if(five.length!==5)continue;const e=evalHand(five);if(!best||cmp(e.s,best.s)>0){best=Object.assign(e,{cards:five})}}
  // con 6 carte il doppio ciclo ne toglie due: rifacciamo togliendone una
  if(n===6){best=null;for(let a=0;a<n;a++){const five=cards.filter((_,k)=>k!==a);const e=evalHand(five);if(!best||cmp(e.s,best.s)>0)best=Object.assign(e,{cards:five})}}
  // il nome viene dalla mano completa (più preciso con i kicker)
  const full=evalHand(cards);if(best&&full)best.n=full.n;
  return best;
}

/* ---- stato ---- */
function initState(room){return {room:room||null,closed:false,v:0,busted:{},cfg:{sb:5,bb:10,stack:1000,turnSec:30,mode:'cash',lvlMin:8,maxRebuys:2},seats:Array(NS).fill(null),running:false,handNo:0,dealer:-1,hand:null,hist:[],sng:null}}
const E=c=>{const e=new Error(c);e.code=c;throw e};
const seatOf=(st,acct)=>st.seats.findIndex(s=>s&&s.acct===acct);
const nextIdx=(from,ok)=>{for(let k=1;k<=NS;k++){const i=((from+k)%NS+NS)%NS;if(ok(i))return i}return -1};
const handLive=st=>!!(st.hand&&st.hand.stage!=='done');
const inLive=(st,i)=>handLive(st)&&st.hand.p[i]&&!st.hand.p[i].folded;
const sngOn=st=>!!(st.cfg.mode==='sng'&&st.sng&&!st.sng.done);
function blinds(st,now){
  if(!sngOn(st))return {sb:st.cfg.sb,bb:st.cfg.bb,level:0,next:null};
  const ms=Math.max(1,st.cfg.lvlMin||8)*60000,l=Math.min(LV.length-1,Math.floor((now-st.sng.startedAt)/ms));
  return {sb:Math.max(1,Math.round(st.cfg.sb*LV[l])),bb:Math.max(2,Math.round(st.cfg.bb*LV[l])),level:l,next:l<LV.length-1?st.sng.startedAt+(l+1)*ms:null,nsb:l<LV.length-1?Math.round(st.cfg.sb*LV[l+1]):null,nbb:l<LV.length-1?Math.round(st.cfg.bb*LV[l+1]):null};
}

function sit(st,who,seat){
  if(seatOf(st,who.acct)>=0)E('already_seated');
  if(sngOn(st))E('sng_running');if(st.closed)E('room_closed');
  const mx=maxSeats(st);
  if(seat==null||seat<0)seat=st.seats.findIndex((s,i)=>!s&&i<mx);
  if(seat<0||seat>=mx)E('full');if(st.seats[seat])E('seat_taken');
  const used=(st.busted||{})[who.acct]||0,mr=rebuyMax(st);
  if(used>mr)E('no_rebuys');
  st.seats[seat]={acct:who.acct,pid:who.pid||null,name:String(who.name||'?').slice(0,24),stack:st.cfg.stack,away:false,rebuys:used};
  st.lonelyAt=null;
  return true;
}
function leave(st,priv,who,now){
  const i=seatOf(st,who.acct);if(i<0)return false;
  if(inLive(st,i)){
    const h=st.hand;
    if(h.toAct===i)apply(st,priv,i,{type:'fold'},now);
    else{h.p[i].folded=true;h.p[i].la='Fold';h.last=h.p[i].name+': fold';progress(st,priv,now,null)}
  }
  const s=st.seats[i];
  if(sngOn(st)&&!s.out){const alive=st.seats.filter(x=>x&&!x.out&&(x.stack>0||x===s)).length;st.sng.out.push({name:s.name,acct:s.acct,pos:alive});st.seats[i]=null;sngCheckEnd(st,now)}
  else st.seats[i]=null;
  if(!st.seats.some(Boolean))st.running=false;
  return true;
}
function rebuy(st,who){
  const i=seatOf(st,who.acct);if(i<0)E('not_seated');const s=st.seats[i];
  if(sngOn(st))E('sng_running');
  if(s.stack>0)E('has_chips');if(inLive(st,i))E('in_hand');
  s.stack=st.cfg.stack;s.rebuys=(s.rebuys||0)+1;s.out=false;return true;
}
function setAway(st,who,away){const i=seatOf(st,who.acct);if(i<0)E('not_seated');st.seats[i].away=!!away;if(!away)st.seats[i].to=0;return true}
function config(st,c){
  if(handLive(st))E('hand_running');if(sngOn(st))E('sng_running');
  const n=(x,lo,hi)=>{x=Math.round(+x);if(!(x>=lo&&x<=hi))E('bad_config');return x};
  const sb=n(c.sb,1,100000),bb=n(c.bb,2,200000),stack=n(c.stack,10,10000000),t=n(c.turnSec||st.cfg.turnSec,10,120);
  const mode=c.mode==='sng'?'sng':'cash',lvlMin=n(c.lvlMin||st.cfg.lvlMin||8,2,60);
  const mr=c.maxRebuys==null?(st.cfg.maxRebuys==null?2:st.cfg.maxRebuys):n(c.maxRebuys,0,99);
  if(bb<sb||stack<bb*2)E('bad_config');
  const gm=(c.game||st.cfg.game)==='omaha'?'omaha':null,lm=gm==='omaha'||(c.limit||st.cfg.limit)==='pot'?'pot':null;
  st.cfg={sb,bb,stack,turnSec:t,mode,lvlMin,maxRebuys:mr};if(gm)st.cfg.game=gm;if(lm)st.cfg.limit=lm;
  if(mode==='cash')st.sng=null;
  /* passare a torneo richiede un avvio esplicito: niente mani a metà strada */
  if(mode==='sng'&&!st.sng){st.running=false;st.hand=null}
  return true;
}
function start(st,priv,now){
  if(st.cfg.mode==='sng'&&!sngOn(st)){
    const sat=st.seats.filter(Boolean);if(sat.length<2)E('need_players');
    sat.forEach(s=>{s.stack=st.cfg.stack;s.out=false;s.rebuys=0;s.away=false});
    st.sng={startedAt:now,out:[],done:false,ranking:null,players:sat.length};st.dealer=-1;st.hand=null;
  }
  st.running=true;tick(st,priv,now);return true;
}
const rebuyMax=st=>{const m=st.cfg.maxRebuys;return m==null?2:m};
const rebuysLeft=(st,acct)=>{const m=rebuyMax(st);return m>=99?99:Math.max(0,m-Math.max(0,(((st.busted||{})[acct])||0)-1))};
const maxSeats=st=>Math.min(NS,Math.max(2,(st.room&&st.room.maxSeats)||NS));
const eligible=st=>st.seats.map((s,i)=>s&&s.stack>0&&!s.out&&(sngOn(st)||!s.away)?i:-1).filter(i=>i>=0);

function newHand(st,priv,now){
  const el=eligible(st);if(el.length<2)return false;
  const ok=i=>el.includes(i);
  const B=blinds(st,now);
  st.handNo++;st.dealer=nextIdx(st.dealer,ok);if(st.room)st.room.started=true;
  const hu=el.length===2,sb=hu?st.dealer:nextIdx(st.dealer,ok),bb=nextIdx(sb,ok);
  const deck=newDeck(),holes={},p={};
  el.forEach(i=>{const s=st.seats[i];p[i]={name:s.name,acct:s.acct,bet:0,contrib:0,folded:false,allin:false,acted:false,la:null,start:s.stack};holes[s.acct]=Array.from({length:st.cfg.game==='omaha'?4:2},()=>deck.pop())});
  st.hand={no:st.handNo,dealer:st.dealer,sb,bb,sbAmt:B.sb,bbAmt:B.bb,level:B.level,stage:'preflop',board:[],p,curBet:0,minRaise:B.bb,toAct:null,deadline:null,last:null,results:null,reveal:null,endedAt:null,startedAt:now};
  priv.hand=st.handNo;priv.deck=deck;priv.holes=holes;
  put(st,sb,B.sb);put(st,bb,B.bb);
  const h=st.hand;h.curBet=Math.max(h.p[sb].bet,h.p[bb].bet);
  h.last='Nuova mano';
  progress(st,priv,now,bb);
  return true;
}
function put(st,i,amt){const s=st.seats[i],P=st.hand.p[i],a=Math.max(0,Math.min(amt,s.stack));s.stack-=a;P.bet+=a;P.contrib+=a;if(s.stack===0)P.allin=true;return a}
const canAct=(h,i)=>h.p[i]&&!h.p[i].folded&&!h.p[i].allin;

function act(st,priv,who,a,now){
  const h=st.hand;if(!handLive(st))E('no_hand');
  const i=seatOf(st,who.acct);if(i<0||h.toAct!==i)E('not_your_turn');
  if(st.seats[i].away)st.seats[i].away=false;st.seats[i].to=0;
  apply(st,priv,i,a,now);return true;
}
function apply(st,priv,i,a,now){
  const h=st.hand,P=h.p[i],S=st.seats[i],toCall=h.curBet-P.bet,nm=P.name;
  const t=a&&a.type;
  if(t==='fold'){P.folded=true;P.la='Fold';h.last=nm+': fold'}
  else if(t==='check'){if(toCall>0)E('cannot_check');P.la='Check';h.last=nm+': check'}
  else if(t==='call'){if(toCall<=0){P.la='Check';h.last=nm+': check'}else{const x=put(st,i,toCall);P.la=P.allin?'All-in':'Call';h.last=nm+(P.allin?': all-in '+P.bet:': call '+x)}}
  else if(t==='raise'||t==='allin'){
    const maxTo=Math.min(P.bet+S.stack,potCap(st,i));let to=t==='allin'?maxTo:Math.round(+a.amount);
    if(!(to>0))E('bad_amount');if(to>maxTo)to=maxTo;
    if(to<=h.curBet){if(to===maxTo)return apply(st,priv,i,{type:'call'},now);E('too_small')}
    const inc=to-h.curBet;
    if(inc<h.minRaise&&to<maxTo)E('min_raise');
    const wasBet=h.curBet>0;put(st,i,to-P.bet);
    if(inc>=h.minRaise)h.minRaise=inc;
    h.curBet=to;Object.keys(h.p).forEach(k=>{if(+k!==i&&canAct(h,+k))h.p[k].acted=false});
    P.la=P.allin?'All-in':wasBet?'Raise':'Bet';
    h.last=nm+(P.allin?': all-in '+to:wasBet?': raise a '+to:': bet '+to);
  }else E('bad_action');
  P.acted=true;
  progress(st,priv,now,i);
}
function progress(st,priv,now,from){
  const h=st.hand,seats=Object.keys(h.p).map(Number);
  const alive=seats.filter(k=>!h.p[k].folded);
  if(alive.length===1)return finishUncontested(st,priv,alive[0],now);
  const start=from==null?(h.toAct==null?h.dealer:h.toAct-1):from;
  const nx=nextIdx(start,k=>canAct(h,k)&&(!h.p[k].acted||h.p[k].bet<h.curBet));
  if(nx>=0){h.toAct=nx;h.deadline=now+st.cfg.turnSec*1000;return}
  // fine del giro di puntate
  for(;;){
    seats.forEach(k=>{const P=h.p[k];P.bet=0;P.acted=false;if(!P.folded&&!P.allin)P.la=null});h.curBet=0;h.minRaise=h.bbAmt||st.cfg.bb;
    if(h.stage==='river')return showdown(st,priv,now);
    if(h.stage==='preflop'){h.board.push(priv.deck.pop(),priv.deck.pop(),priv.deck.pop());h.stage='flop'}
    else if(h.stage==='flop'){h.board.push(priv.deck.pop());h.stage='turn'}
    else{h.board.push(priv.deck.pop());h.stage='river'}
    const movers=alive.filter(k=>canAct(h,k));
    if(movers.length>=2){h.toAct=nextIdx(h.dealer,k=>canAct(h,k));h.deadline=now+st.cfg.turnSec*1000;return}
    // uno solo (o nessuno) può ancora puntare: si scoprono le carte e si gira fino al river
    if(!h.reveal){h.reveal={};alive.forEach(k=>{h.reveal[k]=priv.holes[h.p[k].acct]});h.runout=true}
    h.toAct=null;h.deadline=null;
  }
}
function payout(st,i,amt,acct){const s=st.seats[i];if(s&&s.acct===acct)s.stack+=amt}
function finishUncontested(st,priv,w,now){
  const h=st.hand,pot=Object.values(h.p).reduce((a,x)=>a+x.contrib,0);
  payout(st,w,pot,h.p[w].acct);
  h.results=[{seat:w,name:h.p[w].name,win:pot,hand:null}];h.pot=pot;
  h.stage='done';h.toAct=null;h.deadline=null;h.endedAt=now;h.last=h.p[w].name+' vince '+pot;
  endHand(st,now);
}
function showdown(st,priv,now){
  const h=st.hand,seats=Object.keys(h.p).map(Number),alive=seats.filter(k=>!h.p[k].folded);
  const ev={};alive.forEach(k=>{ev[k]=st.cfg.game==='omaha'?bestOmaha(priv.holes[h.p[k].acct],h.board):best5(priv.holes[h.p[k].acct].concat(h.board))});
  h.reveal={};alive.forEach(k=>{h.reveal[k]=priv.holes[h.p[k].acct]});
  const levels=[...new Set(alive.map(k=>h.p[k].contrib))].sort((a,b)=>a-b);
  const won={};let prev=0,dist=0;
  const order=k=>((k-h.dealer-1)%NS+NS)%NS;
  levels.forEach(L=>{
    let amt=0;seats.forEach(k=>{amt+=Math.max(0,Math.min(h.p[k].contrib,L)-prev)});
    const el=alive.filter(k=>h.p[k].contrib>=L);prev=L;if(!amt||!el.length)return;
    let best=null,ws=[];el.forEach(k=>{const c=best?cmp(ev[k].s,best):1;if(c>0){best=ev[k].s;ws=[k]}else if(c===0)ws.push(k)});
    ws.sort((a,b)=>order(a)-order(b));
    const sh=Math.floor(amt/ws.length);let rem=amt-sh*ws.length;
    ws.forEach(k=>{won[k]=(won[k]||0)+sh+(rem>0?1:0);if(rem>0)rem--});dist+=amt;
  });
  const total=seats.reduce((a,k)=>a+h.p[k].contrib,0);
  if(total>dist){const k=alive.slice().sort((a,b)=>cmp(ev[b].s,ev[a].s))[0];won[k]=(won[k]||0)+total-dist}
  Object.keys(won).forEach(k=>payout(st,+k,won[k],h.p[k].acct));
  h.results=alive.map(k=>({seat:k,name:h.p[k].name,win:won[k]||0,hand:ev[k].n,best:ev[k].cards})).sort((a,b)=>b.win-a.win);
  h.pot=total;h.stage='done';h.toAct=null;h.deadline=null;h.endedAt=now;
  const W=h.results.filter(r=>r.win>0);
  h.last=W.map(r=>r.name).join(' e ')+' vinc'+(W.length>1?'ono':'e')+' con '+W[0].hand;
  endHand(st,now);
}
function endHand(st,now){
  const h=st.hand;
  const shown={};Object.keys(h.reveal||{}).forEach(k=>{shown[k]={name:h.p[k].name,cards:h.reveal[k]}});
  st.hist=(st.hist||[]).concat([{no:h.no,at:now,board:h.board.slice(),pot:h.pot,w:(h.results||[]).filter(r=>r.win>0).map(r=>({seat:r.seat,name:r.name,win:r.win,hand:r.hand})),shown,
    res:Object.keys(h.p).map(k=>({seat:+k,name:h.p[k].name,acct:h.p[k].acct,net:((h.results||[]).find(r=>r.seat===+k)||{win:0}).win-h.p[k].contrib}))}]).slice(-HIST);
  if(!sngOn(st)){
    st.busted=st.busted||{};st.outNow=[];
    st.seats.forEach((sx,k)=>{
      if(!sx||sx.stack>0)return;
      if(!h.p[k]||h.p[k].acct!==sx.acct)return;
      st.busted[sx.acct]=(st.busted[sx.acct]||0)+1;
      st.outNow.push({name:sx.name,acct:sx.acct,left:rebuysLeft(st,sx.acct)});
      st.seats[k]=null;
    });
  }
  if(sngOn(st)){
    const bust=Object.keys(h.p).map(Number).filter(k=>{const s=st.seats[k];return s&&s.acct===h.p[k].acct&&s.stack===0&&!s.out});
    const alive=st.seats.filter(s=>s&&!s.out&&s.stack>0).length;
    bust.sort((a,b)=>h.p[b].start-h.p[a].start).forEach((k,j)=>{const s=st.seats[k];s.out=true;st.sng.out.push({name:s.name,acct:s.acct,pos:alive+1+j})});
    sngCheckEnd(st,now);
  }
}
function sngCheckEnd(st,now){
  if(!sngOn(st))return;
  const left=st.seats.filter(s=>s&&!s.out&&s.stack>0);
  if(left.length>1)return;
  const w=left[0];
  st.sng.ranking=(w?[{name:w.name,acct:w.acct,pos:1}]:[]).concat(st.sng.out.slice().sort((a,b)=>a.pos-b.pos));
  st.sng.done=true;st.sng.endedAt=now;st.running=false;
}
function show(st,priv,who){
  const h=st.hand;if(!h||h.stage!=='done')E('no_hand');
  const i=seatOf(st,who.acct);if(i<0||!h.p[i])E('not_in_hand');
  if(priv.hand!==h.no||!priv.holes||!priv.holes[who.acct])E('no_cards');
  h.reveal=h.reveal||{};h.reveal[i]=priv.holes[who.acct];
  const last=st.hist&&st.hist[st.hist.length-1];if(last&&last.no===h.no){last.shown=last.shown||{};last.shown[i]={name:h.p[i].name,cards:h.reveal[i]}}
  return true;
}
/* il tavolo si chiude quando resta un solo giocatore (o nessuno) */
function roomCheck(st,now){
  if(st.closed)return false;
  const sat=st.seats.filter(Boolean),alive=sat.filter(s=>!s.out);
  if(handLive(st))return false;
  const started=!!(st.room&&st.room.started);
  if(sat.length&&st.cfg.mode!=='sng'&&started&&alive.length<=1){
    // nel cash si aspetta: chi è a 0 può rientrare
    if(!st.lonelyAt){st.lonelyAt=now;return true}
    if(now-st.lonelyAt<LONELY_MS)return false;
  }else if(alive.length>1&&st.lonelyAt){st.lonelyAt=null;return true}
  if(!sat.length||(started&&alive.length<=1)){
    st.closed=true;st.closedAt=now;st.running=false;
    st.final=sat.map(s=>({name:s.name,acct:s.acct,stack:s.stack,out:!!s.out,net:s.stack-st.cfg.stack*(1+(s.rebuys||0))})).sort((a,b)=>b.stack-a.stack);
    return true;
  }
  return false;
}
/* da chiamare spesso: scade il turno, parte la mano successiva */
function tick(st,priv,now){
  const h=st.hand;
  if(handLive(st)&&h.toAct!=null){
    const i=h.toAct,s=st.seats[i];
    if(now>=h.deadline||!s||s.away){
      if(s&&!s.away&&now>=h.deadline){s.to=(s.to||0)+1;if(s.to>=2)s.away=true}
      const P=h.p[i];apply(st,priv,i,{type:h.curBet-P.bet>0?'fold':'check'},now);
      return true;
    }
    return false;
  }
  let ch=false;
  // chi è in pausa da troppo tempo libera il posto
  if(!handLive(st))st.seats.forEach((sx,k)=>{
    if(!sx||!sx.away||sx.out)return;
    if(!sx.awayAt){sx.awayAt=now;ch=true;return}
    if(now-sx.awayAt>AWAY_MS&&!sngOn(st)){st.seats[k]=null;ch=true}
  });
  st.seats.forEach(sx=>{if(sx&&!sx.away&&sx.awayAt){delete sx.awayAt;ch=true}});
  if(st.running&&(!h||now>=(h.endedAt||0)+NEXT_DELAY)){
    if(newHand(st,priv,now))return true;
    // non si può ripartire: si sparecchia il tavolo
    if(h&&now>=(h.endedAt||0)+NEXT_DELAY+2500){st.hand=null;ch=true}
  }
  if(roomCheck(st,now))ch=true;
  return ch;
}
return {roomCheck,maxSeats,rebuysLeft,rebuyMax,LONELY_MS,initState,sit,leave,rebuy,setAway,config,start,act,show,tick,newHand,evalHand,best5,bestOmaha,potCap,cmp,seatOf,eligible,blinds,sngOn,NEXT_DELAY,NS,LV};
})();

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type, x-region",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const json = (o) => new Response(JSON.stringify(o), { headers: { ...cors, "Content-Type": "application/json" } });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const db = createClient(Deno.env.get("SUPABASE_URL"), Deno.env.get("SUPABASE_SERVICE_ROLE_KEY"), { auth: { persistSession: false } });
/* chi chiama: la firma dell'accesso si controlla qui, con le chiavi pubbliche di Supabase (tenute in memoria),
   senza un giro di rete a ogni mossa; se la chiave non torna si chiede direttamente a Supabase */
const SB_URL = Deno.env.get("SUPABASE_URL");
const JWKS = createRemoteJWKSet(new URL(SB_URL + "/auth/v1/.well-known/jwks.json"));
async function authSub(jwt) {
  if (!jwt) return null;
  try {
    const { payload } = await jwtVerify(jwt, JWKS, { issuer: SB_URL + "/auth/v1" });
    return payload.role === "authenticated" && payload.sub ? String(payload.sub) : null;
  } catch (_) {
    const { data } = await db.auth.getUser(jwt).catch(() => ({ data: null }));
    return data?.user?.id || null;
  }
}
const rid = () => "r" + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  try {
    const jwt = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
    const [sub, body] = await Promise.all([authSub(jwt), req.json()]);
    if (!sub) return json({ error: "not_signed_in" });
    const tid = String(body.table_id ?? "");
    const action = String(body.action ?? "state");
    let room = String(body.room_id ?? "");

    if (action === "list") {
      const { data: C } = await db.rpc("hpl_vr_ctx", { p_uid: sub, p_tid: tid, p_rid: "" });
      if (!C?.member) return json({ error: "not_member" });
      const { data: rows } = await db.from("hpl_vr").select("room_id, state").eq("table_id", tid);
      return json({ ok: true, rooms: (rows ?? []).map((r) => r.state), now: Date.now() });
    }
    if (action === "create") {
      const { data: C } = await db.rpc("hpl_vr_ctx", { p_uid: sub, p_tid: tid, p_rid: "" });
      if (!C?.member) return json({ error: "not_member" });
      if (!C.acct || !C.pid) return json({ error: "no_player" });
      await db.rpc("hpl_vr_gc", { p_tid: tid });
      // si contano i tavoli dopo la pulizia, non prima
      const { count: nRooms } = await db.from("hpl_vr").select("room_id", { count: "exact", head: true }).eq("table_id", tid);
      if ((nRooms ?? C.rooms ?? 0) >= 12) return json({ error: "too_many_rooms" });
      const now = Date.now();
      room = rid();
      const ms = [2, 6, 9].includes(Number(body.maxSeats)) ? Number(body.maxSeats) : 6;
      const name = String(body.name || "").trim().slice(0, 28) || "Tavolo di " + String(C.name).split(" ")[0];
      const st = HE.initState({ id: room, name, maxSeats: ms, createdBy: C.name, createdAt: now, started: false });
      try { HE.config(st, body.cfg || {}); HE.sit(st, { acct: C.acct, pid: C.pid, name: C.name }, 0); }
      catch (e) { return json({ error: e.code || String(e.message || e) }); }
      st.v = 0;
      const { data: ok, error } = await db.rpc("hpl_vr_save", { p_tid: tid, p_rid: room, p_v: -1, p_state: st, p_priv: {} });
      if (error) return json({ error: error.message });
      if (!ok) return json({ error: "busy" });
      return json({ ok: true, state: st, cards: null, now, rid: room });
    }

    for (let attempt = 0; attempt < 6; attempt++) {
      const { data: C, error: ce } = await db.rpc("hpl_vr_ctx", { p_uid: sub, p_tid: tid, p_rid: room });
      if (ce) return json({ error: ce.message });
      if (!C?.member) return json({ error: "not_member" });
      if (!C.acct || !C.pid) return json({ error: "no_player" });
      if (!C.state) return json({ error: "room_gone", gone: true });
      const who = { acct: C.acct, pid: C.pid, name: C.name };
      if (action === "kill") {
        if (!C.mgr) return json({ error: "not_manager" });
        await db.rpc("hpl_vr_del", { p_tid: tid, p_rid: room });
        return json({ ok: true, deleted: true, killed: true, now: Date.now() });
      }
      const st = C.state, v = C.v, priv = C.priv || {};
      // regole, avvio e stop: solo chi è seduto a quel tavolo (o un gestore)
      if (["config", "start", "stop"].includes(action) && HE.seatOf(st, C.acct) < 0 && !C.mgr) return json({ error: "not_seated", state: st, now: Date.now() });
      const now = Date.now();
      let changed = false;
      try {
        if (st.closed && !["state", "leave"].includes(action)) throw Object.assign(new Error("room_closed"), { code: "room_closed" });
        if (action === "sit") changed = HE.sit(st, who, body.seat == null ? null : Number(body.seat));
        else if (action === "leave") changed = HE.leave(st, priv, who, now);
        else if (action === "rebuy") changed = HE.rebuy(st, who);
        else if (action === "away") changed = HE.setAway(st, who, !!body.away);
        else if (action === "config") changed = HE.config(st, body.cfg || {});
        else if (action === "start") changed = HE.start(st, priv, now);
        else if (action === "show") changed = HE.show(st, priv, who);
        else if (action === "stop") { st.running = false; changed = true; }
        else if (action === "act") changed = HE.act(st, priv, who, body.move || {}, now);
        else if (action === "tick") changed = HE.tick(st, priv, now);
      } catch (e) {
        return json({ error: e.code || String(e.message || e), state: st, now });
      }
      if (changed && HE.roomCheck(st, now)) changed = true;
      // tavolo vuoto: si elimina subito
      if (st.closed && !st.seats.some(Boolean)) {
        await db.rpc("hpl_vr_del", { p_tid: tid, p_rid: room });
        return json({ ok: true, state: st, deleted: true, now });
      }
      if (changed) {
        st.v = v + 1;
        const { data: saved, error: se } = await db.rpc("hpl_vr_save", { p_tid: tid, p_rid: room, p_v: v, p_state: st, p_priv: priv });
        if (se) return json({ error: se.message });
        if (!saved) { await sleep(30 + Math.random() * 90); continue; }
      }
      const cards = st.hand && priv.hand === st.hand.no && priv.holes ? priv.holes[C.acct] || null : null;
      return json({ ok: true, state: st, cards, now });
    }
    return json({ error: "busy" });
  } catch (e) {
    return json({ error: String(e?.message || e) });
  }
});
