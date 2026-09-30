const {chromium}=require('playwright');
const {fn,site,clock}=require('./vidsrc_controls.test.cjs');
const fs=require('fs'),path=require('path'),assert=require('assert');
const fixture=n=>fs.readFileSync(path.join(__dirname,'fixtures',n),'utf8');
(async()=>{
 const browser=await chromium.launch({headless:true});
 const context=await browser.newContext({viewport:{width:390,height:844},hasTouch:true});
 const page=await context.newPage();page.setDefaultTimeout(7000);
 const leaf=`<html><body style="margin:0;background:#234"><script>
 const sourceId='leaf52';let seq=1;const vidSrcPlaybackCommandsV3252=new Map();let actions=[];
 const activeVideo={isConnected:true,paused:false,ended:false,pause(){actions.push('pause');this.paused=true},play(){actions.push('play');return new Promise(r=>setTimeout(()=>{this.paused=false;r()},120))}};
 function activeVidSrcVideoV3227(){return activeVideo}function send(){seq++}function relaySafeCommandDownV3211(){}
 ${fn(clock,'applyVidSrcPlaybackV3252')}${fn(clock,'handleSafeCommandV3211')}
 addEventListener('message',e=>{if(e.source===parent)handleSafeCommandV3211(e.data)});
 <\/script></body></html>`;
 await context.route('https://player.test/**',r=>r.fulfill({contentType:'text/html',body:leaf}));
 await context.route('https://vidsrc.to/**',r=>r.fulfill({contentType:'text/html',body:`<iframe src="https://player.test/leaf" style="position:absolute;inset:0;width:100%;height:100%;border:0"></iframe><script>window.forwarded=[];addEventListener('message',e=>{if(e.source===parent){forwarded.push(e.data.command);document.querySelector('iframe').contentWindow.postMessage(e.data,'*')}})<\/script>`}));
 await context.route('https://subhub.test/**',r=>r.fulfill({contentType:'text/html',body:'<html dir="rtl"><head></head><body></body></html>'}));
 await page.goto('https://subhub.test/');
 await page.addStyleTag({content:fixture('web374_all.css')});
 await page.evaluate(markup=>{
 document.body.innerHTML=markup;
 const modal=document.querySelector('#embedPlayerModal');modal.classList.add('open','inline-player-v265');modal.style.cssText='width:370px!important;left:10px!important;top:100px!important;height:208px!important;--inline-host-h-v335:208px';
 document.querySelector('.video-modal-box').setAttribute('data-subhub-vidsrc','1');
 const fr=document.createElement('iframe');fr.src='https://vidsrc.to/embed/test';fr.style.cssText='width:100%;height:100%;position:absolute;border:0';document.querySelector('#embedFrameContainer').appendChild(fr);
 },fixture('web374_embed.html'));
 await page.waitForFunction(()=>document.querySelector('#embedFrameContainer iframe').contentWindow!=null);
 const methods=['isVidSrcFrameActiveV328','sendVidSrcSafeCommandV3211','wakeVidSrcControlsV3251','syncVidSrcViewportControlsV3251','installVidSrcViewportControlsV3251','fmtVidSrcTimeV3222','ensureVidSrcTakeoverV3222','updateVidSrcTakeoverV3222','ensureVidSrcProviderShortcutsV3231','syncVidSrcSubPanelLayoutV3230','vidSrcPlaybackStatusV3252','cancelVidSrcPlaybackV3252','requestVidSrcPlaybackV3252','receiveVidSrcPlaybackV3252'];
 await page.addScriptTag({content:`let vidSrcTakeoverDraggingV3222=false,vidSrcTakeoverHideTimerV3222=0,clockReadyState=2,vidSrcPlaybackPendingV3252=null,vidSrcPlaybackStatusTimerV3252=0,clockSource='leaf52',clockPaused=false,clockEnded=false,lastSeq=1;
 function togglePlayerBar(){const bar=document.getElementById('embedTopBar');bar.classList.toggle('open');document.getElementById('embedUiTrigger').classList.toggle('active',bar.classList.contains('open'))}
 function toggleSubtitleModal(){document.getElementById('subPanel').classList.toggle('open');syncVidSrcSubPanelLayoutV3230()}
 function toggleEmbedFullscreen(){document.querySelector('.video-modal-box').classList.toggle('pseudo-fullscreen')}
 ${methods.map(n=>fn(site,n)).join('\n')}
 window.addEventListener('message',receiveVidSrcPlaybackV3252);
 ensureVidSrcProviderShortcutsV3231();const root=ensureVidSrcTakeoverV3222();root.classList.add('on');updateVidSrcTakeoverV3222(120,3600,true);`});
 await page.waitForFunction(()=>document.querySelector('#embedFrameContainer iframe').contentWindow!==null);
 console.log('Initial hit target',await page.locator('#embedUiTrigger').evaluate(el=>{const r=el.getBoundingClientRect();const top=document.elementFromPoint(r.x+r.width/2,r.y+r.height/2);return {rect:r.toJSON(),hit:top&&top.outerHTML.slice(0,150),z:getComputedStyle(el).zIndex}}));
 // Use actual touch hit testing, not element.click(), for the dots and CC.
 await page.locator('#embedUiTrigger').tap();assert(await page.locator('#embedTopBar').isVisible());
 await page.evaluate(()=>document.getElementById('embedCcBtn').style.display='flex');
 await page.locator('#embedCcBtn').tap();assert(await page.locator('#subPanel').isVisible());
 await page.locator('#embedCcBtn').tap();
 await page.locator('#embedUiTrigger').tap();assert(!(await page.locator('#embedTopBar').isVisible()));
 const play=page.locator('[data-sh3222="play"]');
 await play.tap();await page.waitForFunction(()=>document.querySelector('[data-sh3222="play"]').disabled===false);assert.equal(await play.textContent(),'▶');
 await play.tap();
 await page.evaluate(()=>{for(let i=0;i<5;i++)requestVidSrcPlaybackV3252()});
 await page.waitForFunction(()=>document.querySelector('[data-sh3222="play"]').disabled===false);assert.equal(await play.textContent(),'❚❚');
 const leafFrame=page.frames().find(f=>f.url().startsWith('https://player.test/'));assert(leafFrame);
 assert.deepEqual(await leafFrame.evaluate(()=>actions),['pause','play']);
 // Rotate and re-open the top tools while protection is active.
 await page.setViewportSize({width:844,height:390});
 await page.evaluate(()=>{document.querySelector('.video-modal-box').classList.add('pseudo-fullscreen');document.querySelector('#embedPlayerModal').style.top='0';});
 await page.locator('#embedUiTrigger').tap();assert(await page.locator('#embedTopBar').isVisible());
 await page.locator('#embedCcBtn').tap();assert(await page.locator('#subPanel').isVisible());
 await page.locator('#embedCcBtn').tap();await page.locator('#embedUiTrigger').tap();
 await play.tap();await page.waitForFunction(()=>document.querySelector('[data-sh3222="play"]').disabled===false);assert.equal(await play.textContent(),'▶');
 assert.deepEqual(await leafFrame.evaluate(()=>actions),['pause','play','pause']);
 console.log('Full Web 374 CSS: touch dots/CC above protection in portrait/fullscreen; nested-frame playback acknowledged once: PASS');
 await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});
