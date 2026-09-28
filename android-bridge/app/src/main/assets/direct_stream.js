(function () {
  'use strict';
  if (window.__subhubDirectInstalledV3238) return;
  window.__subhubDirectInstalledV3238 = true;

  const token = '__VIDSRC_GUARD_TOKEN__';
  let active = null;
  let opening = false;
  let cleanupTimer = 0;

  function movie() {
    return typeof currentMovie !== 'undefined' ? currentMovie : null;
  }

  function notify(text) {
    try {
      if (typeof showToast === 'function') showToast(text, 'error');
    } catch (_) {}
  }

  function deepClone(value) {
    try { return JSON.parse(JSON.stringify(value)); }
    catch (_) { return null; }
  }

  function mediaCandidates(obj, path, out) {
    if (!obj || typeof obj !== 'object') return out;
    Object.keys(obj).forEach(function (key) {
      const v = obj[key];
      const p = path.concat(key);
      if (typeof v === 'string' && /^https?:\/\//i.test(v)) {
        let score = 1;
        const k = String(key || '').toLowerCase();
        const s = String(v || '').toLowerCase();
        if (/(url|src|link|href|file|video|stream|r2)/.test(k)) score += 7;
        if (/\.(mp4|m4v|webm|mkv|mov)(?:[?#]|$)/i.test(s)) score += 15;
        if (/\.m3u8(?:[?#]|$)/i.test(s)) score += 18;
        if (/r2\.dev|cloudflarestorage|r2\.cloudflarestorage/i.test(s)) score += 9;
        if (/poster|image|thumb|cover|logo|trailer|youtube|youtu\.be/i.test(k + ' ' + s)) score -= 20;
        out.push({path:p, value:v, score:score});
      } else if (v && typeof v === 'object' && p.length < 5) {
        mediaCandidates(v, p, out);
      }
    });
    return out;
  }

  function setPath(obj, path, value) {
    let cur = obj;
    for (let i=0;i<path.length-1;i++) {
      if (!cur || typeof cur !== 'object') return false;
      cur = cur[path[i]];
    }
    if (!cur || typeof cur !== 'object') return false;
    cur[path[path.length-1]] = value;
    return true;
  }

  function sourcePenalty(src) {
    const raw = JSON.stringify(src || {}).toLowerCase();
    let p = 0;
    if (/onlyflix|vidsrc|youtube|trailer|external|embed|iframe/.test(raw)) p += 80;
    if (/r2|vip|720|1080|mp4|video|مباشر|مدمج/.test(raw)) p -= 10;
    return p;
  }

  function findR2Template() {
    const sources = Array.isArray(window._watchSources) ? window._watchSources : [];
    let best = null;
    sources.forEach(function (src, index) {
      if (!src || typeof src !== 'object') return;
      const candidates = mediaCandidates(src, [], []).sort(function (a,b) { return b.score-a.score; });
      if (!candidates.length) return;

      const raw = JSON.stringify(src || {}).toLowerCase();
      const adminKey = String(src.adminKey || '').toLowerCase();
      let r2Bonus = 0;

      // Prefer the exact R2/native-video source object whenever it exists.
      if (adminKey === 'r2' || adminKey.indexOf('r2') >= 0) r2Bonus += 120;
      if (/(^|[^a-z0-9])r2([^a-z0-9]|$)/i.test(raw)) r2Bonus += 55;
      if (/onlyflix|vidsrc|embed|iframe|youtube|trailer/.test(raw)) r2Bonus -= 160;

      const score = candidates[0].score - sourcePenalty(src) + r2Bonus;
      if (!best || score > best.score) {
        best = {index:index, source:src, slot:candidates[0], score:score};
      }
    });
    return best;
  }

  function cleanupInjected() {
    if (!active) return;
    try {
      if (typeof active.previousIndex === 'number') {
        window._watchSelectedIdx = active.previousIndex;
      }
    } catch (_) {}
    active.r2Video = null;
    active.originalVideoSrc = '';
  }

  function watchPlayerClose() {
    clearInterval(cleanupTimer);
    cleanupTimer = setInterval(function () {
      if (!active || !active.r2Opened) {
        clearInterval(cleanupTimer);
        cleanupTimer = 0;
        return;
      }
      let open = false;
      try {
        open = !!document.querySelector(
          '#videoPlayerModal.open,#videoPlayerModal.inline-player-v265.open'
        );
      } catch (_) {}
      if (!open && active.r2Opened) {
        cleanupInjected();
        active = null;
        clearInterval(cleanupTimer);
        cleanupTimer = 0;
      }
    }, 500);
  }

  function findR2VideoElement() {
    try {
      const modal = document.querySelector(
        '#videoPlayerModal.open,#videoPlayerModal.inline-player-v265.open'
      );
      if (!modal) return null;
      return modal.querySelector('video');
    } catch (_) {
      return null;
    }
  }

  function swapExistingR2VideoToHls(state, url) {
    const video = findR2VideoElement();
    if (!video || active !== state) return false;

    try {
      // Preserve the exact R2 modal, controls, subtitle overlay and subPanel.
      // Only the already-created R2 <video> media URL is replaced in memory.
      state.r2Video = video;
      state.originalVideoSrc = String(video.currentSrc || video.src || '');

      try { video.pause(); } catch (_) {}
      try {
        Array.prototype.slice.call(video.querySelectorAll('source')).forEach(function (s) {
          s.removeAttribute('src');
        });
      } catch (_) {}

      video.src = String(url);
      video.setAttribute('src', String(url));
      video.preload = 'auto';
      video.load();

      const playResult = video.play();
      if (playResult && typeof playResult.catch === 'function') {
        playResult.catch(function () {
          try {
            if (active === state) notify('تم فتح مشغّل R2، اضغط تشغيل مرة واحدة إذا لم يبدأ تلقائياً.');
          } catch (_) {}
        });
      }

      state.r2Opened = true;
      watchPlayerClose();
      return true;
    } catch (_) {
      return false;
    }
  }

  function openInExistingR2(url) {
    const state = active;
    if (!state || !/^https:\/\//i.test(String(url || ''))) return false;

    const template = findR2Template();
    if (!template) {
      notify('لم أجد مصدر R2 صالحاً لاستخدام واجهته الحالية.');
      return false;
    }

    try {
      const sources = window._watchSources;
      if (!Array.isArray(sources)) throw new Error('sources');

      state.previousIndex =
        typeof window._watchSelectedIdx === 'number'
          ? window._watchSelectedIdx
          : 0;

      /*
       * Open the REAL R2 source object first so SubHub creates the exact same
       * R2 modal/controls/subPanel that already works today. We do not clone
       * or edit the R2 source object at all.
       */
      window._watchSelectedIdx = template.index;

      if (typeof stopInlinePlayersV265 === 'function') {
        try { stopInlinePlayersV265(); } catch (_) {}
      }
      if (typeof closeEmbedPlayer === 'function') {
        try { closeEmbedPlayer(); } catch (_) {}
      }
      if (typeof playSelectedWatchSource !== 'function') {
        throw new Error('player opener');
      }

      playSelectedWatchSource();

      let attempts = 0;
      const attach = function () {
        if (active !== state) return;
        attempts += 1;

        const modalOpen = !!document.querySelector(
          '#videoPlayerModal.open,#videoPlayerModal.inline-player-v265.open'
        );

        if (modalOpen && swapExistingR2VideoToHls(state, String(url))) {
          return;
        }

        if (attempts < 30) {
          setTimeout(attach, 100);
          return;
        }

        window._watchSelectedIdx = state.previousIndex;
        notify('فتح R2 لكن لم أجد عنصر الفيديو لاستبدال البث. سنجرب المسار التالي.');
        active = null;
      };

      setTimeout(attach, 0);
      return true;
    } catch (_) {
      try { window._watchSelectedIdx = state.previousIndex; } catch (_) {}
      notify('تعذّر فتح مشغّل R2.');
      return false;
    }
  }

  async function open() {
    if (opening || active) return;
    opening = true;
    const selected = movie();

    try {
      // Available to signed-in subscribers. The URL itself is never stored:
      // Android captures a fresh HLS session on every press.
      if (!selected ||
          typeof isLoggedIn === 'undefined' || !isLoggedIn ||
          movie() !== selected) return;

      const bridge = window.SubHubAndroidBridge;
      if (!bridge || typeof bridge.openDirectStream !== 'function') {
        throw new Error('bridge unavailable');
      }

      const id =
        typeof _vidfastMovieIdV302 === 'function'
          ? String(_vidfastMovieIdV302() || '')
          : '';

      if (!/^[A-Za-z0-9_-]{1,80}$/.test(id)) {
        notify('لا يتوفر معرّف مصدر لهذا الفيلم.');
        return;
      }

      const session =
        'direct_' + Date.now() + '_' +
        Math.random().toString(36).slice(2);

      active = {
        session:session,
        movieId:selected.id,
        previousIndex:0,
        r2Opened:false,
        r2Video:null,
        originalVideoSrc:''
      };

      bridge.openDirectStream(token, JSON.stringify({
        session:session,
        movieId:id,
        mode:'capture_to_r2'
      }));
    } catch (_) {
      active = null;
      notify('تعذّر بدء جلب البث المباشر.');
    } finally {
      opening = false;
    }
  }

  window.__subhubDirectCaptured = function (session, url) {
    const state = active;
    if (!state || state.session !== session) return false;
    if (!movie() || movie().id !== state.movieId) {
      active = null;
      return false;
    }
    const ok = openInExistingR2(String(url || ''));
    if (!ok && active === state) active = null;
    return ok;
  };

  window.__subhubDirectClosed = function (session) {
    if (!active || active.session !== session) return;
    if (!active.r2Opened) {
      cleanupInjected();
      active = null;
    }
  };

  function install() {
    const old = document.getElementById('subhub-direct-stream-button');
    if (typeof isLoggedIn === 'undefined' || !isLoggedIn || !movie()) {
      if (old) old.remove();
      return;
    }
    if (old) return;

    const reference = document.getElementById('vidsrcOwnerTrialV355');
    if (!reference || !reference.parentElement) return;

    const button = document.createElement('button');
    button.id = 'subhub-direct-stream-button';
    button.type = 'button';
    button.className = reference.className;
    button.textContent = '▶ مشاهدة مباشرة — تجريبي';
    button.style.cssText =
      'min-height:68px;border:1px solid #e8b544;border-radius:16px;' +
      'background:#132536;color:#fff;padding:12px;font:inherit;cursor:pointer';
    button.addEventListener('click', open);
    reference.insertAdjacentElement('afterend', button);
  }

  install();
  setInterval(install, 1000);
})();