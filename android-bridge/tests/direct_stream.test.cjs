const {readFileSync}=require('node:fs');
const vm=require('node:vm');
const assert=require('node:assert/strict');
const source=readFileSync('app/src/main/assets/direct_stream.js','utf8');
let inserted, opens=[], replies=[], resolveLoad, allowed=true;
const reference={parentElement:{},className:'watch-pill',insertAdjacentElement(_,b){inserted=b;}};
const context={console,Date,Math,Number,JSON,setInterval(){},currentMovie:{id:'movie-one'},isLoggedIn:true,
 document:{getElementById(id){return id==='vidsrcOwnerTrialV355'?reference:null;},createElement(){return {style:{},addEventListener(_,f){this.click=f;}};}},
 checkOwnerAccess:async()=>allowed,_vidfastMovieIdV302:()=>123,
 _buildSubtitleTrackCatalog:()=>[{name:'Arabic',isDefault:true}],
 _loadSubtitleCatalogEntry:()=>new Promise(r=>resolveLoad=r),
 stopInlinePlayersV265(){},closeEmbedPlayer(){},
 SubHubAndroidBridge:{openDirectStream(t,s){opens.push(JSON.parse(s));},directStreamSubtitles(...args){replies.push(args);}}
};context.window=context;vm.createContext(context);vm.runInContext(source,context);
(async()=>{
 assert(inserted);await inserted.click();assert.equal(opens.length,1);assert.equal(opens[0].defaultIndex,0);
 await inserted.click();assert.equal(opens.length,1,'double tap must not create a second player');
 let pending=context.__subhubDirectSubtitle(opens[0].session,0);
 context.__subhubDirectClosed(opens[0].session);await inserted.click();assert.equal(opens.length,2);
 resolveLoad([{start:0,end:2,text:'old'}]);await pending;assert.equal(replies.length,0,'discard closed-session subtitle');
 pending=context.__subhubDirectSubtitle(opens[1].session,0);resolveLoad([{start:0,end:2,text:'current'}]);await pending;
 assert.equal(replies.length,1);assert.equal(replies[0][1],opens[1].session);
 context.__subhubDirectClosed(opens[1].session);allowed=false;await inserted.click();assert.equal(opens.length,2,'owner check remains enforced');
 console.log('direct stream session tests OK');
})().catch(e=>{console.error(e);process.exitCode=1;});