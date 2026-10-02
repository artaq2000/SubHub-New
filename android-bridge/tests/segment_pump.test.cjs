// 322.3.92: exclusive/limited subtitles in the native app players arrive as
// 4-second pieces driven by the player clock — never as a full file.
const {readFileSync}=require('node:fs');
const vm=require('node:vm');
const assert=require('node:assert/strict');
const pump=readFileSync('app/src/main/assets/segment_pump.js','utf8');
const direct=readFileSync('app/src/main/assets/direct_stream.js','utf8');

let inserted, opens=[], modes=[], pieces=[], fulls=[], fetched=[], ended=new Set();
const reference={parentElement:{},className:'watch-pill',insertAdjacentElement(_,b){inserted=b;}};
const context={console,Date,Math,Number,JSON,String,Object,Promise,Error,encodeURIComponent,setInterval(){},
 currentMovie:{id:'movie-one'},isLoggedIn:true,
 document:{getElementById(id){return id==='vidsrcOwnerTrialV355'?reference:null;},createElement(){return {style:{},addEventListener(_,f){this.click=f;}};}},
 checkOwnerAccess:async()=>true,_vidfastMovieIdV302:()=>123,
 _buildSubtitleTrackCatalog:()=>[{name:'حصرية',isDefault:true}],
 _loadSubtitleCatalogEntry:async()=>({dynamic:true,access:{mode:'segments',segmentUrl:'https://seg.example/s?sig=abc',limitSeconds:600}}),
 _serverCueList:list=>(list||[]).map(q=>({start:Number(q.s||0),end:Number(q.e||0),text:String(q.t||'')})),
 stopInlinePlayersV265(){},closeEmbedPlayer(){},
 fetch:async url=>{
   fetched.push(url);
   const t=Number(new URL(url).searchParams.get('t'));
   if(ended.has(t)) return {ok:true,json:async()=>({ended:true,message:'انتهت المدة'})};
   return {ok:true,json:async()=>({cues:[{s:t+0.5,e:t+2,t:'سطر '+t}]})};
 },
 SubHubAndroidBridge:{
   openDirectStream(t,s){opens.push(JSON.parse(s));},
   directStreamSubtitles(...a){fulls.push(a);},
   directStreamSegmentMode(...a){modes.push(a);},
   directStreamSegmentCues(...a){pieces.push(a);}
 }
};
context.window=context;vm.createContext(context);
vm.runInContext(pump,context);vm.runInContext(direct,context);
const flush=()=>new Promise(r=>setTimeout(r,0));

(async()=>{
 await inserted.click();
 const session=opens[0].session;
 await context.__subhubDirectSubtitle(session,0);
 assert.equal(fulls.length,0,'segment subtitles must not be sent as a full file');
 assert.equal(modes.length,1,'player switched to segment mode');
 assert.equal(modes[0][1],session);assert.equal(modes[0][2],0);
 assert.match(modes[0][3],/١٠ دقيقة/,'visitor sees the free-minutes notice');

 context.__subhubDirectSegment(session,0,5.2);await flush();await flush();
 assert.equal(fetched[0],'https://seg.example/s?sig=abc&t=4','fetches the 4-second bucket with the signed link');
 assert.equal(pieces.length,1);
 assert.equal(pieces[0][3],4,'answer names its bucket');
 assert.deepEqual(JSON.parse(pieces[0][4]),[{start:4.5,end:6,text:'سطر 4'}]);
 assert(fetched.includes('https://seg.example/s?sig=abc&t=8'),'next bucket is prefetched');

 const before=fetched.length;
 context.__subhubDirectSegment(session,0,8.1);await flush();await flush();
 assert(!fetched.slice(before).includes('https://seg.example/s?sig=abc&t=8'),'prefetched bucket is reused');
 const last=JSON.parse(pieces[pieces.length-1][4]);
 assert.deepEqual(last.map(c=>c.text),['سطر 4','سطر 8','سطر 12'].filter(x=>last.some(c=>c.text===x)));
 assert(last.some(c=>c.text==='سطر 8'));

 const n=fetched.length;
 context.__subhubDirectSegment(session,0,601);await flush();
 assert.equal(fetched.length,n,'nothing is fetched past the free limit');
 const over=pieces[pieces.length-1];
 assert.equal(over[4],'[]');assert.match(over[5],/انتهت مدة الترجمة المجانية/);
 context.__subhubDirectSegment(session,0,605);await flush();
 assert.equal(pieces[pieces.length-1][5],'','end notice shows once');

 ended.add(100);
 context.__subhubDirectSegment(session,0,101);await flush();await flush();
 assert.equal(pieces[pieces.length-1][4],'[]');assert.equal(pieces[pieces.length-1][5],'انتهت المدة');

 const sent=pieces.length;
 context.__subhubDirectSegment(session,1,20);await flush();await flush();
 assert.equal(pieces.length,sent,'other subtitle index is ignored');
 context.__subhubDirectClosed(session);
 context.__subhubDirectSegment(session,0,40);await flush();await flush();
 assert.equal(pieces.length,sent,'closed session gets nothing');
 console.log('segment pump tests OK');
})().catch(e=>{console.error(e);process.exitCode=1;});
