(function(){
  'use strict';

  /*
   * SubHub Android 322.3.47 — resumable R2 multipart upload.
   *
   * This runs only inside the Android WebView after the live SubHub page has
   * finished loading. The website keeps its existing uploader; the app wraps
   * the same Worker endpoints with durable per-part state.
   *
   * Temporary network loss:
   *   - keep the multipart upload open;
   *   - remember every completed part (partNumber + ETag);
   *   - wait for connectivity and retry only the current missing part.
   *
   * App/page restart:
   *   - localStorage keeps the uploadId and completed parts;
   *   - after the owner selects the same file again, upload continues from
   *     the first missing part instead of starting from zero.
   *
   * No partially uploaded file is written into SubHub's movie data: the final
   * URL is filled only after complete-multipart succeeds.
   */
  if (window.__subhubAndroidR2ResumeV347) return;
  window.__subhubAndroidR2ResumeV347 = true;

  var STORE_KEY = 'subhub_android_r2_resume_v347';
  var TTL_MS = 6 * 24 * 60 * 60 * 1000;
  var MAX_STATES = 8;
  var CHUNK_FALLBACK = 50 * 1024 * 1024;
  var qlBusy = false;
  var seriesBusy = false;

  function pageReady(){
    return typeof artaqCall === 'function' &&
      typeof qlPutPart === 'function' &&
      typeof dlHumanSize === 'function';
  }

  function chunkSize(){
    try{
      if(typeof QL_CHUNK !== 'undefined' && Number(QL_CHUNK) > 0) return Number(QL_CHUNK);
    }catch(_){}
    return CHUNK_FALLBACK;
  }

  function readStore(){
    var all = {};
    try{
      all = JSON.parse(localStorage.getItem(STORE_KEY) || '{}') || {};
    }catch(_){
      all = {};
    }

    var now = Date.now();
    var rows = [];
    Object.keys(all).forEach(function(k){
      var x = all[k] || {};
      if(!x.updatedAt || now - Number(x.updatedAt) > TTL_MS){
        delete all[k];
      }else{
        rows.push({key:k, at:Number(x.updatedAt) || 0});
      }
    });

    rows.sort(function(a,b){ return b.at - a.at; });
    rows.slice(MAX_STATES).forEach(function(r){ delete all[r.key]; });

    writeStore(all);
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
    var to = Math.min(from + chunk, total);
    return Math.max(0, to - from);
  }

  function completedBytes(parts, total, chunk){
    var n = 0;
    (parts || []).forEach(function(p){
      var pn = Number(p && p.partNumber);
      if(pn > 0 && p && p.etag) n += partBytes(pn, total, chunk);
    });
    return Math.min(total, n);
  }

  function sleep(ms){
    return new Promise(function(resolve){ setTimeout(resolve, ms); });
  }

  async function waitOnline(ui, pct){
    if(navigator.onLine !== false) return;
    if(typeof ui === 'function'){
      ui(pct, '⏸️ انقطع الإنترنت — بانتظار عودة الاتصال. الأجزاء المكتملة محفوظة ولن تُعاد.');
    }
    await new Promise(function(resolve){
      var done = function(){
        window.removeEventListener('online', done);
        resolve();
      };
      window.addEventListener('online', done, {once:true});
    });
    await sleep(700);
  }

  async function retry(work, ui, pct, label){
    var lastErr = null;
    for(var attempt = 1; attempt <= 5; attempt++){
      await waitOnline(ui, pct);
      try{
        return await work(attempt);
      }catch(e){
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

  async function upload(file, folder, ui){
    if(!file) throw new Error('لم يتم اختيار ملف');
    if(!pageReady()) throw new Error('أداة رفع R2 لم تجهز بعد — أعد فتح الصفحة');

    folder = String(folder || '').trim().replace(/^\/+|\/+$/g, '');
    var chunk = chunkSize();
    var total = Number(file.size || 0);
    var count = Math.max(1, Math.ceil(total / chunk));
    var fp = fingerprint(file, folder);
    var all = readStore();
    var state = all[fp] || null;

    if(state && (
      Number(state.size) !== total ||
      Number(state.lastModified || 0) !== Number(file.lastModified || 0) ||
      Number(state.chunkSize) !== chunk ||
      !state.key ||
      !state.uploadId
    )){
      delete all[fp];
      writeStore(all);
      state = null;
    }

    if(!state){
      if(typeof ui === 'function') ui(0, 'جاري بدء رفع جديد…');
      var started = await retry(function(){
        return artaqCall('/api/create-multipart', {
          filename: file.name,
          folder: folder
        });
      }, ui, 0, 'بدء الرفع');

      if(!started || !started.key || !started.uploadId){
        throw new Error('رد غير متوقع من خادم الرفع');
      }

      state = {
        key: started.key,
        uploadId: started.uploadId,
        folder: folder,
        filename: file.name,
        size: total,
        lastModified: Number(file.lastModified || 0),
        type: String(file.type || ''),
        chunkSize: chunk,
        parts: [],
        createdAt: Date.now(),
        updatedAt: Date.now()
      };
      all[fp] = state;
      writeStore(all);
    }else{
      var restored = completedBytes(state.parts, total, chunk);
      if(typeof ui === 'function'){
        ui(total ? restored / total * 100 : 0,
          '↩️ استكمال رفع سابق — تم حفظ ' +
          dlHumanSize(restored) + ' من ' + dlHumanSize(total));
      }
    }

    var partMap = {};
    (state.parts || []).forEach(function(p){
      var pn = Number(p && p.partNumber);
      if(pn > 0 && p && p.etag){
        partMap[pn] = {partNumber:pn, etag:p.etag};
      }
    });

    var doneBytes = completedBytes(Object.keys(partMap).map(function(k){ return partMap[k]; }), total, chunk);
    var startedAt = Date.now();

    for(var n = 1; n <= count; n++){
      if(partMap[n]) continue;

      var from = (n - 1) * chunk;
      var to = Math.min(from + chunk, total);
      var blob = file.slice(from, to);
      var basePct = total ? doneBytes / total * 100 : 0;

      /* Capture loop values because retry() may wait for connectivity. */
      var pn = n;
      var sentBefore = doneBytes;
      var partBlob = blob;

      var etag = await retry(async function(){
        var got = await artaqCall('/api/part-url', {
          key: state.key,
          uploadId: state.uploadId,
          partNumber: pn
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
      }, ui, basePct, 'رفع الجزء ' + pn);

      partMap[pn] = {partNumber:pn, etag:etag};
      doneBytes = to;
      state.parts = Object.keys(partMap)
        .map(function(k){ return partMap[k]; })
        .sort(function(a,b){ return a.partNumber - b.partNumber; });
      state.updatedAt = Date.now();

      all = readStore();
      all[fp] = state;
      writeStore(all);
    }

    if(typeof ui === 'function') ui(100, 'جاري تجميع الأجزاء وإنهاء الرفع…');

    var finalParts = Object.keys(partMap)
      .map(function(k){ return partMap[k]; })
      .sort(function(a,b){ return a.partNumber - b.partNumber; });

    await retry(function(){
      return artaqCall('/api/complete-multipart', {
        key: state.key,
        uploadId: state.uploadId,
        parts: finalParts
      });
    }, ui, 100, 'إنهاء الرفع');

    all = readStore();
    delete all[fp];
    writeStore(all);

    return publicUrlForKey(state.key);
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
        });

        var urlEl = document.getElementById('quickLinkUrlInput');
        if(urlEl) urlEl.value = url;
        try{ qlUpUI(100, '✅ تم الرفع بالكامل (' + dlHumanSize(file.size) + ') — الرابط جاهز، اضغط حفظ'); }catch(_){}
      }catch(e){
        var msg = e && e.message ? e.message : 'فشل الرفع';
        try{
          qlUpUI(0, '❌ ' + msg + ' — اختر نفس الملف لاحقاً وسيكمل من آخر جزء.');
        }catch(_){}
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
          '/S' + String(sn).padStart(2,'0') +
          '/E' + String(en).padStart(2,'0');

        try{
          var url = await upload(file, folder, function(pct, txt){
            try{
              if(typeof _seriesUploadUiV290 === 'function') _seriesUploadUiV290(pct, txt);
              else if(typeof _seriesUploadUi === 'function') _seriesUploadUi(pct, txt);
            }catch(_){}
          });

          var target = document.getElementById(kind === 'subtitle' ? 'serEpSub' : 'serEpR2');
          if(target) target.value = url;
          try{
            if(typeof _seriesUploadUiV290 === 'function') _seriesUploadUiV290(100, '✅ اكتمل الرفع إلى R2 — اضغط حفظ');
            else if(typeof _seriesUploadUi === 'function') _seriesUploadUi(100, '✅ اكتمل الرفع إلى R2 — اضغط حفظ');
          }catch(_){}
        }catch(e){
          var msg = e && e.message ? e.message : 'فشل الرفع';
          try{
            if(typeof _seriesUploadUiV290 === 'function') _seriesUploadUiV290(0, '❌ ' + msg + ' — اختر نفس الملف لاحقاً لاستكماله');
            else if(typeof _seriesUploadUi === 'function') _seriesUploadUi(0, '❌ ' + msg + ' — اختر نفس الملف لاحقاً لاستكماله');
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
          });
          var input = document.getElementById('seriesTrailerUrlV264');
          if(input) input.value = url;
          try{ _seriesTrailerUploadUiV291(100, '✅ اكتمل الرفع إلى R2 — اضغط حفظ المقطع'); }catch(_){}
        }catch(e){
          var msg = e && e.message ? e.message : 'فشل الرفع';
          try{ _seriesTrailerUploadUiV291(0, '❌ ' + msg + ' — اختر نفس الملف لاحقاً لاستكماله'); }catch(_){}
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
    return ok;
  }

  /* onPageFinished normally sees all page functions. A short retry also
     covers slow script execution without keeping a permanent timer. */
  if(!install()){
    var tries = 0;
    var timer = setInterval(function(){
      tries++;
      if(install() || tries >= 20) clearInterval(timer);
    }, 250);
  }

  window.addEventListener('online', function(){
    /* The active upload promise wakes itself; this only refreshes its message. */
  });
})();