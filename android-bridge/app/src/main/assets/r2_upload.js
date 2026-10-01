(function(){
  'use strict';

  /*
   * SubHub Android 322.3.48 — native background R2 upload.
   *
   * Preferred path:
   *   Web page chooses the file -> Android remembers its content:// Uri ->
   *   R2UploadService owns the multipart upload as a foreground dataSync
   *   service. WebView can pause while another app is in front or the screen is
   *   off. Completed multipart parts are persisted by Android.
   *
   * Fallback path:
   *   If the native bridge is unavailable, keep the 322.3.47 resumable
   *   JavaScript uploader. This is useful for older app builds.
   */
  if (window.__subhubAndroidR2BackgroundV348) return;
  window.__subhubAndroidR2BackgroundV348 = true;

  var STORE_KEY = 'subhub_android_r2_resume_v347';
  var TTL_MS = 6 * 24 * 60 * 60 * 1000;
  var MAX_STATES = 8;
  var CHUNK_FALLBACK = 50 * 1024 * 1024;
  var qlBusy = false;
  var seriesBusy = false;
  var nativeMonitor = null;
  var lastAuthRefreshAt = 0;

  function pageReady(){
    return typeof artaqCall === 'function' &&
      typeof qlPutPart === 'function' &&
      typeof dlHumanSize === 'function';
  }

  function nativeBridge(){
    try{
      var b = window.SubHubR2Bridge;
      if(b && typeof b.isAvailable === 'function' && b.isAvailable()) return b;
    }catch(_){}
    return null;
  }

  async function ownerToken(force){
    var u = null;
    try{
      u = (typeof firebase !== 'undefined' && firebase.auth) ? firebase.auth().currentUser : null;
    }catch(_){}
    if(!u || typeof u.getIdToken !== 'function'){
      throw new Error('لازم تسجّل الدخول بحساب المالك');
    }
    return u.getIdToken(!!force);
  }

  function parseState(raw){
    try{
      if(!raw) return {status:'none'};
      return typeof raw === 'string' ? (JSON.parse(raw) || {status:'none'}) : raw;
    }catch(_){
      return {status:'none'};
    }
  }

  function contextObject(extra){
    var c = extra || {};
    try{
      if(typeof currentMovie !== 'undefined' && currentMovie && currentMovie.id){
        c.movieId = String(currentMovie.id);
      }
    }catch(_){}
    return c;
  }

  function nativeUi(ui, state){
    if(typeof ui !== 'function' || !state) return;
    var pct = Number(state.percent || 0);
    var txt = String(state.message || '');
    if(state.status === 'queued' && !txt) txt = 'جاري تجهيز الرفع في الخلفية…';
    if(state.status === 'uploading' && !txt) txt = Math.round(pct) + '% · الرفع مستمر في الخلفية';
    if(state.status === 'waiting' && !txt) txt = 'بانتظار عودة الاتصال…';
    if(state.status === 'auth_required' && !txt) txt = 'افتح SubHub لتجديد جلسة المالك';
    ui(pct, txt);
  }

  async function maybeRefreshNativeAuth(bridge, jobId, state){
    if(!bridge || !state || state.status !== 'auth_required') return false;
    var now = Date.now();
    if(now - lastAuthRefreshAt < 8000) return false;
    lastAuthRefreshAt = now;
    try{
      var token = await ownerToken(true);
      bridge.refreshAuth(String(jobId || ''), token);
      return true;
    }catch(_){
      return false;
    }
  }

  function currentContextMatches(state){
    try{
      var raw = state && state.context;
      var c = typeof raw === 'string' ? JSON.parse(raw || '{}') : (raw || {});
      if(!c.movieId) return true;
      return typeof currentMovie !== 'undefined' && currentMovie &&
        String(currentMovie.id || '') === String(c.movieId);
    }catch(_){
      return true;
    }
  }

  function applyNativeResult(state){
    if(!state || !state.url || !currentContextMatches(state)) return false;
    var mode = String(state.mode || 'quick');

    try{
      if(mode === 'quick'){
        var q = document.getElementById('quickLinkUrlInput');
        if(q) q.value = state.url;
        if(typeof qlUpUI === 'function'){
          qlUpUI(100, '✅ تم الرفع بالكامل في الخلفية — الرابط جاهز، اضغط حفظ');
        }
        return !!q;
      }

      if(mode === 'series-video'){
        var rv = document.getElementById('serEpR2');
        if(rv) rv.value = state.url;
        if(typeof _seriesUploadUiV290 === 'function') _seriesUploadUiV290(100, '✅ اكتمل الرفع إلى R2 — اضغط حفظ');
        else if(typeof _seriesUploadUi === 'function') _seriesUploadUi(100, '✅ اكتمل الرفع إلى R2 — اضغط حفظ');
        return !!rv;
      }

      if(mode === 'series-subtitle'){
        var rs = document.getElementById('serEpSub');
        if(rs) rs.value = state.url;
        if(typeof _seriesUploadUiV290 === 'function') _seriesUploadUiV290(100, '✅ اكتمل الرفع إلى R2 — اضغط حفظ');
        else if(typeof _seriesUploadUi === 'function') _seriesUploadUi(100, '✅ اكتمل الرفع إلى R2 — اضغط حفظ');
        return !!rs;
      }

      if(mode === 'series-trailer'){
        var tr = document.getElementById('seriesTrailerUrlV264');
        if(tr) tr.value = state.url;
        if(typeof _seriesTrailerUploadUiV291 === 'function'){
          _seriesTrailerUploadUiV291(100, '✅ اكتمل الرفع إلى R2 — اضغط حفظ المقطع');
        }
        return !!tr;
      }
    }catch(_){}
    return false;
  }

  function monitorNative(jobId, ui){
    var bridge = nativeBridge();
    if(!bridge) return Promise.reject(new Error('خدمة الرفع في الخلفية غير متاحة'));

    return new Promise(function(resolve, reject){
      var stopped = false;

      async function tick(){
        if(stopped) return;
        var state = parseState(bridge.getState(String(jobId || '')));
        nativeUi(ui, state);

        if(state.status === 'auth_required'){
          await maybeRefreshNativeAuth(bridge, jobId, state);
        }

        if(state.status === 'done'){
          stopped = true;
          if(nativeMonitor) clearTimeout(nativeMonitor);
          applyNativeResult(state);
          resolve(state.url || '');
          return;
        }

        if(state.status === 'error'){
          stopped = true;
          reject(new Error(state.message || 'تعذّر إكمال الرفع'));
          return;
        }

        if(state.status === 'cancelled'){
          stopped = true;
          reject(new Error('تم إيقاف الرفع'));
          return;
        }

        var delay = document.hidden ? 2200 : 700;
        nativeMonitor = setTimeout(tick, delay);
      }

      tick();
    });
  }

  async function startNativeUpload(file, folder, ui, mode, context){
    var bridge = nativeBridge();
    if(!bridge) throw new Error('خدمة الرفع في الخلفية غير متاحة');

    var token = await ownerToken(true);
    var jobId = String(bridge.startUpload(
      token,
      String(folder || ''),
      String(file && file.name || ''),
      Number(file && file.size || 0),
      String(mode || 'quick'),
      JSON.stringify(contextObject(context || {}))
    ) || '');

    if(jobId.indexOf('ERR:') === 0) throw new Error(jobId.slice(4));
    if(!jobId) throw new Error('تعذّر بدء خدمة الرفع');

    if(typeof ui === 'function'){
      ui(0, '⬆️ بدأ الرفع في الخلفية — يمكنك الآن الخروج من SubHub واستخدام تطبيق آخر');
    }

    return monitorNative(jobId, ui);
  }

  function resumeNativeCurrent(){
    var bridge = nativeBridge();
    if(!bridge) return;
    try{
      var state = parseState(bridge.getCurrentState());
      if(!state || !state.jobId || state.status === 'none') return;

      if(state.status === 'done'){
        applyNativeResult(state);
        return;
      }

      if(['queued','uploading','waiting','completing','auth_required'].indexOf(state.status) < 0) return;

      var mode = String(state.mode || 'quick');
      var ui = null;
      if(mode === 'quick' && typeof qlUpUI === 'function') ui = qlUpUI;
      else if((mode === 'series-video' || mode === 'series-subtitle')){
        if(typeof _seriesUploadUiV290 === 'function') ui = _seriesUploadUiV290;
        else if(typeof _seriesUploadUi === 'function') ui = _seriesUploadUi;
      }else if(mode === 'series-trailer' && typeof _seriesTrailerUploadUiV291 === 'function'){
        ui = _seriesTrailerUploadUiV291;
      }

      monitorNative(state.jobId, ui).catch(function(){});
    }catch(_){}
  }

  function chunkSize(){
    try{
      if(typeof QL_CHUNK !== 'undefined' && Number(QL_CHUNK) > 0) return Number(QL_CHUNK);
    }catch(_){}
    return CHUNK_FALLBACK;
  }

  function readStore(){
    var all = {};
    try{ all = JSON.parse(localStorage.getItem(STORE_KEY) || '{}') || {}; }catch(_){ all = {}; }
    var now = Date.now(), rows = [];
    Object.keys(all).forEach(function(k){
      var x = all[k] || {};
      if(!x.updatedAt || now - Number(x.updatedAt) > TTL_MS) delete all[k];
      else rows.push({key:k, at:Number(x.updatedAt) || 0});
    });
    rows.sort(function(a,b){ return b.at - a.at; });
    rows.slice(MAX_STATES).forEach(function(r){ delete all[r.key]; });
    try{ localStorage.setItem(STORE_KEY, JSON.stringify(all)); }catch(_){}
    return all;
  }

  function writeStore(all){
    try{ localStorage.setItem(STORE_KEY, JSON.stringify(all || {})); }catch(_){}
  }

  function fingerprint(file, folder){
    return [
      String(folder || ''),
      String(file && file.name || ''),
      String(file && file.size || 0),
      String(file && file.lastModified || 0),
      String(file && file.type || '')
    ].join('|');
  }

  function partBytes(partNumber, total, chunk){
    var from = (partNumber - 1) * chunk;
    return Math.max(0, Math.min(from + chunk, total) - from);
  }

  function completedBytes(parts, total, chunk){
    var n = 0;
    (parts || []).forEach(function(p){
      var pn = Number(p && p.partNumber);
      if(pn > 0 && p && p.etag) n += partBytes(pn, total, chunk);
    });
    return Math.min(total, n);
  }

  function sleep(ms){ return new Promise(function(resolve){ setTimeout(resolve, ms); }); }

  async function waitOnline(ui, pct){
    if(navigator.onLine !== false) return;
    if(typeof ui === 'function'){
      ui(pct, '⏸️ انقطع الإنترنت — الأجزاء المكتملة محفوظة ولن تُعاد.');
    }
    await new Promise(function(resolve){
      var done = function(){ window.removeEventListener('online', done); resolve(); };
      window.addEventListener('online', done, {once:true});
    });
    await sleep(700);
  }

  async function retry(work, ui, pct, label){
    var lastErr = null;
    for(var attempt = 1; attempt <= 5; attempt++){
      await waitOnline(ui, pct);
      try{ return await work(attempt); }
      catch(e){
        lastErr = e;
        if(attempt >= 5) break;
        if(typeof ui === 'function'){
          ui(pct, '⚠️ ' + label + ' تعذّر مؤقتاً — إعادة المحاولة ' + (attempt + 1) + ' من ٥…');
        }
        await sleep(Math.min(6000, 700 * Math.pow(2, attempt - 1)));
      }
    }
    throw lastErr || new Error('تعذّر إكمال الرفع');
  }

  function publicUrlForKey(key){
    try{
      if(typeof R2_PUBLIC_BASE !== 'undefined' && R2_PUBLIC_BASE){
        return String(R2_PUBLIC_BASE).replace(/\/+$/, '') + '/' +
          String(key).split('/').map(encodeURIComponent).join('/');
      }
    }catch(_){}
    throw new Error('تعذّر تكوين رابط R2 النهائي');
  }

  async function fallbackUpload(file, folder, ui){
    if(!file) throw new Error('لم يتم اختيار ملف');
    if(!pageReady()) throw new Error('أداة رفع R2 لم تجهز بعد — أعد فتح الصفحة');

    folder = String(folder || '').trim().replace(/^\/+|\/+$/g, '');
    var chunk = chunkSize(), total = Number(file.size || 0);
    var count = Math.max(1, Math.ceil(total / chunk));
    var fp = fingerprint(file, folder), all = readStore(), state = all[fp] || null;

    if(state && (
      Number(state.size) !== total ||
      Number(state.lastModified || 0) !== Number(file.lastModified || 0) ||
      Number(state.chunkSize) !== chunk || !state.key || !state.uploadId
    )){
      delete all[fp]; writeStore(all); state = null;
    }

    if(!state){
      if(typeof ui === 'function') ui(0, 'جاري بدء رفع جديد…');
      var started = await retry(function(){
        return artaqCall('/api/create-multipart', {filename:file.name, folder:folder});
      }, ui, 0, 'بدء الرفع');
      if(!started || !started.key || !started.uploadId) throw new Error('رد غير متوقع من خادم الرفع');
      state = {
        key:started.key, uploadId:started.uploadId, folder:folder,
        filename:file.name, size:total, lastModified:Number(file.lastModified || 0),
        type:String(file.type || ''), chunkSize:chunk, parts:[],
        createdAt:Date.now(), updatedAt:Date.now()
      };
      all[fp] = state; writeStore(all);
    }

    var partMap = {};
    (state.parts || []).forEach(function(p){
      var pn = Number(p && p.partNumber);
      if(pn > 0 && p && p.etag) partMap[pn] = {partNumber:pn, etag:p.etag};
    });

    var doneBytes = completedBytes(Object.keys(partMap).map(function(k){ return partMap[k]; }), total, chunk);
    var startedAt = Date.now();

    for(var n = 1; n <= count; n++){
      if(partMap[n]) continue;
      var from = (n - 1) * chunk, to = Math.min(from + chunk, total);
      var pn = n, sentBefore = doneBytes, partBlob = file.slice(from, to);
      var etag = await retry(async function(){
        var got = await artaqCall('/api/part-url', {
          key:state.key, uploadId:state.uploadId, partNumber:pn
        });
        if(!got || !got.url) throw new Error('لم يصل رابط رفع الجزء');
        return qlPutPart(got.url, partBlob, function(loaded){
          var sent = Math.min(total, sentBefore + loaded);
          var secs = Math.max(0.5, (Date.now() - startedAt) / 1000);
          if(typeof ui === 'function'){
            ui(total ? sent / total * 100 : 0,
              Math.round(total ? sent / total * 100 : 0) + '% · جزء ' + pn + ' من ' + count +
              ' · ' + dlHumanSize(sent) + ' / ' + dlHumanSize(total) +
              ' · ' + dlHumanSize(sent / secs) + '/ث');
          }
        });
      }, ui, total ? doneBytes / total * 100 : 0, 'رفع الجزء ' + pn);

      partMap[pn] = {partNumber:pn, etag:etag};
      doneBytes = to;
      state.parts = Object.keys(partMap).map(function(k){ return partMap[k]; })
        .sort(function(a,b){ return a.partNumber - b.partNumber; });
      state.updatedAt = Date.now();
      all = readStore(); all[fp] = state; writeStore(all);
    }

    var finalParts = Object.keys(partMap).map(function(k){ return partMap[k]; })
      .sort(function(a,b){ return a.partNumber - b.partNumber; });

    if(typeof ui === 'function') ui(100, 'جاري تجميع الأجزاء وإنهاء الرفع…');
    await retry(function(){
      return artaqCall('/api/complete-multipart', {
        key:state.key, uploadId:state.uploadId, parts:finalParts
      });
    }, ui, 100, 'إنهاء الرفع');

    all = readStore(); delete all[fp]; writeStore(all);
    return publicUrlForKey(state.key);
  }

  async function upload(file, folder, ui, mode, context){
    if(nativeBridge()){
      return startNativeUpload(file, folder, ui, mode, context);
    }
    return fallbackUpload(file, folder, ui);
  }

  function installQuickLink(){
    if(typeof window.qlStartUpload !== 'function') return false;
    window.qlStartUpload = async function(file){
      if(!file || qlBusy) return;
      qlBusy = true;
      var btn = document.getElementById('qlUpBtn');
      if(btn){ btn.disabled = true; btn.textContent = '⏳ جاري الرفع…'; }
      try{
        var folder = '';
        try{ folder = typeof _qlUpFolder !== 'undefined' ? _qlUpFolder : ''; }catch(_){}
        var url = await upload(file, folder, function(pct, txt){
          try{ qlUpUI(pct, txt); }catch(_){}
        }, 'quick', {});
        var urlEl = document.getElementById('quickLinkUrlInput');
        if(urlEl) urlEl.value = url;
        try{ qlUpUI(100, '✅ تم الرفع بالكامل — الرابط جاهز، اضغط حفظ'); }catch(_){}
      }catch(e){
        try{ qlUpUI(0, '❌ ' + ((e && e.message) ? e.message : 'فشل الرفع')); }catch(_){}
      }finally{
        qlBusy = false;
        if(btn){ btn.disabled = false; btn.textContent = '⬆️ ارفع ملفاً إلى R2'; }
      }
    };
    return true;
  }

  function installSeries(){
    if(typeof window.seriesStartUpload === 'function'){
      window.seriesStartUpload = async function(file, kind){
        if(!file || seriesBusy) return;
        seriesBusy = true;
        var sn = 1, en = 1, mid = 'series';
        try{ sn = Number(_seriesEditingSeason) || 1; }catch(_){}
        try{
          en = Math.max(1, Number((document.getElementById('serEpNumber') || {}).value) ||
            Number(_seriesEditingEpisode) || 1);
        }catch(_){}
        try{ mid = String((currentMovie && currentMovie.id) || 'series'); }catch(_){}
        var folder = mid.replace(/^\/+|\/+$/g, '') +
          '/S' + String(sn).padStart(2,'0') + '/E' + String(en).padStart(2,'0');
        var mode = kind === 'subtitle' ? 'series-subtitle' : 'series-video';

        try{
          var url = await upload(file, folder, function(pct, txt){
            try{
              if(typeof _seriesUploadUiV290 === 'function') _seriesUploadUiV290(pct, txt);
              else if(typeof _seriesUploadUi === 'function') _seriesUploadUi(pct, txt);
            }catch(_){}
          }, mode, {season:sn, episode:en});

          var target = document.getElementById(kind === 'subtitle' ? 'serEpSub' : 'serEpR2');
          if(target) target.value = url;
        }catch(e){
          var msg = e && e.message ? e.message : 'فشل الرفع';
          try{
            if(typeof _seriesUploadUiV290 === 'function') _seriesUploadUiV290(0, '❌ ' + msg);
            else if(typeof _seriesUploadUi === 'function') _seriesUploadUi(0, '❌ ' + msg);
          }catch(_){}
        }finally{
          seriesBusy = false;
          try{ if(typeof _seriesUploadBusy !== 'undefined') _seriesUploadBusy = false; }catch(_){}
        }
      };
    }

    if(typeof window.seriesStartTrailerUploadV291 === 'function'){
      window.seriesStartTrailerUploadV291 = async function(file){
        if(!file || seriesBusy) return;
        try{
          if(typeof checkOwnerAccess === 'function' && !(await checkOwnerAccess())) return;
        }catch(_){ return; }

        seriesBusy = true;
        var mid = 'series';
        try{ mid = String((currentMovie && currentMovie.id) || 'series'); }catch(_){}
        var folder = mid.replace(/^\/+|\/+$/g, '') + '/trailer';

        try{
          var url = await upload(file, folder, function(pct, txt){
            try{ if(typeof _seriesTrailerUploadUiV291 === 'function') _seriesTrailerUploadUiV291(pct, txt); }catch(_){}
          }, 'series-trailer', {});

          var input = document.getElementById('seriesTrailerUrlV264');
          if(input) input.value = url;
        }catch(e){
          try{
            if(typeof _seriesTrailerUploadUiV291 === 'function'){
              _seriesTrailerUploadUiV291(0, '❌ ' + ((e && e.message) ? e.message : 'فشل الرفع'));
            }
          }catch(_){}
        }finally{
          seriesBusy = false;
          try{ if(typeof _seriesUploadBusy !== 'undefined') _seriesUploadBusy = false; }catch(_){}
        }
      };
    }
  }

  function install(){
    if(!pageReady()) return false;
    var ok = installQuickLink();
    installSeries();
    setTimeout(resumeNativeCurrent, 150);
    return ok;
  }

  if(!install()){
    var tries = 0;
    var timer = setInterval(function(){
      tries++;
      if(install() || tries >= 20) clearInterval(timer);
    }, 250);
  }

  document.addEventListener('visibilitychange', function(){
    if(!document.hidden) setTimeout(resumeNativeCurrent, 120);
  });
  window.addEventListener('focus', function(){ setTimeout(resumeNativeCurrent, 120); });
})();
