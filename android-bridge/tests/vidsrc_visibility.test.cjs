const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert');
const assets=path.join(__dirname,'../app/src/main/assets');
const source=fs.readFileSync(process.env.VIDSRC_CLOCK_SOURCE||path.join(assets,'player_clock.js'),'utf8');
class Element {
 constructor(tag,parent=null){this.tagName=tag.toUpperCase();this.parentNode=parent;this.isConnected=true;this.attrs={};this.values=new Map();this.textTracks=[];
 this.style={setProperty:(k,v,p='')=>this.values.set(k,[v,p]),removeProperty:k=>this.values.delete(k),getPropertyValue:k=>(this.values.get(k)||[''])[0],getPropertyPriority:k=>(this.values.get(k)||['',''])[1]};}
 setAttribute(k,v){this.attrs[k]=v}removeAttribute(k){delete this.attrs[k]}
 contains(other){while(other){if(other===this)return true;other=other.parentNode}return false}
 querySelectorAll(){return []}getBoundingClientRect(){return {left:0,top:0,right:600,bottom:340,width:600,height:340}}
}
const html=new Element('html'),body=new Element('body',html);
const wrapper=new Element('div',body);wrapper.className='jwplayer jw-flag-captions-enabled';
const video=new Element('video',wrapper),caption=new Element('div',wrapper);
const shadowHost=new Element('media-player',body),shadow={host:shadowHost,parentNode:null};
const shadowVideo=new Element('video',shadow),frameHolder=new Element('div',body),iframe=new Element('iframe',frameHolder);
const late=new Element('div',body);late.style.setProperty('display','flex');
let media=[video,shadowVideo,iframe,shadowHost];
let candidates=[html,body,wrapper,video,caption,shadowHost,frameHolder,late];
const ctx={document:{documentElement:html,body},Set,Map,vidSrcTakeoverActiveV3224:true,vidSrcProviderCaptionsAllowedV3231:false,activeVideo:video,
 hookVidSrcTracksV3225(){},getComputedStyle:()=>({display:'block',visibility:'visible',fontSize:'16px'}),
 vidSrcDeepQueryAllV3226(selector){if(selector==='video')return media.filter(x=>x.tagName==='VIDEO');if(selector==='div,span,p')return [];if(selector==='video,iframe,object,embed,media-player')return media;return candidates;}};
vm.createContext(ctx);
const helperStart=source.indexOf('  const vidSrcCaptionStylesV3250');
if(helperStart>=0)vm.runInContext(source.slice(helperStart,source.indexOf('  function scrubVidSrcProviderCaptionsV3224()',helperStart)),ctx);
vm.runInContext(source.slice(source.indexOf('  function scrubVidSrcProviderCaptionsV3224()'),source.indexOf('  function scrubVidSrcProviderUiV3234()')),ctx);
ctx.scrubVidSrcProviderCaptionsV3224();
if(process.env.EXPECT_LEGACY_BUG){
 assert.equal(wrapper.style.getPropertyValue('display'),'none');
 console.log('Reproduced legacy defect: caption state class hides the video container.');process.exit(0);
}
for(const el of [html,body,wrapper,video,shadowHost,frameHolder])assert.notEqual(el.style.getPropertyValue('display'),'none','media ancestor must stay visible');
assert.equal(caption.style.getPropertyValue('display'),'none','actual caption overlay must be hidden');
assert.equal(late.style.getPropertyValue('display'),'none');
media.push(new Element('video',late));ctx.scrubVidSrcProviderCaptionsV3224();
assert.equal(late.style.getPropertyValue('display'),'flex','restore original style when a late video is inserted');
assert.equal(late.style.getPropertyValue('visibility'),'');
assert.equal(late.style.getPropertyValue('opacity'),'');
assert(!/['"]html\.[^\n]*\[class\*=["'](?:caption|subtitle|cue)/.test(source),'no unguarded substring CSS rules');
vm.runInContext('vidSrcCaptionStylesV3250.forEach((_,el)=>restoreVidSrcCaptionNodeV3250(el));',ctx);
assert.equal(caption.style.getPropertyValue('display'),'','caption styles restored on release');
console.log('Media/caption separation, iframe and shadow ancestors, late media insertion, restoration: PASS');
const site=fs.readFileSync(path.join(assets,'site_bridge.js'),'utf8');
const code=site.slice(site.indexOf('  function installVidSrcSubscriberV3250()'),site.indexOf('  function nowPerf()'));
for(const owner of [false,true]){
 let calls=0,prepared=0,frame={isConnected:true};const delayed=[];
 const c={isLoggedIn:owner,window:{openVidSrcForSubscriberV369(){calls++;return 'original-result'}},document:{querySelector:()=>frame},setTimeout:f=>delayed.push(f),isVidSrcFrameActiveV328:()=>true,syncVidSrcGuardV328(){},installVidSrcPseudoFullscreenV3216(){},prepareVidSrcSubHubUiV3216(){prepared++},ensureVidSrcTakeoverV3222(){}};
 vm.createContext(c);vm.runInContext(code,c);c.installVidSrcSubscriberV3250();const wrapped=c.window.openVidSrcForSubscriberV369;c.installVidSrcSubscriberV3250();assert.equal(c.window.openVidSrcForSubscriberV369,wrapped);
 assert.equal(wrapped(),'original-result');assert.equal(calls,1);assert.equal(prepared,1);
 frame.isConnected=false;delayed.forEach(f=>f());assert.equal(prepared,1,'closed session must not be hydrated');
 console.log((owner?'Owner':'Subscriber')+' entry preserves original opener and stops stale callbacks: PASS');
}
