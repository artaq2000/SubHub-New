(function () {
  'use strict';
  if (window.__subHubSiteBridgeV1) return true;
  window.__subHubSiteBridgeV1 = true;

  let lastSig = '';
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

  window.SubHubNativeClock = function (payload) {
    try { if (typeof payload === 'string') payload = JSON.parse(payload); } catch (_) { return false; }
    payload = payload || {};
    const t = Number(payload.currentTime);
    if (!Number.isFinite(t) || t < 0 || t > 50000) return false;
    try {
      if (typeof _onlyflixStopProbeV317 === 'function') _onlyflixStopProbeV317();
      if (typeof _onlyflixUseTimeV317 === 'function') _onlyflixUseTimeV317(t);
      else {
        if (typeof _bridgeTime !== 'undefined') _bridgeTime = t;
        if (typeof _bridgeActive !== 'undefined') _bridgeActive = true;
        if (typeof updateSubtitleOverlay === 'function') updateSubtitleOverlay();
      }
      push(false);
      return true;
    } catch (_) { return false; }
  };

  function wrap(name, after) {
    try {
      const fn = window[name];
      if (typeof fn !== 'function' || fn.__subhubNativeWrapped) return;
      const wrapped = function () {
        const r = fn.apply(this, arguments);
        try { after(); } catch (_) {}
        return r;
      };
      wrapped.__subhubNativeWrapped = true;
      window[name] = wrapped;
    } catch (_) {}
  }

  wrap('updateSubtitleOverlay', function () { push(false); });
  wrap('applySubtitleStyle', function () { push(true); });
  setTimeout(function () {
    wrap('updateSubtitleOverlay', function () { push(false); });
    wrap('applySubtitleStyle', function () { push(true); });
    push(true);
  }, 800);
  return true;
})();
