(function () {
  'use strict';
  if (window.__subHubClockInstalledV1) return;
  window.__subHubClockInstalledV1 = true;

  const HOST_RE = /(?:^|\.)(?:onlyflix\.to|cdnm\.ink|cdnmovies-stream\.online|cdnmvs\.online)$/i;
  if (!HOST_RE.test(location.hostname || '')) return;

  const seen = new WeakSet();
  let activeMedia = null;
  let lastSentAt = 0;
  let lastSig = '';

  function bridge() {
    try {
      return window.SubHubAndroidBridge && typeof window.SubHubAndroidBridge.mediaClock === 'function'
        ? window.SubHubAndroidBridge : null;
    } catch (_) { return null; }
  }

  function payload(media) {
    const duration = Number(media.duration);
    return {
      page: location.href,
      host: location.hostname || '',
      currentTime: Number(media.currentTime || 0),
      duration: Number.isFinite(duration) ? duration : 0,
      paused: !!media.paused,
      seeking: !!media.seeking,
      playbackRate: Number(media.playbackRate || 1),
      readyState: Number(media.readyState || 0),
      ended: !!media.ended,
      at: Date.now()
    };
  }

  function send(media, force) {
    if (!media) return;
    const b = bridge();
    if (!b) return;
    const now = Date.now();
    if (!force && now - lastSentAt < 80) return;
    const p = payload(media);
    const sig = [
      p.currentTime.toFixed(3), p.paused ? 1 : 0, p.seeking ? 1 : 0,
      p.playbackRate.toFixed(3), p.readyState, p.ended ? 1 : 0
    ].join('|');
    if (!force && sig === lastSig) return;
    lastSentAt = now;
    lastSig = sig;
    try { b.mediaClock(JSON.stringify(p)); } catch (_) {}
  }

  function attach(media) {
    if (!media || seen.has(media)) return;
    seen.add(media);
    activeMedia = media;
    const forceEvents = ['loadedmetadata','durationchange','play','pause','playing','waiting','stalled','seeking','seeked','ratechange','ended','emptied'];
    forceEvents.forEach(function (name) {
      try { media.addEventListener(name, function () { activeMedia = media; send(media, true); }, true); } catch (_) {}
    });
    try { media.addEventListener('timeupdate', function () { activeMedia = media; send(media, false); }, true); } catch (_) {}
    send(media, true);
  }

  function scan() {
    try { document.querySelectorAll('video,audio').forEach(attach); } catch (_) {}
  }

  function tick() {
    try {
      if (activeMedia && document.contains(activeMedia)) send(activeMedia, false);
      else activeMedia = null;
      scan();
    } catch (_) {}
    requestAnimationFrame(tick);
  }

  scan();
  try {
    new MutationObserver(scan).observe(document.documentElement || document, {childList:true, subtree:true});
  } catch (_) {}
  try { requestAnimationFrame(tick); } catch (_) { setInterval(scan, 500); }
})();
