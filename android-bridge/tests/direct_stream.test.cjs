const {readFileSync}=require('node:fs');
const vm=require('node:vm');
const assert=require('node:assert/strict');
const source=readFileSync('app/src/main/assets/direct_stream.js','utf8');

let inserted=null, opens=[], allowed=true, playerOpen=false;
const reference={
  parentElement:{},
  className:'watch-pill',
  insertAdjacentElement(_,b){inserted=b;}
};
const created=[];
const document={
  getElementById(id){
    if(id==='vidsrcOwnerTrialV355') return reference;
    if(id==='subhub-direct-stream-button') return null;
    return null;
  },
  querySelector(sel){
    if(sel.includes('#videoPlayerModal')) return playerOpen?{}:null;
    return null;
  },
  createElement(){
    const el={
      style:{cssText:''},
      className:'',
      textContent:'',
      addEventListener(_,f){this.click=f;}
    };
    created.push(el);
    return el;
  }
};

const originalSources=[
  {adminKey:'onlyflix',url:'https://example.test/embed/1'},
  {adminKey:'r2',label:'VIP',url:'https://pub.example.r2.dev/movie.mp4',poster:'https://img.test/poster.jpg'}
];
const context={
  console,Date,Math,Number,JSON,
  setInterval(){return 1;},
  clearInterval(){},
  setTimeout(fn){fn();return 1;},
  currentMovie:{id:'movie-one'},
  isLoggedIn:true,
  document,
  checkOwnerAccess:async()=>allowed,
  _vidfastMovieIdV302:()=>123,
  _watchSources:originalSources,
  _watchSelectedIdx:1,
  stopInlinePlayersV265(){},
  closeEmbedPlayer(){},
  playSelectedWatchSource(){
    playerOpen=true;
    const selected=context._watchSources[context._watchSelectedIdx];
    context.lastPlayed=selected;
  },
  SubHubAndroidBridge:{
    openDirectStream(t,s){opens.push(JSON.parse(s));}
  }
};
context.window=context;
vm.createContext(context);
vm.runInContext(source,context);

(async()=>{
  assert(inserted,'button installed');
  await inserted.click();
  assert.equal(opens.length,1);
  assert.equal(opens[0].mode,'capture_to_r2');
  assert.equal(opens[0].movieId,'123');

  const session=opens[0].session;
  const ok=context.__subhubDirectCaptured(session,'https://cdn.test/master.m3u8?sig=1');
  assert.equal(ok,true);
  assert(context.lastPlayed,'R2 opener called');
  assert.equal(context.lastPlayed.adminKey,'r2','preserve R2 route');
  assert.equal(context.lastPlayed.url,'https://cdn.test/master.m3u8?sig=1','replace only media URL');
  assert.equal(context.lastPlayed.poster,'https://img.test/poster.jpg','do not touch poster');
  assert.equal(originalSources[1].url,'https://pub.example.r2.dev/movie.mp4','original R2 source remains unchanged');

  allowed=false;
  context.__subhubDirectClosed(session);
  console.log('direct stream capture-to-R2 tests OK');
})().catch(e=>{console.error(e);process.exitCode=1;});
