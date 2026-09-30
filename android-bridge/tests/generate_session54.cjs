const {fn,site}=require('./vidsrc_controls.test.cjs');
const fs=require('fs'),path=require('path');
const dest=path.join(__dirname,'../app/src/androidTest/assets');fs.mkdirSync(dest,{recursive:true});
const fixture=n=>fs.readFileSync(path.join(__dirname,'fixtures',n),'utf8');
const names=['isVidSrcFrameActiveV328','syncVidSrcGuardV328','sendVidSrcSafeCommandV3211','nativeTapVidSrcV3213','requestVidSrcCaptionOffV3227','wakeVidSrcControlsV3251','syncVidSrcViewportControlsV3251','installVidSrcViewportControlsV3251','fmtVidSrcTimeV3222','ensureVidSrcTakeoverV3222','updateVidSrcTakeoverV3222','vidSrcPlaybackStatusV3252','cancelVidSrcPlaybackV3252','wakeVidSrcPlaybackV3256','requestVidSrcPlaybackV3252','nativeVidSrcPlaybackV3253','armVidSrcPlaybackTimeoutV3253','receiveVidSrcPlaybackV3253','syncVidSrcSessionV3254','recoverVidSrcPlaybackV3254','tickVidSrcControlsV3254'];
const script=`let vidSrcTakeoverDraggingV3222=false,vidSrcTakeoverHideTimerV3222=0,clockReadyState=0,vidSrcPlaybackPendingV3252=null,vidSrcPlaybackStatusTimerV3252=0,vidSrcResumeTargetV3256=0,vidSrcWakeTimerV3256=0,clockSource='',clockPaused=true,clockWaiting=false,clockEnded=false,clockLinked=false,lastSeq=1,lastVidSrcGuardState=null;let vidSrcSessionV3254=null,vidSrcControlsDeadlineV3254=0,vidSrcCaptionOffAtV3227=0;
const VIDSRC_GUARD_TOKEN='__VIDSRC_GUARD_TOKEN__';
function endVidSrcQualityV3251(){};
${names.map(n=>fn(site,n)).join('\n')}
window.SubHubNativePlayback=receiveVidSrcPlaybackV3253;
window.SubHubNativeClock=p=>{if(p.stage)return;clockSource=p.source;clockPaused=p.paused;clockEnded=p.ended;clockReadyState=p.readyState;updateVidSrcTakeoverV3222(p.currentTime,p.duration,!p.paused&&!p.ended);window.testLast=p;};
window.SubHubNativeResumeV3256=()=>{cancelVidSrcPlaybackV3252();clearTimeout(vidSrcWakeTimerV3256);syncVidSrcGuardV328(true);const session=syncVidSrcSessionV3254();vidSrcControlsDeadlineV3254=Date.now()+2600;if(!session)return;try{session.resumeAt=Number(SubHubAndroidBridge.vidSrcSession(VIDSRC_GUARD_TOKEN,session.src))||0}catch(_){}const point=Math.max(0,Number(session.resumeAt)||Number(window.__subhubVidSrcTimeV3222)||0);vidSrcResumeTargetV3256=point;clockSource='';lastSeq=-1;clockReadyState=0;clockLinked=false;clockPaused=true;clockWaiting=false;clockEnded=false;window.__subhubVidSrcTimeV3222=point;window.__subhubVidSrcPlayingV3222=false;updateVidSrcTakeoverV3222(point,window.__subhubVidSrcDurationV3211||0,false);};
window.SubHubNativeResumeV3255=window.SubHubNativeResumeV3256;window.SubHubNativeResumeV3254=window.SubHubNativeResumeV3256;
const modal=document.getElementById('embedPlayerModal');modal.classList.add('open','inline-player-v265');modal.style.cssText='width:370px!important;left:10px!important;top:100px!important;height:208px!important;--inline-host-h-v335:208px';
document.querySelector('.video-modal-box').setAttribute('data-subhub-vidsrc','1');
const fr=document.createElement('iframe');fr.src='https://vidsrc.to/embed/movie/native54';fr.allow='autoplay';fr.style.cssText='width:100%;height:100%;position:absolute;border:0';document.querySelector('#embedFrameContainer').appendChild(fr);
syncVidSrcGuardV328(true);syncVidSrcSessionV3254();const controls=ensureVidSrcTakeoverV3222();controls.classList.add('on');
setInterval(()=>{syncVidSrcSessionV3254();tickVidSrcControlsV3254()},50);
window.testToggle=()=>requestVidSrcPlaybackV3252();
window.testState=()=>({paused:clockPaused,pending:!!vidSrcPlaybackPendingV3252,time:window.__subhubVidSrcTimeV3222||0,status:document.querySelector('.sh-v3252-status').textContent,center:getComputedStyle(document.querySelector('.sh-v3222-center')).visibility,ready:clockReadyState,source:clockSource,resume:vidSrcSessionV3254?.resumeAt});`;
fs.writeFileSync(path.join(dest,'home54.html'),`<!doctype html><html dir="rtl"><head><style>${fixture('web374_all.css')}</style></head><body>${fixture('web374_embed.html')}</body></html>`);
fs.writeFileSync(path.join(dest,'top54.js'),script);
// The actual movie moves to a provider with no VidSrc ancestor, then uses shadow DOM.
fs.writeFileSync(path.join(dest,'redirect54.html'),`<script>location.replace('https://provider.test/restored');</script>`);
fs.writeFileSync(path.join(dest,'provider54.html'),`<!doctype html><html><body><div id="host"></div><script>
const root=document.getElementById('host').attachShadow({mode:'open'});
root.innerHTML='<video autoplay muted loop playsinline style="width:100vw;height:100vh" src="data:video/webm;base64,${fixture('session54.webm.b64')}"></video>';
const movie=root.querySelector('video');
movie.addEventListener('click',()=>{try{if(movie.paused)movie.play();else movie.pause()}catch(_){}});
SubHubPlayerChannel.addEventListener('message',e=>{const d=JSON.parse(e.data);if(d.command==='test-stop-timers'){const end=setInterval(()=>{},1000);for(let i=1;i<=end;i++)clearInterval(i);}});
</script></body></html>`);
console.log('Generated offline native WebView lifecycle fixture');
