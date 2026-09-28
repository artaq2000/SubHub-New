const {readFileSync}=require('node:fs');
const vm=require('node:vm');
const assert=require('node:assert/strict');
const source=readFileSync('app/src/main/assets/direct_stream.js','utf8');

let inserted=null, opens=[], playerOpen=false;
const fakeVideo={
  src:'https://pub.example.r2.dev/movie.mp4',
  currentSrc:'https://pub.example.r2.dev/movie.mp4',
  preload:'',
  paused:false,
  querySelectorAll(){return [];},
  setAttribute(name,value){if(name==='src')this.src=value;},
  pause(){this.paused=true;},
  load(){this.loaded=true;},
  play(){this.played=true;return Promise.resolve();}
};
const modal={querySelector(sel){return sel==='video'?fakeVideo:null;}};
const reference={
  parentElement:{},
  className:'watch-pill',
  insertAdjacentElement(_,b){inserted=b;}
};
const document={
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
const timers=[];
const context={
  console,Date,Math,Number,JSON,
  setInterval(){return 1;},clearInterval(){},
  setTimeout(fn){timers.push(fn);return timers.length;},
  currentMovie:{id:'movie-one'},isLoggedIn:true,document,
  _vidfastMovieIdV302:()=>123,
  _watchSources:sources,_watchSelectedIdx:0,
  stopInlinePlayersV265(){},closeEmbedPlayer(){},
  playSelectedWatchSource(){
    playerOpen=true;
    context.lastPlayed=context._watchSources[context._watchSelectedIdx];
  },
  SubHubAndroidBridge:{openDirectStream(t,s){opens.push(JSON.parse(s));}}
};
context.window=context;
vm.createContext(context);
vm.runInContext(source,context);

(async()=>{
  assert(inserted,'button installed');
  await inserted.click();
  assert.equal(opens.length,1);
  assert.equal(opens[0].mode,'capture_to_r2');
  const session=opens[0].session;
  const ok=context.__subhubDirectCaptured(session,'https://cdn.test/master.m3u8?sig=1');
  assert.equal(ok,true);
  while(timers.length) timers.shift()();

  assert(context.lastPlayed,'R2 opener called');
  assert.equal(context.lastPlayed.adminKey,'r2','real R2 route is opened');
  assert.equal(context.lastPlayed.url,'https://pub.example.r2.dev/movie.mp4','stored R2 source remains unchanged');
  assert.equal(fakeVideo.src,'https://cdn.test/master.m3u8?sig=1','only live video element URL is swapped');
  assert.equal(fakeVideo.loaded,true);
  assert.equal(fakeVideo.played,true);
  console.log('direct stream real-R2 swap tests OK');
})().catch(e=>{console.error(e);process.exitCode=1;});
