const {chromium}=require('playwright');
const {fn,site,clock}=require('./vidsrc_controls.test.cjs');
const fs=require('fs'),path=require('path'),assert=require('assert');
const fixture=n=>fs.readFileSync(path.join(__dirname,'fixtures',n),'utf8');
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--autoplay-policy=no-user-gesture-required']});
 const context=await browser.newContext({viewport:{width:390,height:844},hasTouch:true});
 const page=await context.newPage();page.setDefaultTimeout(7000);
 let currentSource='',pending=null;const reports=[];
 // Synchronous IPC stand-in shared by otherwise unrelated origins. Java mailbox
 // semantics are tested separately on the JVM; this test uses real HTML media.
 await context.route('https://bridge.test/ipc',async route=>{
   const {method,args}=JSON.parse(route.request().postData());let reply=null,notify=null;
   if(method==='mediaClock') {const p=JSON.parse(args[0]);if(!p.stage)currentSource=p.source;}
   if(method==='requestVidSrcPlayback') {reply=!pending;if(reply)pending={id:args[1],source:currentSource,delivered:false};}
   if(method==='cancelVidSrcPlayback' && pending?.id===args[1]) pending=null;
   if(method==='pollVidSrcPlayback') {
     const source=args[1];reply={activeRequest:pending?.source===source?pending.id:''};
     if(pending && !pending.delivered && source===currentSource){pending.source=source;pending.delivered=true;reply={activeRequest:pending.id,requestId:pending.id,targetSource:source,timeoutMs:14700};}
   }
   if(method==='vidSrcPlaybackResult') {
     const p=JSON.parse(args[1]);
     if(pending?.id===p.requestId && pending.source===p.source){reports.push(p);notify=p;if(p.phase!=='preparing')pending=null;}
   }
   if(method==='isVidSrcGuardActive')reply=true;
   await route.fulfill({contentType:'application/json',headers:{'access-control-allow-origin':'*'},body:JSON.stringify(reply)});
   if(notify) page.evaluate(p=>window.SubHubNativePlayback?.(p),notify).catch(()=>{});
 });
 await context.addInitScript({content:`
 window.SubHubAndroidBridge={};
 for(const method of ['mediaClock','requestVidSrcPlayback','cancelVidSrcPlayback','pollVidSrcPlayback','vidSrcPlaybackResult','isVidSrcGuardActive']){
   window.SubHubAndroidBridge[method]=(...args)=>{const x=new XMLHttpRequest();x.open('POST','https://bridge.test/ipc',false);x.setRequestHeader('Content-Type','text/plain');x.send(JSON.stringify({method,args}));const r=JSON.parse(x.responseText);return method==='pollVidSrcPlayback'?JSON.stringify(r):r;};
 }
 ${clock}
 `});
 await context.route('https://player.test/**',r=>r.fulfill({contentType:'text/html',body:`<html><body style="margin:0"><video id="movie" autoplay muted loop playsinline style="width:100vw;height:100vh" src="data:video/webm;base64,${fixture('playback53.webm.b64')}"></video></body></html>`}));
 // Deliberately no postMessage relay: this is the failure the native route removes.
 await context.route('https://vidsrc.to/**',r=>r.fulfill({contentType:'text/html',body:`<html><body style="margin:0"><script>addEventListener('message',e=>e.stopImmediatePropagation(),true);<\/script><iframe src="https://player.test/leaf" style="width:100vw;height:100vh;border:0" allow="autoplay"></iframe></body></html>`}));
 await context.route('https://subhub.test/**',r=>r.fulfill({contentType:'text/html',body:'<html dir="rtl"><head></head><body></body></html>'}));
 await page.goto('https://subhub.test/');
 await page.addStyleTag({content:fixture('web374_all.css')});
 await page.evaluate(markup=>{
 document.body.innerHTML=markup;
 const modal=document.querySelector('#embedPlayerModal');modal.classList.add('open','inline-player-v265');modal.style.cssText='width:370px!important;left:10px!important;top:100px!important;height:208px!important;--inline-host-h-v335:208px';
 document.querySelector('.video-modal-box').setAttribute('data-subhub-vidsrc','1');
 const fr=document.createElement('iframe');fr.src='https://vidsrc.to/embed/test';fr.allow='autoplay';fr.style.cssText='width:100%;height:100%;position:absolute;border:0';document.querySelector('#embedFrameContainer').appendChild(fr);
 },fixture('web374_embed.html'));
 const methods=['isVidSrcFrameActiveV328','sendVidSrcSafeCommandV3211','wakeVidSrcControlsV3251','syncVidSrcViewportControlsV3251','installVidSrcViewportControlsV3251','fmtVidSrcTimeV3222','ensureVidSrcTakeoverV3222','updateVidSrcTakeoverV3222','vidSrcPlaybackStatusV3252','cancelVidSrcPlaybackV3252','requestVidSrcPlaybackV3252','nativeVidSrcPlaybackV3253','armVidSrcPlaybackTimeoutV3253','receiveVidSrcPlaybackV3253'];
 await page.addScriptTag({content:`let vidSrcTakeoverDraggingV3222=false,vidSrcTakeoverHideTimerV3222=0,clockReadyState=2,vidSrcPlaybackPendingV3252=null,vidSrcPlaybackStatusTimerV3252=0,vidSrcWakeRetryTimerV3257=0,clockSource='intentionally-stale',clockPaused=true,clockWaiting=false,clockEnded=false,lastSeq=1;const VIDSRC_GUARD_TOKEN='test';
 ${methods.map(n=>fn(site,n)).join('\n')}
 window.SubHubNativePlayback=receiveVidSrcPlaybackV3253;
 const root=ensureVidSrcTakeoverV3222();root.classList.add('on');updateVidSrcTakeoverV3222(120,3600,false);`});
 const leaf=()=>page.frames().find(f=>f.url().startsWith('https://player.test/'));
 await page.waitForFunction(()=>document.querySelector('#embedFrameContainer iframe'));
 while(!leaf()) await new Promise(r=>setTimeout(r,50));
 await leaf().waitForFunction(()=>document.querySelector('video')?.currentTime>0.1);
 const center=page.locator('.sh-v3222-center');
 async function toggle(expectPaused){
   await center.tap();
   await page.waitForFunction(()=>!document.querySelector('.sh-v3222-center').disabled);
   assert.equal(await leaf().evaluate(()=>document.querySelector('video').paused),expectPaused);
   assert.equal(await center.textContent(),expectPaused?'▶':'❚❚');
   assert.equal(await page.locator('.sh-v3252-status').textContent(),'');
 }
 for(let i=0;i<2;i++){await toggle(true);await toggle(false);}
 await page.setViewportSize({width:844,height:390});
 await page.evaluate(()=>document.querySelector('.video-modal-box').classList.add('pseudo-fullscreen'));
 await toggle(true);await toggle(false);
 const oldSource=currentSource;
 await page.evaluate(()=>{cancelVidSrcPlaybackV3252();const old=document.querySelector('#embedFrameContainer iframe');const replacement=old.cloneNode();replacement.src='https://vidsrc.to/embed/reopened';old.replaceWith(replacement)});
 const start=Date.now();while((!leaf()||currentSource===oldSource)&&Date.now()-start<6000)await new Promise(r=>setTimeout(r,50));
 assert.notEqual(currentSource,oldSource);await leaf().waitForFunction(()=>document.querySelector('video')?.currentTime>0.1);
 await toggle(true);await toggle(false);
 assert.equal(reports.filter(p=>p.phase==='complete').length,8);assert.equal(reports.filter(p=>p.phase==='preparing').length,4);assert.equal(reports.filter(p=>p.phase==='error').length,0);
 console.log('REAL media with blocked parent messages: single-tap pause/resume, stale UI, portrait/fullscreen, reopened frame and truthful status: PASS');
 await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});
