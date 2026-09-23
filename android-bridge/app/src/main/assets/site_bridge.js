(function () {
  'use strict';
  if (window.__subHubSiteBridgeV222) return true;
  window.__subHubSiteBridgeV222 = true;

  const BRIDGE_BUILD = '322.2.2';
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
  let openingBusy = false;
  let openingFallbackTimer = 0;

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

  function ensureOpeningStyle() {
    try {
      if (document.getElementById('subhub-opening-style-v3222')) return;
      const style = document.createElement('style');
      style.id = 'subhub-opening-style-v3222';
      style.textContent = [
        '#watchScreen.subhub-opening-v3222{pointer-events:none!important;cursor:progress!important;}',
        '#watchScreen.subhub-opening-v3222 .watch-screen-poster{filter:brightness(.34) saturate(.9)!important;transition:filter .12s ease;}',
        '#watchScreen.subhub-opening-v3222 .watch-screen-overlay{background:linear-gradient(180deg,rgba(10,14,20,.14),rgba(10,14,20,.62))!important;}',
        '#watchScreen.subhub-opening-v3222 .watch-play-ring{width:76px!important;height:76px!important;}',
        '#watchScreen.subhub-opening-v3222 .watch-play-ring::before{inset:0!important;border:4px solid rgba(255,255,255,.18)!important;border-top-color:var(--accent)!important;border-right-color:var(--accent)!important;opacity:1!important;transform:none!important;animation:subhubOpeningSpin3222 .62s linear infinite!important;}',
        '#watchScreen.subhub-opening-v3222 .watch-play-ring::after{content:"";position:absolute;inset:9px;border-radius:50%;border:3px solid transparent;border-bottom-color:var(--accent2);border-left-color:var(--accent2);animation:subhubOpeningSpinReverse3222 .88s linear infinite;opacity:.95;}',
        '#watchScreen.subhub-opening-v3222 .watch-play-btn{width:48px!important;height:48px!important;background:rgba(10,14,20,.76)!important;border-color:rgba(255,255,255,.22)!important;box-shadow:0 0 22px rgba(255,255,255,.08);}',
        '#watchScreen.subhub-opening-v3222 .watch-play-btn::after{display:none!important;}',
        '#watchScreen.subhub-opening-v3222 .watch-play-btn::before{content:"";width:8px;height:8px;border-radius:50%;background:var(--accent);box-shadow:0 0 0 7px rgba(255,255,255,.07);animation:subhubOpeningPulse3222 .72s ease-in-out infinite alternate;}',
        '.watch-pill.subhub-opening-source-v3222{position:relative!important;pointer-events:none!important;color:transparent!important;border-color:var(--accent)!important;overflow:hidden;}',
        '.watch-pill.subhub-opening-source-v3222 *{visibility:hidden!important;}',
        '.watch-pill.subhub-opening-source-v3222::after{content:"جارٍ الفتح…";position:absolute;inset:0;display:flex;align-items:center;justify-content:center;color:var(--accent);font:inherit;font-weight:800;}',
        '@keyframes subhubOpeningSpin3222{to{transform:rotate(360deg)}}',
        '@keyframes subhubOpeningSpinReverse3222{to{transform:rotate(-360deg)}}',
        '@keyframes subhubOpeningPulse3222{from{transform:scale(.76);opacity:.55}to{transform:scale(1.18);opacity:1}}',
        '@media (prefers-reduced-motion:reduce){#watchScreen.subhub-opening-v3222 .watch-play-ring::before,#watchScreen.subhub-opening-v3222 .watch-play-ring::after,#watchScreen.subhub-opening-v3222 .watch-play-btn::before{animation-duration:1.15s!important;}}'
      ].join('\n');
      (document.head || document.documentElement).appendChild(style);
    } catch (_) {}
  }

  function selectedOnlyFlixIndex() {
    try {
      const sources = window._watchSources || [];
      const idx = (typeof window._watchSelectedIdx === 'number') ? window._watchSelectedIdx : 0;
      const s = sources[idx];
      return s && s.adminKey === 'onlyflix' ? idx : -1;
    } catch (_) { return -1; }
  }

  function clearOpeningFeedback() {
    openingBusy = false;
    if (openingFallbackTimer) {
      clearTimeout(openingFallbackTimer);
      openingFallbackTimer = 0;
    }
    try {
      const screen = document.getElementById('watchScreen');
      if (screen) {
        screen.classList.remove('subhub-opening-v3222');
        screen.removeAttribute('aria-busy');
      }
      document.querySelectorAll('.watch-pill.subhub-opening-source-v3222').forEach(function (pill) {
        pill.classList.remove('subhub-opening-source-v3222');
        pill.removeAttribute('aria-busy');
      });
    } catch (_) {}
  }

  function markOpeningFeedback(idx) {
    ensureOpeningStyle();
    try {
      const screen = document.getElementById('watchScreen');
      if (screen) {
        screen.classList.add('subhub-opening-v3222');
        screen.setAttribute('aria-busy', 'true');
      }
      const pill = document.querySelector('.watch-pill[data-src-idx="' + idx + '"]');
      if (pill) {
        pill.classList.add('subhub-opening-source-v3222');
        pill.setAttribute('aria-busy', 'true');
      }
      openingBusy = true;
    } catch (_) {}
  }

  function playerModalIsOpen() {
    try {
      return !!document.querySelector(
        '#embedPlayerModal.open, #videoPlayerModal.open, #embedPlayerModal.inline-player-v265.open, #videoPlayerModal.inline-player-v265.open'
      );
    } catch (_) { return false; }
  }

  function installOpeningFeedback() {
    ensureOpeningStyle();

    try {
      if (!window.__subhubOpeningObserverV3222 && document.documentElement) {
        window.__subhubOpeningObserverV3222 = new MutationObserver(function () {
          if (openingBusy && playerModalIsOpen()) clearOpeningFeedback();
        });
        window.__subhubOpeningObserverV3222.observe(document.documentElement, {
          childList:true,
          subtree:true,
          attributes:true,
          attributeFilter:['class']
        });
      }
    } catch (_) {}

    try {
      const fn = window.playSelectedWatchSource;
      if (typeof fn !== 'function' || fn.__subhubOpeningWrappedV3222) return;

      const wrapped = function () {
        const idx = selectedOnlyFlixIndex();
        if (idx < 0) return fn.apply(this, arguments);
        if (openingBusy) return;

        const self = this;
        const args = arguments;
        markOpeningFeedback(idx);

        const run = function () {
          try {
            fn.apply(self, args);
            if (playerModalIsOpen()) clearOpeningFeedback();
            else {
              openingFallbackTimer = setTimeout(function () {
                if (!playerModalIsOpen()) clearOpeningFeedback();
              }, 2500);
            }
          } catch (e) {
            clearOpeningFeedback();
            throw e;
          }
        };

        /*
         * One browser paint only: the user sees the response immediately,
         * then the original OnlyFlix action runs. No artificial video delay.
         */
        if (typeof requestAnimationFrame === 'function') requestAnimationFrame(run);
        else setTimeout(run, 0);
      };
      wrapped.__subhubOpeningWrappedV3222 = true;
      window.playSelectedWatchSource = wrapped;
    } catch (_) {}
  }

  function wrap(name, after) {
    try {
      const fn = window[name];
      if (typeof fn !== 'function' || fn.__subhubNativeWrappedV222) return;
      const wrapped = function () {
        const r = fn.apply(this, arguments);
        try { after(); } catch (_) {}
        return r;
      };
      wrapped.__subhubNativeWrappedV222 = true;
      window[name] = wrapped;
    } catch (_) {}
  }

  stampBuild();
  installOpeningFeedback();
  wrap('updateSubtitleOverlay', function () { push(false); });
  wrap('applySubtitleStyle', function () { push(true); });

  setTimeout(function () {
    stampBuild();
    installOpeningFeedback();
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