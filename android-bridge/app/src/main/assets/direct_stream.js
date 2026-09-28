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
    const state = active;

    try {
      if (state.syncTimer) clearInterval(state.syncTimer);
      state.syncTimer = 0;
    } catch (_) {}

    try {
      const bridge = window.SubHubAndroidBridge;
      if (bridge && typeof bridge.directR2Command === 'function' && state.session) {
        bridge.directR2Command(token, state.session, 'close', 0);
      }
    } catch (_) {}

    try {
      const video = state.r2Video;
      if (video) {
        ['currentTime','duration','paused','ended','readyState','networkState'].forEach(function (key) {
          try { delete video[key]; } catch (_) {}
        });

        try {
          if (state.hadOwnPlay) video.play = state.originalPlay;
          else delete video.play;
        } catch (_) {}
        try {
          if (state.hadOwnPause) video.pause = state.originalPause;
          else delete video.pause;
        } catch (_) {}

        try {
          if (state.originalSrc) video.setAttribute('src', state.originalSrc);
          else video.removeAttribute('src');
          if (state.originalPoster) video.setAttribute('poster', state.originalPoster);
          else video.removeAttribute('poster');

          (state.sourceNodes || []).forEach(function (item) {
            try {
              if (item.src) item.node.setAttribute('src', item.src);
              else item.node.removeAttribute('src');
            } catch (_) {}
          });

          if (state.originalVideoStyle != null) {
            video.setAttribute('style', state.originalVideoStyle);
          } else {
            video.removeAttribute('style');
          }
          video.load();
        } catch (_) {}
      }
    } catch (_) {}

    try {
      (state.transparentNodes || []).forEach(function (item) {
        try {
          if (item.style == null) item.node.removeAttribute('style');
          else item.node.setAttribute('style', item.style);
        } catch (_) {}
      });
    } catch (_) {}

    try {
      if (typeof state.previousIndex === 'number') {
        window._watchSelectedIdx = state.previousIndex;
      }
    } catch (_) {}

    state.r2Video = null;
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
    }, 350);
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

  function normalizeRect(video) {
    const r = video.getBoundingClientRect();
    const iw = Math.max(1, window.innerWidth || document.documentElement.clientWidth || 1);
    const ih = Math.max(1, window.innerHeight || document.documentElement.clientHeight || 1);
    return {
      x:Math.max(0, r.left / iw),
      y:Math.max(0, r.top / ih),
      w:Math.max(0.01, r.width / iw),
      h:Math.max(0.01, r.height / ih)
    };
  }

  function resizeModeFor(video) {
    try {
      const fit = String(getComputedStyle(video).objectFit || '').toLowerCase();
      if (fit === 'cover') return 'cover';
      if (fit === 'fill') return 'fill';
    } catch (_) {}
    return 'fit';
  }

  function makeVideoAreaTransparent(state, video) {
    state.transparentNodes = [];
    let node = video.parentElement;
    const modal = video.closest && video.closest('#videoPlayerModal');
    let guard = 0;
    while (node && guard++ < 8) {
      state.transparentNodes.push({
        node:node,
        style:node.hasAttribute('style') ? node.getAttribute('style') : null
      });
      try {
        node.style.setProperty('background-color','transparent','important');
        node.style.setProperty('background-image','none','important');
      } catch (_) {}
      if (node === modal) break;
      node = node.parentElement;
    }
  }

  function installR2Facade(state, video) {
    const bridge = window.SubHubAndroidBridge;
    if (!bridge ||
        typeof bridge.startDirectR2Playback !== 'function' ||
        typeof bridge.getDirectR2State !== 'function') return false;

    state.r2Video = video;
    state.originalSrc = String(video.getAttribute('src') || '');
    state.originalPoster = String(video.getAttribute('poster') || '');
    state.originalVideoStyle = video.hasAttribute('style') ? video.getAttribute('style') : null;
    state.hadOwnPlay = Object.prototype.hasOwnProperty.call(video,'play');
    state.hadOwnPause = Object.prototype.hasOwnProperty.call(video,'pause');
    state.originalPlay = video.play;
    state.originalPause = video.pause;
    state.sourceNodes = Array.prototype.slice.call(video.querySelectorAll('source')).map(function (node) {
      return {node:node, src:String(node.getAttribute('src') || '')};
    });

    makeVideoAreaTransparent(state, video);

    try { state.originalPause.call(video); } catch (_) {}
    try { video.muted = true; } catch (_) {}
    try { video.preload = 'none'; } catch (_) {}
    try { video.removeAttribute('poster'); } catch (_) {}
    try { video.removeAttribute('src'); } catch (_) {}
    try {
      state.sourceNodes.forEach(function (item) { item.node.removeAttribute('src'); });
      video.load();
    } catch (_) {}

    try {
      video.style.setProperty('background','transparent','important');
      video.style.setProperty('visibility','hidden','important');
    } catch (_) {}

    state.native = {
      position:0,
      duration:0,
      buffered:0,
      playing:true,
      state:2
    };
    state.syncing = false;
    state.lastPlaying = null;

    function define(name, getter, setter) {
      try {
        Object.defineProperty(video, name, {
          configurable:true,
          enumerable:true,
          get:getter,
          set:setter || function () {}
        });
      } catch (_) {}
    }

    define('currentTime',
      function () { return Math.max(0, Number(state.native.position || 0) / 1000); },
      function (seconds) {
        if (state.syncing) return;
        const ms = Math.max(0, Number(seconds || 0) * 1000);
        try { bridge.directR2Command(token,state.session,'seek',ms); } catch (_) {}
      }
    );
    define('duration', function () {
      const d = Number(state.native.duration || 0);
      return d > 0 ? d / 1000 : 0;
    });
    define('paused', function () { return !state.native.playing; });
    define('ended', function () {
      const d=Number(state.native.duration||0), p=Number(state.native.position||0);
      return d>0 && p>=d-250;
    });
    define('readyState', function () { return 4; });
    define('networkState', function () { return 1; });

    try {
      video.play = function () {
        try { bridge.directR2Command(token,state.session,'play',0); } catch (_) {}
        state.native.playing = true;
        try { video.dispatchEvent(new Event('play')); } catch (_) {}
        return Promise.resolve();
      };
      video.pause = function () {
        try { bridge.directR2Command(token,state.session,'pause',0); } catch (_) {}
        state.native.playing = false;
        try { video.dispatchEvent(new Event('pause')); } catch (_) {}
      };
    } catch (_) {}

    video.addEventListener('volumechange', function () {
      if (active !== state) return;
      try {
        bridge.directR2Command(
          token,state.session,'volume',
          video.muted ? 0 : Math.max(0,Math.min(1,Number(video.volume || 0)))
        );
      } catch (_) {}
    });

    const rect = normalizeRect(video);
    try {
      bridge.startDirectR2Playback(
        token,state.session,rect.x,rect.y,rect.w,rect.h
      );
    } catch (_) {
      return false;
    }

    state.syncTimer = setInterval(function () {
      if (active !== state) return;

      try {
        const raw = bridge.getDirectR2State(token,state.session);
        const next = raw ? JSON.parse(raw) : null;
        if (next && typeof next === 'object') {
          state.native = next;
        }
      } catch (_) {}

      try {
        const r = normalizeRect(video);
        bridge.updateDirectR2Bounds(
          token,state.session,r.x,r.y,r.w,r.h,resizeModeFor(video)
        );
      } catch (_) {}

      try {
        const playing = !!state.native.playing;
        if (playing !== state.lastPlaying) {
          state.lastPlaying = playing;
          video.dispatchEvent(new Event(playing ? 'play' : 'pause'));
        }
        video.dispatchEvent(new Event('timeupdate'));
        video.dispatchEvent(new Event('progress'));
      } catch (_) {}
    }, 180);

    state.r2Opened = true;
    watchPlayerClose();
    return true;
  }

  function openInExistingR2() {
    const state = active;
    if (!state) return false;

    const template = findR2Template();
    if (!template) {
      notify('لم أجد مصدر R2 صالحاً لاستخدام واجهته الحالية.');
      return false;
    }

    try {
      if (!Array.isArray(window._watchSources)) throw new Error('sources');

      state.previousIndex =
        typeof window._watchSelectedIdx === 'number'
          ? window._watchSelectedIdx
          : 0;

      // Open the exact existing R2 UI. No R2 settings/styles are copied or edited.
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

        const video = findR2VideoElement();
        if (video && installR2Facade(state, video)) return;

        if (attempts < 35) {
          setTimeout(attach,100);
          return;
        }

        try { window._watchSelectedIdx = state.previousIndex; } catch (_) {}
        notify('فتح R2 لكن تعذّر ربط البث المباشر به.');
        active = null;
      };

      setTimeout(attach,0);
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
        originalVideoSrc:'',
        syncTimer:0,
        transparentNodes:[],
        sourceNodes:[]
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

  window.__subhubDirectCaptured = function (session) {
    const state = active;
    if (!state || state.session !== session) return false;
    if (!movie() || movie().id !== state.movieId) {
      active = null;
      return false;
    }
    const ok = openInExistingR2();
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