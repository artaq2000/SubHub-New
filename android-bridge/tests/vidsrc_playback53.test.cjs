const {fn,site,clock}=require('./vidsrc_controls.test.cjs');
const assert=require('assert'),vm=require('vm');
const block=clock.slice(clock.indexOf('  // Commands and results use Android IPC'),clock.indexOf('  const vidSrcPlaybackCommandsV3252'));
(async()=>{
 let now=1000,live='',next=null,playCalls=0,pauseCalls=0,resolvePlay,rejectPlay;
 const results=[];
 const v={isConnected:true,paused:false,ended:false,readyState:4,currentTime:60,seeking:false,error:null,
   play(){playCalls++;this.paused=false;return new Promise((r,j)=>{resolvePlay=r;rejectPlay=j})},
   pause(){pauseCalls++;this.paused=true}};
 const b={pollVidSrcPlayback(){const d={activeRequest:live,...next};next=null;return JSON.stringify(d)},vidSrcPlaybackResult(token,raw){results.push(JSON.parse(raw))}};
 const c={window:{addEventListener(){}},document:{addEventListener(){}},activeVideo:v,sourceId:'real-leaf',bridge:()=>b,payload:video=>({source:'real-leaf',paused:video.paused,ended:video.ended,seq:results.length+1}),isVidSrcChainV3216:()=>true,Date:{now:()=>now},Set,JSON,Number,Math};
 vm.createContext(c);vm.runInContext(block,c);
 const request=id=>{live=id;next={requestId:id,targetSource:'real-leaf',timeoutMs:14700};c.pollVidSrcPlaybackV3253()};
 request('pause');assert.equal(v.paused,true);assert.equal(results.at(-1).phase,'complete');assert.equal(pauseCalls,1);
 request('resume');assert.equal(playCalls,1);assert.equal(results.at(-1).phase,'preparing');
 c.startVidSrcPlaybackV3253({requestId:'resume',targetSource:'real-leaf'},v);assert.equal(playCalls,1);
 resolvePlay();await Promise.resolve();assert.equal(results.at(-1).phase,'complete');assert.equal(results.at(-1).playing,true);
 for(let i=0;i<3;i++){request('pause'+i);assert(v.paused);request('resume'+i);resolvePlay();await Promise.resolve();assert(!v.paused);}
 request('pause-for-close');request('close-while-loading');const count=results.length;live='';c.pollVidSrcPlaybackV3253();assert(v.paused);resolvePlay();await Promise.resolve();assert.equal(results.length,count);
 request('reopen');resolvePlay();await Promise.resolve();assert.equal(results.at(-1).requestId,'reopen');assert.equal(results.at(-1).phase,'complete');
 request('pause-for-stall');request('stalled');now+=14701;c.pollVidSrcPlaybackV3253();assert(v.paused);assert.equal(results.at(-1).error,'buffer-timeout');resolvePlay();await Promise.resolve();assert.equal(results.at(-1).error,'buffer-timeout');
 request('rejected');rejectPlay({name:'NotAllowedError'});await Promise.resolve();assert(v.paused);assert.equal(results.at(-1).error,'NotAllowedError');
 request('replaced');v.isConnected=false;c.pollVidSrcPlaybackV3253();assert.equal(results.at(-1).error,'media-replaced');assert(v.paused);
 console.log('Native media controller: repeat pause/resume, dedup, real confirmation, cancel/reopen, bounded stall, rejection, replacement: PASS');
 const frame={src:'https://vidsrc.to/embed/current',isConnected:true},status={textContent:''},timers=[];let id=0,requests=0,cancels=0;
 let wakeups=0;
 const u={window:{__subhubVidSrcPlayingV3222:false,SubHubAndroidBridge:{requestVidSrcPlayback(){requests++;return true},cancelVidSrcPlayback(){cancels++}}},document:{querySelector:s=>s.includes('iframe')?frame:s.includes('status')?status:null},crypto:{getRandomValues:a=>a.fill(++id)},VIDSRC_GUARD_TOKEN:'guard',isVidSrcFrameActiveV328:()=>true,updateVidSrcTakeoverV3222(){},wakeVidSrcPlaybackV3256(){wakeups++;return false},setTimeout:f=>(timers.push(f),timers.length),clearTimeout(){},Date};
 vm.createContext(u);vm.runInContext("let clockSource='stale',clockReadyState=2,clockLinked=true,clockPaused=false,clockEnded=false,clockWaiting=false,lastSeq=99;let vidSrcPlaybackPendingV3252=null,vidSrcPlaybackStatusTimerV3252=0;"+['vidSrcPlaybackStatusV3252','cancelVidSrcPlaybackV3252','nativeVidSrcPlaybackV3253','requestVidSrcPlaybackV3252','armVidSrcPlaybackTimeoutV3253','receiveVidSrcPlaybackV3253'].map(n=>fn(site,n)).join('\n'),u);
 for(let i=0;i<5;i++)u.requestVidSrcPlaybackV3252();assert.equal(requests,1);assert.equal(status.textContent,'جارٍ الاتصال بالمشغّل…');
 let p=vm.runInContext('vidSrcPlaybackPendingV3252',u);
 u.receiveVidSrcPlaybackV3253({requestId:p.id,source:'real-leaf',phase:'preparing',paused:false,seq:100});assert(status.textContent.includes('تحضير'));assert(p.started);
 u.receiveVidSrcPlaybackV3253({requestId:p.id,source:'real-leaf',phase:'complete',paused:false,seq:101});assert.equal(vm.runInContext('vidSrcPlaybackPendingV3252',u),null);assert.equal(status.textContent,'');
 u.requestVidSrcPlaybackV3252();p=vm.runInContext('vidSrcPlaybackPendingV3252',u);timers.at(-1)();assert.equal(vm.runInContext('vidSrcPlaybackPendingV3252',u),null);assert(status.textContent.includes('تعذّر الاتصال'));assert(cancels>=2);assert(wakeups>=1);
 console.log('UI: native-only request, double-tap coalescing, actual preparing/complete, timeout clears pending: PASS');
})().catch(e=>{console.error(e);process.exit(1)});
