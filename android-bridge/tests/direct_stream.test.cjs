const {readFileSync}=require('node:fs');
const vm=require('node:vm');
const assert=require('node:assert/strict');
const source=readFileSync('app/src/main/assets/direct_stream.js','utf8');

let inserted=null, opens=[], playerOpen=false, started=[], commands=[], bounds=[];
const timeoutQueue=[];
const intervalFns=[];

function styleObj(){
  const values={};
  return {
    setProperty(k,v){values[k]=v;},
    removeProperty(k){delete values[k];},
    get values(){return values;}
  };
}
function node(parent=null){
  return {
    parentElement:parent,
    style:styleObj(),
    attrs:{},
    hasAttribute(k){return Object.prototype.hasOwnProperty.call(this.attrs,k);},
    getAttribute(k){return this.attrs[k] ?? null;},
    setAttribute(k,v){this.attrs[k]=String(v);},
    removeAttribute(k){delete this.attrs[k];}
  };
}

const modal=node(null);
modal.id='videoPlayerModal';
const stage=node(modal);
const sourceNode=node(null); sourceNode.attrs.src='https://pub.example.r2.dev/movie.mp4';

const listeners={};
const fakeVideo=node(stage);
fakeVideo.attrs.src='https://pub.example.r2.dev/movie.mp4';
fakeVideo.attrs.poster='https://img.test/poster.jpg';
fakeVideo.preload='auto';
fakeVideo.muted=false;
fakeVideo.volume=1;
fakeVideo.currentSrc=fakeVideo.attrs.src;
fakeVideo.querySelectorAll=(sel)=>sel==='source'?[sourceNode]:[];
fakeVideo.closest=(sel)=>sel==='#videoPlayerModal'?modal:null;
fakeVideo.getBoundingClientRect=()=>({left:100,top:80,width:800,height:450});
fakeVideo.addEventListener=(name,fn)=>{(listeners[name]||(listeners[name]=[])).push(fn);};
fakeVideo.dispatchEvent=(ev)=>{(listeners[ev.type]||[]).forEach(fn=>fn.call(fakeVideo,ev));return true;};
fakeVideo.load=()=>{fakeVideo.loaded=true;};
fakeVideo.play=()=>Promise.resolve();
fakeVideo.pause=()=>{fakeVideo.pausedNative=true;};

modal.querySelector=(sel)=>sel==='video'?fakeVideo:null;

const reference={parentElement:{},className:'watch-pill',insertAdjacentElement(_,b){inserted=b;}};
const document={
  documentElement:{clientWidth:1000,clientHeight:600},
  getElementById(id){
    if(id==='vidsrcOwnerTrialV355') return reference;
    if(id==='subhub-direct-stream-button') return null;
    return null;
  },
  querySelector(sel){
    if(sel.includes('#videoPlayerModal')) return playerOpen?modal:null;
    return null;
  },
  createElement(){
    return {style:{cssText:''},className:'',textContent:'',addEventListener(_,f){this.click=f;}};
  }
};

const sources=[
  {adminKey:'onlyflix',url:'https://example.test/embed/1'},
  {adminKey:'r2',label:'VIP',url:'https://pub.example.r2.dev/movie.mp4',poster:'https://img.test/poster.jpg'}
];

const context={
  console,Date,Math,Number,JSON,Object,Promise,
  Event:function(type){this.type=type;},
  innerWidth:1000,innerHeight:600,
  getComputedStyle(){return {objectFit:'contain'};},
  setInterval(fn){intervalFns.push(fn);return intervalFns.length;},
  clearInterval(){},
  setTimeout(fn){timeoutQueue.push(fn);return timeoutQueue.length;},
  currentMovie:{id:'movie-one'},isLoggedIn:true,document,
  _vidfastMovieIdV302:()=>123,
  _watchSources:sources,_watchSelectedIdx:0,
  stopInlinePlayersV265(){},closeEmbedPlayer(){},
  playSelectedWatchSource(){
    playerOpen=true;
    context.lastPlayed=context._watchSources[context._watchSelectedIdx];
  },
  SubHubAndroidBridge:{
    openDirectStream(t,s){opens.push(JSON.parse(s));},
    startDirectR2Playback(t,s,x,y,w,h){started.push({s,x,y,w,h});},
    updateDirectR2Bounds(t,s,x,y,w,h,mode){bounds.push({s,x,y,w,h,mode});},
    directR2Command(t,s,cmd,value){commands.push({s,cmd,value});},
    getDirectR2State(){return JSON.stringify({position:12340,duration:5930000,buffered:18000,playing:true,state:3});}
  }
};
context.window=context;
vm.createContext(context);
vm.runInContext(source,context);

(async()=>{
  assert(inserted,'direct button installed');
  await inserted.click();
  assert.equal(opens.length,1);
  assert.equal(opens[0].mode,'capture_to_r2');

  const session=opens[0].session;
  const ok=context.__subhubDirectCaptured(session);
  assert.equal(ok,true);

  // Run the one-shot attach callback only; intervals are not auto-run in this test.
  while(timeoutQueue.length) timeoutQueue.shift()();

  assert(context.lastPlayed,'real R2 source opener called');
  assert.equal(context.lastPlayed.adminKey,'r2');
  assert.equal(context.lastPlayed.url,'https://pub.example.r2.dev/movie.mp4','stored R2 source is untouched');
  assert.equal(started.length,1,'native backend starts after real R2 UI exists');
  assert.equal(started[0].s,session);
  assert(Math.abs(started[0].x-0.1)<0.0001);
  assert(Math.abs(started[0].w-0.8)<0.0001);
  assert.equal(fakeVideo.hasAttribute('src'),false,'browser R2 media is detached to avoid duplicate R2 bandwidth');

  // Run one native clock/bounds sync iteration.
  const sync=intervalFns[intervalFns.length-1];
  sync();
  assert(bounds.length>=1);
  assert.equal(bounds[bounds.length-1].mode,'fit');
  assert.equal(fakeVideo.currentTime,12.34);
  assert.equal(fakeVideo.paused,false);

  fakeVideo.pause();
  assert(commands.some(x=>x.cmd==='pause'));
  await fakeVideo.play();
  assert(commands.some(x=>x.cmd==='play'));

  console.log('direct stream Media3-behind-R2 facade tests OK');
})().catch(e=>{console.error(e);process.exitCode=1;});
