const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert');
const dir=path.join(__dirname,'../app/src/main/assets');
const site=fs.readFileSync(path.join(dir,'site_bridge.js'),'utf8');
const clock=fs.readFileSync(path.join(dir,'player_clock.js'),'utf8');
function fn(src,name){const a=src.indexOf('  function '+name+'(');assert(a>=0,name);return src.slice(a,src.indexOf('\n  }',a)+4);}
// A rapid play/pause must not queue play again, including after changing movies.
const sent=[],timers=[];let frame={contentWindow:{postMessage:m=>sent.push(m)}};
const c={document:{querySelector:()=>frame},isVidSrcFrameActiveV328:()=>true,setTimeout:f=>timers.push(f)};
vm.createContext(c);vm.runInContext(fn(site,'sendVidSrcSafeCommandV3211'),c);
c.sendVidSrcSafeCommandV3211('play');c.sendVidSrcSafeCommandV3211('pause');
frame={contentWindow:{postMessage:m=>sent.push(m)}};timers.forEach(f=>f());
assert.deepEqual(sent.map(m=>m.command),['play','pause']);assert.equal(timers.length,0);
// Toggle reads media.paused at command receipt, not a stale UI clock.
const video={isConnected:true,paused:false,ended:false,pause(){this.paused=true},play(){this.paused=false;return Promise.resolve()}};
const k={activeVideo:video,activeVidSrcVideoV3227:()=>video,send(){},relaySafeCommandDownV3211(){},bootstrapSafePlayV3212(){throw Error('must use connected media')},document:{contains:()=>false}};
vm.createContext(k);vm.runInContext(fn(clock,'handleSafeCommandV3211'),k);
k.handleSafeCommandV3211({command:'toggle'});assert.equal(video.paused,true);
k.handleSafeCommandV3211({command:'toggle'});assert.equal(video.paused,false);
console.log('Single-send play/pause, immediate media-state toggle, shadow media: PASS');
// Expanding the panel changes the outer box height only; toolbar bounds stay at frame.
const values={};let height=640;
const box={offsetWidth:360,get offsetHeight(){return height},scrollLeft:0,scrollTop:0,clientLeft:0,clientTop:0,getBoundingClientRect:()=>({left:10,top:100,width:360,height})};
const viewport={getBoundingClientRect:()=>({left:10,top:100,width:360,height:202.5})};
const root={parentElement:box,style:{setProperty:(k,v)=>values[k]=v,getPropertyValue:k=>values[k]}};
const g={document:{getElementById:()=>viewport}};vm.createContext(g);vm.runInContext(fn(site,'syncVidSrcViewportControlsV3251'),g);
g.syncVidSrcViewportControlsV3251(root);const first={...values};height=1100;g.syncVidSrcViewportControlsV3251(root);assert.deepEqual(values,first);assert.equal(values.height,'202.5px');
console.log('Opening subtitle settings does not extend video control bounds: PASS');
// Quality gear selection must prefer the real visible provider button over
// a hidden duplicate left in the player DOM.
{
 const mk=(name,visible)=>({
   visible,textContent:'',innerHTML:'<svg class="settings-icon"></svg>',
   id:name,className:'settings-button',disabled:false,
   getAttribute:n=>n==='aria-label'?'Settings':null,
   getBoundingClientRect:()=>({left:300,top:160,width:40,height:40,right:340,bottom:200})
 });
 const hiddenGear=mk('hidden-settings',false),visibleGear=mk('visible-settings',true);
 const root={querySelectorAll:()=>[hiddenGear,visibleGear]};
 const ctx={
   document:{querySelectorAll:()=>[hiddenGear,visibleGear]},
   vidSrcDeepRootsV3226:()=>[root],
   vidSrcDeepQueryAllV3226:()=>[hiddenGear,visibleGear],
   deepVisibleV3227:el=>!!el.visible,
   deepTextV3227:el=>String(el.getAttribute('aria-label')||'').toLowerCase(),
   activeVidSrcVideoV3227:()=>({getBoundingClientRect:()=>({left:0,top:0,width:360,height:202,right:360,bottom:202})})
 };
 vm.createContext(ctx);
 vm.runInContext([
   fn(clock,'vidSrcProviderButtonScoreV3260'),
   fn(clock,'vidSrcProviderButtonCandidatesV3260'),
   fn(clock,'findVidSrcProviderButtonV3231')
 ].join('\n'),ctx);
 assert.equal(ctx.findVidSrcProviderButtonV3231('quality'),visibleGear);
 console.log('Quality gear prefers the visible provider settings control: PASS');
}

// Quality entry reads the provider's real options, reports them to SubHub,
 // selects the requested provider option, then restores the protected controls.
 const classes=new Set(),messages=[];let clicks=0,chosen=0,restored=0,scrubbed=0;
 function option(text,selected){
   return {
     textContent:text,className:selected?'selected':'',isConnected:true,tagName:'BUTTON',
     matches:()=>true,closest(){return this},
     getAttribute:n=>n==='aria-checked'?(selected?'true':'false'):null,
     getBoundingClientRect:()=>({width:54,height:28}),
     click(){chosen++}
   };
 }
 const q360=option('360p',false),q720=option('720p',true),q1080=option('1080p',false);
 const settings={
   getAttribute:()=>null,
   click(){clicks++},
   isConnected:true
 };
 const q={
   document:{
     documentElement:{classList:{add:c=>classes.add(c),remove:c=>classes.delete(c)}},
     getElementById:()=>({}),
     querySelectorAll:()=>[]
   },
   window:{top:{postMessage:m=>messages.push(m)}},
   activeVidSrcVideoV3227:()=>video,
   findVidSrcProviderButtonV3231:()=>settings,
   restoreVidSrcProviderUiV3234:()=>restored++,
   setVidSrcProviderUiScrubV3234:()=>scrubbed++,
   vidSrcTakeoverActiveV3224:true,
   vidSrcDeepQueryAllV3226:sel=>sel.includes('quality')||sel.includes('menuitem')?[q360,q720,q1080]:[],
   setTimeout:(fn,ms)=>(ms<1000&&fn(),1),
   clearTimeout(){}
 };
 vm.createContext(q);
 vm.runInContext('let vidSrcQualityMenuV3251=null;'+[
   'vidSrcQualityKeyV3259','vidSrcQualityLabelV3259','vidSrcQualityCandidateScoreV3259',
   'vidSrcQualityClickTargetV3259','collectVidSrcQualityOptionsV3259','findVidSrcQualitySubmenuV3259',
   'reportVidSrcQualityV3251','closeVidSrcQualityV3251','selectVidSrcProviderQualityV3259',
   'openVidSrcProviderQualityV3231'
 ].map(n=>fn(clock,n)).join('\n'),q);
 assert.equal(q.openVidSrcProviderQualityV3231({requestId:'session'}),true);
 assert.equal(clicks,1);assert.equal(restored,1);
 assert(messages.some(m=>m.open===true&&m.options.join(',')==='1080p,720p,360p'&&m.selected==='720p'));
 assert.equal(q.selectVidSrcProviderQualityV3259({requestId:'session',quality:'1080p'}),true);
 assert.equal(chosen,1);assert(scrubbed>=1);
 console.log('Quality reads real provider values and selects the requested hidden option: PASS');
module.exports={fn,site,clock};
