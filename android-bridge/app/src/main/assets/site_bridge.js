(function () {
  'use strict';
  if (window.__subHubSiteBridgeV221) return true;
  window.__subHubSiteBridgeV221 = true;

  const BRIDGE_BUILD = '322.2.1';
  let lastSig = '';
  let clockSource = '';
  let lastSeq = -1;
  let anchorTime = 0;
  let anchorPerf = 0;
  let clockPaused = true;
  let clockSeeking = false;
  let clockWaiting = false;
  let clockEnded = false;
  let clockRate = 1;
  let clockReadyState = 0;
  let lastClockReceivePerf = 0;
  let clockLinked = false;

  function nowPerf() {
    try { return performance.now(); } catch (_) { return Date.now(); }
  }

  function stampBuild() {
    try {
      document.documentElement.setAttribute('data-subhub-android-bridge', BRIDGE_BUILD);
      const tag = document.getElementById('ownerVersionTag');
      if (tag && String(tag.textContent || '').indexOf('Android ' + BRIDGE_BUILD) < 0) {
        let base = String(tag.textContent || '').replace(/\s*·\s*Android\s+322(?:\.\d+)*/g, '').trim();
        tag.textContent = (base ? base + ' · ' : '') + 'Android ' + BRIDGE_BUILD;
        tag.title = 'SubHub Android Bridge ' + BRIDGE_BUILD;
      }
    } catch (_) {}
  }

  function nativeBridge() {
    try {
      const b = window.SubHubAndroidBridge;
      return b && typeof b.subtitleState === 'function' ? b : null;
    } catch (_) { return null; }
  }

  function textEl() {
    const overlay = document.getElementById('embedSubtitleOverlay');
    return overlay ? overlay.querySelector('.sub-text') : null;
  }

  function push(force) {
    const b = nativeBridge();
    if (!b) return;
    const overlay = document.getElementById('embedSubtitleOverlay');
    const tx = textEl();
    if (!overlay || !tx) return;
    let cs = null;
    try { cs = getComputedStyle(tx); } catch (_) {}
    const visible = overlay.style.display !== 'none' && !!String(tx.textContent || '').trim();
    const settings = (typeof _subSettings === 'object' && _subSettings) ? _subSettings : {};
    const p = {
      visible: visible,
      text: visible ? String(tx.textContent || '') : '',
      positionPct: Number(settings.position || 7),
      fontSizePx: cs ? (parseFloat(cs.fontSize || '0') || 0) : 0,
      color: cs ? cs.color : String(settings.color || '#ffffff'),
      background: cs ? cs.backgroundColor : 'rgba(0,0,0,.6)',
      fontWeight: cs ? cs.fontWeight : String(settings.weight || 500),
      fontKey: String(settings.font || 'default'),
      shadow: Number(settings.shadow || 0)
    };
    const sig = JSON.stringify(p);
    if (!force && sig === lastSig) return;
    lastSig = sig;
    try { b.subtitleState(sig); } catch (_) {}
  }

  function estimatedTime() {
    let t = anchorTime;
    const now = nowPerf();
    const age = now - lastClockReceivePerf;
    const canAdvance = !clockPaused && !clockSeeking && !clockWaiting && !clockEnded && clockReadyState >= 2 && age >= 0 && age < 650;
    if (canAdvance) t += Math.max(0, now - anchorPerf) / 1000 * clockRate;
    return Math.max(0, t);
  }

  function applyClock() {
    if (!clockLinked) return false;
    const t = estimatedTime();
    try {
      if (typeof _onlyflixStopProbeV317 === 'function') _onlyflixStopProbeV317();
      if (typeof _onlyflixUseTimeV317 === 'function') {
        _onlyflixUseTimeV317(t);
      } else {
        if (typeof _bridgeTime !== 'undefined') _bridgeTime = t;
        if (typeof _bridgeActive !== 'undefined') _bridgeActive = true;
        if (typeof updateSubtitleOverlay === 'function') updateSubtitleOverlay();
      }
      return true;
    } catch (_) {
      return false;
    }
  }

  window.SubHubNativeClock = function (payload) {
    try { if (typeof payload === 'string') payload = JSON.parse(payload); } catch (_) { return false; }
    payload = payload || {};

    const t = Number(payload.currentTime);
    const seq = Number(payload.seq);
    const source = String(payload.source || '');
    if (!Number.isFinite(t) || t < 0 || t > 50000) return false;
    if (source && source === clockSource && Number.isFinite(seq) && seq <= lastSeq) return false;

    if (source !== clockSource) {
      clockSource = source;
      lastSeq = -1;
    }
    if (Number.isFinite(seq)) lastSeq = seq;

    const now = nowPerf();
    anchorTime = t;
    anchorPerf = now;
    lastClockReceivePerf = now;
    clockPaused = !!payload.paused;
    clockSeeking = !!payload.seeking;
    clockWaiting = !!payload.waiting;
    clockEnded = !!payload.ended;
    clockRate = Number(payload.playbackRate || 1);
    if (!Number.isFinite(clockRate) || clockRate <= 0 || clockRate > 8) clockRate = 1;
    clockReadyState = Number(payload.readyState || 0);
    clockLinked = true;

    applyClock();
    return true;
  };

  function wrap(name, after) {
    try {
      const fn = window[name];
      if (typeof fn !== 'function' || fn.__subhubNativeWrappedV221) return;
      const wrapped = function () {
        const r = fn.apply(this, arguments);
        try { after(); } catch (_) {}
        return r;
      };
      wrapped.__subhubNativeWrappedV221 = true;
      window[name] = wrapped;
    } catch (_) {}
  }

  stampBuild();
  wrap('updateSubtitleOverlay', function () { push(false); });
  wrap('applySubtitleStyle', function () { push(true); });

  setTimeout(function () {
    stampBuild();
    wrap('updateSubtitleOverlay', function () { push(false); });
    wrap('applySubtitleStyle', function () { push(true); });
    push(true);
  }, 800);

  setInterval(function () {
    if (!clockLinked) return;
    if (nowPerf() - lastClockReceivePerf > 1200) return;
    applyClock();
  }, 50);

  return true;
})();