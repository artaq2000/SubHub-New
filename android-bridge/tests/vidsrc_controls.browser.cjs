const {chromium}=require('playwright');
const {fn,site}=require('./vidsrc_controls.test.cjs');
const fs=require('fs'),path=require('path'),assert=require('assert');
(async()=>{
 const browser=await chromium.launch({headless:true});
 const page=await browser.newPage({viewport:{width:390,height:844},hasTouch:true});
 await page.setContent(`<style>
 *{box-sizing:border-box}body{margin:0;background:#111}#embedPlayerModal{margin:20px 10px}.video-modal-box{position:relative;width:370px;background:#000;border:1px solid #333}#embedFrameContainer{position:relative;width:100%;aspect-ratio:16/9;background:#345}#embedTopBar{position:absolute;top:4px;left:62px;z-index:2147483600;display:flex}#subPanel{height:400px;display:none;background:#789}#subPanel.open{display:block}.pseudo-fullscreen{position:fixed!important;inset:0!important;width:100%!important;height:100%!important}#embedTopBar button{width:44px;height:44px}
 </style><div id="embedPlayerModal" class="open"><div class="video-modal-box" data-subhub-vidsrc="1"><div id="embedTopBar"><button class="video-top-btn yt-close-btn" id="embedFsBtn" title="ملء الشاشة">⛶</button><button class="video-top-btn yt-close-btn" id="embedStretchBtn">١٠٠</button><button id="embedCcBtn">CC</button><button title="إغلاق">×</button></div><div id="embedFrameContainer"></div><div id="subPanel"></div></div></div>`);
 await page.addStyleTag({content:fs.readFileSync(path.join(__dirname,'fixtures/vidsrc_web374.css'),'utf8')});
 await page.addScriptTag({content:`let vidSrcTakeoverDraggingV3222=false,vidSrcTakeoverHideTimerV3222=0,clockReadyState=2,vidSrcPlaybackPendingV3252=null;function requestVidSrcPlaybackV3252(){sendVidSrcSafeCommandV3211('setplayback')};window.sent=[];function sendVidSrcSafeCommandV3211(cmd){sent.push(cmd)};${['wakeVidSrcControlsV3251','syncVidSrcViewportControlsV3251','installVidSrcViewportControlsV3251','fmtVidSrcTimeV3222','ensureVidSrcTakeoverV3222','updateVidSrcTakeoverV3222','ensureVidSrcProviderShortcutsV3231','syncVidSrcSubPanelLayoutV3230'].map(n=>fn(site,n)).join('\n')}
 ensureVidSrcProviderShortcutsV3231();const root=ensureVidSrcTakeoverV3222();root.classList.add('on');updateVidSrcTakeoverV3222(120,3600,true);window.testRoot=root;`});
 const play=page.locator('[data-sh3222="play"]');
 await play.click();assert.deepEqual(await page.evaluate(()=>sent),['setplayback']);
 assert(await page.locator('[data-provider-action="quality"]').isVisible());
 assert(await page.locator('#embedStretchBtn').isVisible());
 assert(await page.locator('#embedFsBtn').isVisible());
 assert(!(await page.locator('#embedTopBar button[title="إغلاق"]').isVisible()));
 const rect=await page.locator('#subhub-vidsrc-takeover-v3222').boundingBox();
 await page.evaluate(()=>{document.querySelector('#subPanel').classList.add('open');syncVidSrcSubPanelLayoutV3230();});
 await page.waitForTimeout(100);
 const after=await page.locator('#subhub-vidsrc-takeover-v3222').boundingBox();assert.equal(after.height,rect.height);
 let bar=await page.locator('.sh-v3222-bar').boundingBox();assert(bar.y+bar.height<=after.y+after.height,'toolbar stays inside video with panel open');
 assert(await page.locator('.sh-v3222-bar').isVisible());
 await page.setViewportSize({width:844,height:390});
 await page.evaluate(()=>{document.querySelector('.video-modal-box').classList.add('pseudo-fullscreen','sh-v374-awake');syncVidSrcSubPanelLayoutV3230();});
 await page.waitForTimeout(100);
 const full=await page.locator('#subhub-vidsrc-takeover-v3222').boundingBox();const fr=await page.locator('#embedFrameContainer').boundingBox();assert(Math.abs(full.height-fr.height)<1);
 await play.click();assert.deepEqual(await page.evaluate(()=>sent),['setplayback','setplayback']);
 await page.evaluate(()=>{document.querySelector('#subPanel').classList.remove('open');syncVidSrcSubPanelLayoutV3230();document.querySelector('.video-modal-box').classList.remove('sh-v374-awake');document.querySelector('.video-modal-box').classList.add('sh-v374-idle');});
 await page.waitForTimeout(100);assert(!(await play.isVisible()));
 await page.locator('.sh-v3251-surface').click({position:{x:40,y:90}});assert(await play.isVisible());
 await play.click();assert.equal((await page.evaluate(()=>sent)).length,3);
 await page.screenshot({path:'/tmp/vidsrc-controls-51.png'});
 console.log('Chromium: single bottom play/pause, subtitle panel geometry, fullscreen, idle and touch wake: PASS');
 await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});
