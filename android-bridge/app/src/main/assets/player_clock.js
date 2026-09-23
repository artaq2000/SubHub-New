(function () {
  'use strict';
  if (window.__subHubClockInstalledV2) return;
  window.__subHubClockInstalledV2 = true;

  const HOST_RE = /(?:^|\.)(?:onlyflix\.to|cdnm\.ink|cdnmovies-stream\.online|cdnmvs\.online)$/i;
  if (!HOST_RE.test(location.hostname || '')) return;

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
    let s = 0;
    const dur = Number(v.duration);
    const area = rectArea(v);
    if (!v.ended) s += 8;
    if (!v.paused) s += 38;
    if (v.seeking) s += 55;
    if (Number(v.readyState || 0) >= 2) s += 16;
    if (Number.isFinite(dur) && dur >= 60) s += 22;
    if (String(v.currentSrc || v.src || '')) s += 6;
    if (area > 0) s += Math.min(34, area / 18000);
    try {
      if (document.fullscreenElement && (document.fullscreenElement === v || document.fullscreenElement.contains(v))) s += 50;
    } catch (_) {}
    return s;
  }

  function chooseBest(prefer) {
    try {
      const list = Array.from(document.querySelectorAll('video'));
      if (!list.length) { activeVideo = null; return null; }
      let best = null;
      let bestScore = -100000;
      for (const v of list) {
        const s = mediaScore(v) + (v === prefer ? 20 : 0);
        if (s > bestScore) { best = v; bestScore = s; }
      }
      activeVideo = best;
      return best;
    } catch (_) {
      return activeVideo;
    }
  }

  function ensureState(v) {
    let st = state.get(v);
    if (!st) {
      st = { waiting: false, lastEvent: '', lastFrameTime: NaN };
      state.set(v, st);
    }
    return st;
  }

  function payload(v, eventName, overrideTime) {
    const st = ensureState(v);
    const duration = Number(v.duration);
    const current = Number.isFinite(Number(overrideTime)) ? Number(overrideTime) : Number(v.currentTime || 0);
    return {
      source: sourceId,
      seq: ++seq,
      event: String(eventName || 'clock'),
      page: location.href,
      host: location.hostname || '',
      currentTime: current,
      duration: Number.isFinite(duration) ? duration : 0,
      paused: !!v.paused,
      seeking: !!v.seeking,
      waiting: !!st.waiting,
      playbackRate: Number(v.playbackRate || 1),
      readyState: Number(v.readyState || 0),
      ended: !!v.ended,
      visibleArea: rectArea(v),
      score: mediaScore(v),
      at: Date.now()
    };
  }

  function send(v, force, eventName, overrideTime) {
    if (!v || v !== activeVideo) return;
    const b = bridge();
    if (!b) return;
    const now = performance.now ? performance.now() : Date.now();
    if (!force && now - lastSentAt < 65) return;
    const p = payload(v, eventName, overrideTime);
    const sig = [
      p.currentTime.toFixed(3), p.paused ? 1 : 0, p.seeking ? 1 : 0, p.waiting ? 1 : 0,
      p.playbackRate.toFixed(3), p.readyState, p.ended ? 1 : 0, p.source
    ].join('|');
    if (!force && sig === lastSig) return;
    lastSentAt = now;
    lastSig = sig;
    try { b.mediaClock(JSON.stringify(p)); } catch (_) {}
  }

  function onEvent(v, name) {
    const st = ensureState(v);
    st.lastEvent = name;
    if (name === 'waiting' || name === 'stalled' || name === 'seeking') st.waiting = true;
    if (name === 'playing' || name === 'canplay' || name === 'seeked' || name === 'timeupdate') st.waiting = false;

    if (name === 'seeking' || name === 'seeked' || name === 'play' || name === 'playing') {
      chooseBest(v);
    } else if (!activeVideo || !document.contains(activeVideo)) {
      chooseBest(v);
    }

    const urgent = name === 'seeking' || name === 'seeked' || name === 'pause' || name === 'playing' || name === 'waiting';
    send(v, urgent, name);
  }

  function startVideoFrameLoop(v) {
    if (!v || typeof v.requestVideoFrameCallback !== 'function') return;
    const st = ensureState(v);
    if (st.frameLoopStarted) return;
    st.frameLoopStarted = true;
    const loop = function (_now, meta) {
      try {
        if (v === activeVideo && meta && Number.isFinite(Number(meta.mediaTime))) {
          st.lastFrameTime = Number(meta.mediaTime);
          st.waiting = false;
          send(v, false, 'frame', st.lastFrameTime);
        }
      } catch (_) {}
      try { v.requestVideoFrameCallback(loop); } catch (_) { st.frameLoopStarted = false; }
    };
    try { v.requestVideoFrameCallback(loop); } catch (_) { st.frameLoopStarted = false; }
  }

  function attach(v) {
    if (!v || v.tagName !== 'VIDEO' || seen.has(v)) return;
    seen.add(v);
    ensureState(v);
    [
      'loadedmetadata','durationchange','play','pause','playing','waiting','stalled',
      'seeking','seeked','ratechange','ended','emptied','canplay','timeupdate'
    ].forEach(function (name) {
      try { v.addEventListener(name, function () { onEvent(v, name); }, true); } catch (_) {}
    });
    startVideoFrameLoop(v);
    if (!activeVideo) chooseBest(v);
  }

  function scan() {
    try {
      document.querySelectorAll('video').forEach(attach);
      chooseBest(activeVideo);
    } catch (_) {}
  }

  scan();
  try {
    new MutationObserver(scan).observe(document.documentElement || document, {childList:true, subtree:true});
  } catch (_) {}

  setInterval(function () {
    try {
      scan();
      if (activeVideo && document.contains(activeVideo)) send(activeVideo, false, 'poll');
    } catch (_) {}
  }, 90);
})();