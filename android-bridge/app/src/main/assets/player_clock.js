(function () {
  'use strict';
  if (window.__subHubClockInstalledV221) return;
  window.__subHubClockInstalledV221 = true;

  const HOST_RE = /(?:^|\.)(?:onlyflix\.to|cdnm\.ink|cdnmovies-stream\.online|cdnmvs\.online)$/i;
  if (!HOST_RE.test(location.hostname || '')) return;

  function isOnlyFlixHost() { return /(?:^|\.)onlyflix\.to$/i.test(location.hostname || ''); }
  function isShareHost() { return /(?:^|\.)share\.cdnm\.ink$/i.test(location.hostname || ''); }
  function isPlayerHost() { return /(?:^|\.)cdnmovies-stream\.online$/i.test(location.hostname || ''); }

  /*
   * Preparation screen ported from the proven SoapDiag 1.16 behavior.
   * Normal path: the deepest player reports real media readiness.
   * Fallbacks: iframe load + 6500 ms, hard ceiling 11000 ms.
   */
  const PREP_MESSAGE_TYPE = '__soapdiag_player_ready__';
  const PREP_OVERLAY_ID = '__subhub_prepare_overlay';
  const PREP_READY_PREFIX = PREP_MESSAGE_TYPE + '|';
  let playerReadySignalled = false;
  let prepareDismissed = false;
  let prepareFallbackArmed = false;
  let lastReadyRelay = '';

  function findFrameMatching(re) {
    try {
      return Array.from(document.querySelectorAll('iframe')).find(function (f) {
        const src = String(f.getAttribute('src') || f.src || '');
        return re.test(src);
      }) || null;
    } catch (_) { return null; }
  }

  function findServer1Frame() {
    try {
      const frames = Array.from(document.querySelectorAll('iframe'));
      return frames.find(function (f) {
        const src = String(f.getAttribute('src') || f.src || '');
        return f.id === '__soapdiag_server1' ||
          /share\.cdnm\.ink\/embed\//i.test(src) ||
          /cdnmovies-stream\.online\/imdb\//i.test(src);
      }) || null;
    } catch (_) { return null; }
  }

  function maximizeFrameInCurrentDocument(frame) {
    if (!frame) return false;
    try {
      let p = frame.parentElement;
      while (p && p !== document.body && p !== document.documentElement) {
        p.style.setProperty('display', 'block', 'important');
        p.style.setProperty('visibility', 'visible', 'important');
        p.style.setProperty('opacity', '1', 'important');
        p.style.setProperty('transform', 'none', 'important');
        p.style.setProperty('filter', 'none', 'important');
        p.style.setProperty('perspective', 'none', 'important');
        p.style.setProperty('overflow', 'visible', 'important');
        p.style.setProperty('width', '100%', 'important');
        p.style.setProperty('height', '100%', 'important');
        p = p.parentElement;
      }
      if (document.documentElement) {
        document.documentElement.style.setProperty('width', '100%', 'important');
        document.documentElement.style.setProperty('height', '100%', 'important');
        document.documentElement.style.setProperty('overflow', 'hidden', 'important');
      }
      if (document.body) {
        document.body.style.setProperty('width', '100%', 'important');
        document.body.style.setProperty('height', '100%', 'important');
        document.body.style.setProperty('overflow', 'hidden', 'important');
        document.body.style.setProperty('margin', '0', 'important');
        document.body.style.setProperty('padding', '0', 'important');
        document.body.style.setProperty('background', '#000', 'important');
      }
      frame.style.setProperty('display', 'block', 'important');
      frame.style.setProperty('visibility', 'visible', 'important');
      frame.style.setProperty('opacity', '1', 'important');
      frame.style.setProperty('position', 'fixed', 'important');
      frame.style.setProperty('inset', '0', 'important');
      frame.style.setProperty('left', '0', 'important');
      frame.style.setProperty('top', '0', 'important');
      frame.style.setProperty('width', '100vw', 'important');
      frame.style.setProperty('height', '100vh', 'important');
      frame.style.setProperty('min-width', '100vw', 'important');
      frame.style.setProperty('min-height', '100vh', 'important');
      frame.style.setProperty('max-width', 'none', 'important');
      frame.style.setProperty('max-height', 'none', 'important');
      frame.style.setProperty('margin', '0', 'important');
      frame.style.setProperty('padding', '0', 'important');
      frame.style.setProperty('border', '0', 'important');
      frame.style.setProperty('z-index', '2147483646', 'important');
      frame.setAttribute('allow', (frame.getAttribute('allow') || '') + '; autoplay; fullscreen; picture-in-picture');
      return true;
    } catch (_) { return false; }
  }

  function maximizePlayerDocument() {
    if (!isPlayerHost()) return;
    try {
      const id = '__subhub_player_fill';
      if (document.getElementById(id)) return;
      const style = document.createElement('style');
      style.id = id;
      style.textContent = [
        'html,body{width:100%!important;height:100%!important;margin:0!important;padding:0!important;overflow:hidden!important;background:#000!important;}',
        '#player,.player,.oframeplayer,[class*="player"],.jwplayer,.video-js{width:100%!important;height:100%!important;max-width:none!important;max-height:none!important;margin:0!important;}',
        'video{width:100%!important;height:100%!important;max-width:none!important;max-height:none!important;object-fit:contain!important;}'
      ].join('\n');
      (document.head || document.documentElement).appendChild(style);
    } catch (_) {}
  }

  function forceInlinePlayerRepaint() {
    if (!isOnlyFlixHost()) return;
    try {
      const frame = findServer1Frame();
      if (!frame) return;
      frame.style.setProperty('backface-visibility', 'hidden', 'important');
      frame.style.setProperty('transform', 'translateZ(0)', 'important');
      void frame.offsetWidth;
      try { window.dispatchEvent(new Event('resize')); } catch (_) {}
      try {
        requestAnimationFrame(function () {
          requestAnimationFrame(function () {
            frame.style.setProperty('transform', 'none', 'important');
            void frame.offsetHeight;
            try { window.dispatchEvent(new Event('resize')); } catch (_) {}
          });
        });
      } catch (_) {
        setTimeout(function () { frame.style.setProperty('transform', 'none', 'important'); }, 80);
      }
    } catch (_) {}
  }

  function ensurePrepareOverlay() {
    if (!isOnlyFlixHost() || prepareDismissed) return;
    try {
      if (document.getElementById(PREP_OVERLAY_ID)) return;
      const host = document.body || document.documentElement;
      if (!host) return;
      const box = document.createElement('div');
      box.id = PREP_OVERLAY_ID;
      box.setAttribute('aria-live', 'polite');
      box.style.cssText = [
        'position:fixed','inset:0','width:100vw','height:100vh',
        'display:flex','align-items:center','justify-content:center',
        'background:#000','z-index:2147483647','pointer-events:none',
        'font-family:sans-serif','direction:rtl','color:#fff'
      ].join(';');
      box.innerHTML = '<div style="text-align:center;padding:24px">' +
        '<div style="width:44px;height:44px;border:4px solid rgba(255,255,255,.25);border-top-color:#fff;border-radius:50%;margin:0 auto 18px;animation:subhub-prep-spin .8s linear infinite"></div>' +
        '<div style="font-size:21px;font-weight:600">جارٍ تحضير الفيديو...</div>' +
        '<div style="font-size:13px;opacity:.7;margin-top:8px">سيظهر المشغّل مباشرةً عند الجاهزية</div>' +
        '</div>';
      const style = document.createElement('style');
      style.id = PREP_OVERLAY_ID + '_style';
      style.textContent = '@keyframes subhub-prep-spin{to{transform:rotate(360deg)}}';
      (document.head || document.documentElement).appendChild(style);
      host.appendChild(box);
    } catch (_) {}
  }

  function hidePrepareOverlay() {
    if (!isOnlyFlixHost()) return;
    try {
      const box = document.getElementById(PREP_OVERLAY_ID);
      if (box) box.remove();
      const style = document.getElementById(PREP_OVERLAY_ID + '_style');
      if (style) style.remove();
      prepareDismissed = true;
      setTimeout(forceInlinePlayerRepaint, 16);
    } catch (_) {}
  }

  function readyMessageReason(data) {
    try {
      if (typeof data === 'string' && data.indexOf(PREP_READY_PREFIX) === 0) {
        return decodeURIComponent(data.slice(PREP_READY_PREFIX.length) || 'player-ready');
      }
      if (data && typeof data === 'object' && data.type === PREP_MESSAGE_TYPE) {
        return String(data.reason || 'player-ready');
      }
    } catch (_) {}
    return '';
  }

  function relayReadyMessage(data) {
    try {
      const key = typeof data === 'string' ? data : JSON.stringify(data || {});
      if (key && key === lastReadyRelay) return;
      lastReadyRelay = key;
      if (window.parent && window.parent !== window) window.parent.postMessage(data, '*');
    } catch (_) {}
  }

  function signalPlayerReady(reason) {
    if (!isPlayerHost() || playerReadySignalled) return;
    playerReadySignalled = true;
    const why = String(reason || 'media-ready');
    const token = PREP_READY_PREFIX + encodeURIComponent(why);
    try { if (window.parent && window.parent !== window) window.parent.postMessage(token, '*'); } catch (_) {}
    try { if (window.top && window.top !== window && window.top !== window.parent) window.top.postMessage(token, '*'); } catch (_) {}
    try { window.top.postMessage({type: PREP_MESSAGE_TYPE, reason: why, href: location.href}, '*'); } catch (_) {}
  }

  function armPrepareFallback(frame) {
    if (!isOnlyFlixHost() || !frame || prepareFallbackArmed) return;
    prepareFallbackArmed = true;
    const reveal = function () {
      if (prepareDismissed) return;
      maximizeServer1Frame();
      hidePrepareOverlay();
    };
    try {
      frame.addEventListener('load', function () {
        setTimeout(reveal, 6500);
      }, {once:true});
    } catch (_) {}
    setTimeout(reveal, 11000);
  }

  function maximizeServer1Frame() {
    if (isOnlyFlixHost()) {
      const frame = findServer1Frame();
      if (frame) armPrepareFallback(frame);
      return maximizeFrameInCurrentDocument(frame);
    }
    if (isShareHost()) {
      return maximizeFrameInCurrentDocument(findFrameMatching(/cdnmovies-stream\.online\/imdb\//i));
    }
    return false;
  }

  try {
    window.addEventListener('message', function (ev) {
      const data = ev && ev.data;
      const reason = readyMessageReason(data);
      if (!reason) return;
      if (isOnlyFlixHost()) {
        maximizeServer1Frame();
        setTimeout(hidePrepareOverlay, 40);
      } else {
        relayReadyMessage(data);
      }
    }, true);
  } catch (_) {}

  ensurePrepareOverlay();
  try { setTimeout(ensurePrepareOverlay, 0); } catch (_) {}

  /* Stable bridge 322.2 timing logic below — unchanged. */
  const seen = new WeakSet();
  const state = new WeakMap();
  const sourceId = (location.hostname || 'player') + '|' + Date.now().toString(36) + '|' + Math.random().toString(36).slice(2, 8);
  let activeVideo = null;
  let seq = 0;
  let lastSentAt = 0;
  let lastSig = '';

  function bridge() {
    try {
      return window.SubHubAndroidBridge && typeof window.SubHubAndroidBridge.mediaClock === 'function'
        ? window.SubHubAndroidBridge : null;
    } catch (_) { return null; }
  }

  function rectArea(v) {
    try {
      const r = v.getBoundingClientRect();
      if (!r || r.width <= 0 || r.height <= 0) return 0;
      const w = Math.max(0, Math.min(innerWidth || r.width, r.right) - Math.max(0, r.left));
      const h = Math.max(0, Math.min(innerHeight || r.height, r.bottom) - Math.max(0, r.top));
      return Math.round(w * h);
    } catch (_) { return 0; }
  }

  function mediaScore(v) {
    if (!v || v.tagName !== 'VIDEO') return -100000;
    let score = 0;
    const duration = Number(v.duration);
    const area = rectArea(v);
    if (!v.ended) score += 8;
    if (!v.paused) score += 38;
    if (v.seeking) score += 55;
    if (Number(v.readyState || 0) >= 2) score += 16;
    if (Number.isFinite(duration) && duration >= 60) score += 22;
    if (String(v.currentSrc || v.src || '')) score += 6;
    if (area > 0) score += Math.min(34, area / 18000);
    try {
      if (document.fullscreenElement && (document.fullscreenElement === v || document.fullscreenElement.contains(v))) score += 50;
    } catch (_) {}
    return score;
  }

  function chooseBest(prefer) {
    try {
      const list = Array.from(document.querySelectorAll('video'));
      if (!list.length) { activeVideo = null; return null; }
      let best = null;
      let bestScore = -100000;
      for (const video of list) {
        const score = mediaScore(video) + (video === prefer ? 20 : 0);
        if (score > bestScore) { best = video; bestScore = score; }
      }
      activeVideo = best;
      return best;
    } catch (_) {
      return activeVideo;
    }
  }

  function ensureState(video) {
    let st = state.get(video);
    if (!st) {
      st = {waiting:false, lastEvent:'', lastFrameTime:NaN};
      state.set(video, st);
    }
    return st;
  }

  function maybeSignalReady(video, playingEvent) {
    if (!isPlayerHost() || !video) return;
    try {
      const current = Number(video.currentTime || 0);
      const readyState = Number(video.readyState || 0);
      if (playingEvent && readyState >= 2) {
        signalPlayerReady('media-playing-event');
        return;
      }
      if (!video.paused && readyState >= 2 && current > 0.03) {
        signalPlayerReady('media-playing-progress');
      }
    } catch (_) {}
  }

  function payload(video, eventName, overrideTime) {
    const st = ensureState(video);
    const duration = Number(video.duration);
    const current = Number.isFinite(Number(overrideTime)) ? Number(overrideTime) : Number(video.currentTime || 0);
    return {
      source: sourceId,
      seq: ++seq,
      event: String(eventName || 'clock'),
      page: location.href,
      host: location.hostname || '',
      currentTime: current,
      duration: Number.isFinite(duration) ? duration : 0,
      paused: !!video.paused,
      seeking: !!video.seeking,
      waiting: !!st.waiting,
      playbackRate: Number(video.playbackRate || 1),
      readyState: Number(video.readyState || 0),
      ended: !!video.ended,
      visibleArea: rectArea(video),
      score: mediaScore(video),
      at: Date.now()
    };
  }

  function send(video, force, eventName, overrideTime) {
    if (!video || video !== activeVideo) return;
    const b = bridge();
    if (!b) return;
    const now = performance.now ? performance.now() : Date.now();
    if (!force && now - lastSentAt < 65) return;
    const p = payload(video, eventName, overrideTime);
    const sig = [
      p.currentTime.toFixed(3), p.paused ? 1 : 0, p.seeking ? 1 : 0, p.waiting ? 1 : 0,
      p.playbackRate.toFixed(3), p.readyState, p.ended ? 1 : 0, p.source
    ].join('|');
    if (!force && sig === lastSig) return;
    lastSentAt = now;
    lastSig = sig;
    try { b.mediaClock(JSON.stringify(p)); } catch (_) {}
  }

  function onEvent(video, name) {
    const st = ensureState(video);
    st.lastEvent = name;
    if (name === 'waiting' || name === 'stalled' || name === 'seeking') st.waiting = true;
    if (name === 'playing' || name === 'canplay' || name === 'seeked' || name === 'timeupdate') st.waiting = false;

    if (name === 'seeking' || name === 'seeked' || name === 'play' || name === 'playing') {
      chooseBest(video);
    } else if (!activeVideo || !document.contains(activeVideo)) {
      chooseBest(video);
    }

    maybeSignalReady(video, name === 'playing');

    const urgent = name === 'seeking' || name === 'seeked' || name === 'pause' || name === 'playing' || name === 'waiting';
    send(video, urgent, name);
  }

  function startVideoFrameLoop(video) {
    if (!video || typeof video.requestVideoFrameCallback !== 'function') return;
    const st = ensureState(video);
    if (st.frameLoopStarted) return;
    st.frameLoopStarted = true;
    const loop = function (_now, meta) {
      try {
        if (video === activeVideo && meta && Number.isFinite(Number(meta.mediaTime))) {
          st.lastFrameTime = Number(meta.mediaTime);
          st.waiting = false;
          maybeSignalReady(video, false);
          send(video, false, 'frame', st.lastFrameTime);
        }
      } catch (_) {}
      try { video.requestVideoFrameCallback(loop); } catch (_) { st.frameLoopStarted = false; }
    };
    try { video.requestVideoFrameCallback(loop); } catch (_) { st.frameLoopStarted = false; }
  }

  function attach(video) {
    if (!video || video.tagName !== 'VIDEO' || seen.has(video)) return;
    seen.add(video);
    ensureState(video);
    [
      'loadedmetadata','durationchange','play','pause','playing','waiting','stalled',
      'seeking','seeked','ratechange','ended','emptied','canplay','timeupdate'
    ].forEach(function (name) {
      try { video.addEventListener(name, function () { onEvent(video, name); }, true); } catch (_) {}
    });
    startVideoFrameLoop(video);
    if (!activeVideo) chooseBest(video);
    maybeSignalReady(video, false);
  }

  function scan() {
    try {
      ensurePrepareOverlay();
      maximizeServer1Frame();
      maximizePlayerDocument();
      document.querySelectorAll('video').forEach(attach);
      chooseBest(activeVideo);
      if (activeVideo) maybeSignalReady(activeVideo, false);
    } catch (_) {}
  }

  scan();

  try {
    new MutationObserver(function () {
      scan();
    }).observe(document.documentElement || document, {
      childList:true,
      subtree:true,
      attributes:true,
      attributeFilter:['src','href']
    });
  } catch (_) {}

  setInterval(function () {
    try {
      scan();
      if (activeVideo && document.contains(activeVideo)) {
        maybeSignalReady(activeVideo, false);
        send(activeVideo, false, 'poll');
      }
    } catch (_) {}
  }, 90);
})();