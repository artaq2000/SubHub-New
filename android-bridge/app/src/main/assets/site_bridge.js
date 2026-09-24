(function () {
  'use strict';
  if (window.__subHubSiteBridgeV223) return true;
  window.__subHubSiteBridgeV223 = true;

  const BRIDGE_BUILD = '322.3.4';

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
  let onlyflixCoverTimer = 0;

  function nowPerf() {
    try { return performance.now(); } catch (_) { return Date.now(); }
  }

  function stampBuild() {
    try {
      document.documentElement.setAttribute('data-subhub-android-bridge', BRIDGE_BUILD);
      const tag = document.getElementById('ownerVersionTag');
      if (tag && String(tag.textContent || '').indexOf('Android ' + BRIDGE_BUILD) < 0) {
        let base = String(tag.textContent || '')
          .replace(/\s*·\s*Android\s+322(?:\.\d+)*/g, '')
          .trim();
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

    const visible =
      overlay.style.display !== 'none' &&
      !!String(tx.textContent || '').trim();

    const settings =
      (typeof _subSettings === 'object' && _subSettings)
        ? _subSettings
        : {};

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

    const canAdvance =
      !clockPaused &&
      !clockSeeking &&
      !clockWaiting &&
      !clockEnded &&
      clockReadyState >= 2 &&
      age >= 0 &&
      age < 650;

    if (canAdvance) {
      t += Math.max(0, now - anchorPerf) / 1000 * clockRate;
    }

    return Math.max(0, t);
  }

  function applyClock() {
    if (!clockLinked) return false;

    const t = estimatedTime();

    try {
      if (typeof _onlyflixStopProbeV317 === 'function') {
        _onlyflixStopProbeV317();
      }

      if (typeof _onlyflixUseTimeV317 === 'function') {
        _onlyflixUseTimeV317(t);
      } else {
        if (typeof _bridgeTime !== 'undefined') _bridgeTime = t;
        if (typeof _bridgeActive !== 'undefined') _bridgeActive = true;
        if (typeof updateSubtitleOverlay === 'function') {
          updateSubtitleOverlay();
        }
      }

      return true;
    } catch (_) {
      return false;
    }
  }

  window.SubHubNativeClock = function (payload) {
    try {
      if (typeof payload === 'string') payload = JSON.parse(payload);
    } catch (_) {
      return false;
    }

    payload = payload || {};

    /*
     * v322.3.0:
     * Stage messages are not media time. They are used only to hide the
     * intermediate share.cdnm movie card and reveal the real nested player.
     */
    const stage = String(payload.stage || '');
    if (stage) {
      if (
        stage === 'deep-frame-loaded' ||
        stage === 'deep-player-ui-ready'
      ) {
        revealOnlyFlixDeepPlayer(stage);
      }
      return true;
    }

    const t = Number(payload.currentTime);
    const seq = Number(payload.seq);
    const source = String(payload.source || '');

    if (!Number.isFinite(t) || t < 0 || t > 50000) return false;

    if (
      source &&
      source === clockSource &&
      Number.isFinite(seq) &&
      seq <= lastSeq
    ) {
      return false;
    }

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

    if (
      !Number.isFinite(clockRate) ||
      clockRate <= 0 ||
      clockRate > 8
    ) {
      clockRate = 1;
    }

    clockReadyState = Number(payload.readyState || 0);
    clockLinked = true;

    applyClock();
    return true;
  };

  function ensureOpeningStyle() {
    try {
      if (document.getElementById('subhub-opening-style-v3223')) return;

      const style = document.createElement('style');
      style.id = 'subhub-opening-style-v3223';

      style.textContent = [
        '#watchScreen.subhub-opening-v3223{pointer-events:none!important;cursor:progress!important;}',
        '#watchScreen.subhub-opening-v3223 .watch-screen-poster{filter:brightness(.34) saturate(.9)!important;transition:filter .12s ease;}',
        '#watchScreen.subhub-opening-v3223 .watch-screen-overlay{background:linear-gradient(180deg,rgba(10,14,20,.14),rgba(10,14,20,.62))!important;}',
        '#watchScreen.subhub-opening-v3223 .watch-play-ring{width:76px!important;height:76px!important;}',
        '#watchScreen.subhub-opening-v3223 .watch-play-ring::before{inset:0!important;border:4px solid rgba(255,255,255,.18)!important;border-top-color:var(--accent)!important;border-right-color:var(--accent)!important;opacity:1!important;transform:none!important;animation:subhubOpeningSpin3223 .62s linear infinite!important;}',
        '#watchScreen.subhub-opening-v3223 .watch-play-ring::after{content:"";position:absolute;inset:9px;border-radius:50%;border:3px solid transparent;border-bottom-color:var(--accent2);border-left-color:var(--accent2);animation:subhubOpeningSpinReverse3223 .88s linear infinite;opacity:.95;}',
        '#watchScreen.subhub-opening-v3223 .watch-play-btn{width:48px!important;height:48px!important;background:rgba(10,14,20,.76)!important;border-color:rgba(255,255,255,.22)!important;box-shadow:0 0 22px rgba(255,255,255,.08);}',
        '#watchScreen.subhub-opening-v3223 .watch-play-btn::after{display:none!important;}',
        '#watchScreen.subhub-opening-v3223 .watch-play-btn::before{content:"";width:8px;height:8px;border-radius:50%;background:var(--accent);box-shadow:0 0 0 7px rgba(255,255,255,.07);animation:subhubOpeningPulse3223 .72s ease-in-out infinite alternate;}',
        '.watch-pill.subhub-opening-source-v3223{position:relative!important;pointer-events:none!important;color:transparent!important;border-color:var(--accent)!important;overflow:hidden;}',
        '.watch-pill.subhub-opening-source-v3223 *{visibility:hidden!important;}',
        '.watch-pill.subhub-opening-source-v3223::after{content:"جارٍ الفتح…";position:absolute;inset:0;display:flex;align-items:center;justify-content:center;color:var(--accent);font:inherit;font-weight:800;}',

        '#subhub-onlyflix-cover-v3223{position:absolute;inset:0;z-index:68;overflow:hidden;background:#0a0e14;pointer-events:auto;display:flex;align-items:center;justify-content:center;}',
        '#subhub-onlyflix-cover-v3223 .of-cover-poster{position:absolute;inset:0;width:100%;height:100%;object-fit:cover;filter:brightness(.34) saturate(.9);}',
        '#subhub-onlyflix-cover-v3223 .of-cover-shade{position:absolute;inset:0;background:linear-gradient(180deg,rgba(10,14,20,.10),rgba(10,14,20,.62));}',
        '#subhub-onlyflix-cover-v3223 .of-cover-spin{position:relative;width:76px;height:76px;border-radius:50%;border:4px solid rgba(255,255,255,.18);border-top-color:var(--accent);border-right-color:var(--accent);animation:subhubOpeningSpin3223 .62s linear infinite;}',
        '#subhub-onlyflix-cover-v3223 .of-cover-spin::after{content:"";position:absolute;inset:9px;border-radius:50%;border:3px solid transparent;border-bottom-color:var(--accent2);border-left-color:var(--accent2);animation:subhubOpeningSpinReverse3223 .88s linear infinite;}',

        '@keyframes subhubOpeningSpin3223{to{transform:rotate(360deg)}}',
        '@keyframes subhubOpeningSpinReverse3223{to{transform:rotate(-360deg)}}',
        '@keyframes subhubOpeningPulse3223{from{transform:scale(.76);opacity:.55}to{transform:scale(1.18);opacity:1}}',

        '@media (prefers-reduced-motion:reduce){#watchScreen.subhub-opening-v3223 .watch-play-ring::before,#watchScreen.subhub-opening-v3223 .watch-play-ring::after,#watchScreen.subhub-opening-v3223 .watch-play-btn::before,#subhub-onlyflix-cover-v3223 .of-cover-spin,#subhub-onlyflix-cover-v3223 .of-cover-spin::after{animation-duration:1.15s!important;}}'
      ].join('\n');

      (document.head || document.documentElement).appendChild(style);
    } catch (_) {}
  }

  function selectedOnlyFlixIndex() {
    try {
      const sources = window._watchSources || [];
      const idx =
        (typeof window._watchSelectedIdx === 'number')
          ? window._watchSelectedIdx
          : 0;

      const s = sources[idx];

      return s && s.adminKey === 'onlyflix'
        ? idx
        : -1;
    } catch (_) {
      return -1;
    }
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
        screen.classList.remove('subhub-opening-v3223');
        screen.removeAttribute('aria-busy');
      }

      document
        .querySelectorAll('.watch-pill.subhub-opening-source-v3223')
        .forEach(function (pill) {
          pill.classList.remove('subhub-opening-source-v3223');
          pill.removeAttribute('aria-busy');
        });
    } catch (_) {}
  }

  function markOpeningFeedback(idx) {
    ensureOpeningStyle();

    try {
      const screen = document.getElementById('watchScreen');

      if (screen) {
        screen.classList.add('subhub-opening-v3223');
        screen.setAttribute('aria-busy', 'true');
      }

      const pill =
        document.querySelector(
          '.watch-pill[data-src-idx="' + idx + '"]'
        );

      if (pill) {
        pill.classList.add('subhub-opening-source-v3223');
        pill.setAttribute('aria-busy', 'true');
      }

      openingBusy = true;
    } catch (_) {}
  }

  function onlyFlixPosterUrl() {
    try {
      const img =
        document.querySelector(
          '#watchScreen .watch-screen-poster'
        );

      return img
        ? String(img.currentSrc || img.src || '')
        : '';
    } catch (_) {
      return '';
    }
  }

  function ensureOnlyFlixCover() {
    if (!openingBusy) return false;

    try {
      const container =
        document.getElementById('embedFrameContainer');

      if (!container) return false;

      if (
        document.getElementById(
          'subhub-onlyflix-cover-v3223'
        )
      ) {
        return true;
      }

      const cover = document.createElement('div');
      cover.id = 'subhub-onlyflix-cover-v3223';
      cover.setAttribute('aria-busy', 'true');

      const poster = onlyFlixPosterUrl();

      if (poster) {
        const img = document.createElement('img');
        img.className = 'of-cover-poster';
        img.alt = '';
        img.src = poster;
        cover.appendChild(img);
      }

      const shade = document.createElement('div');
      shade.className = 'of-cover-shade';
      cover.appendChild(shade);

      const spin = document.createElement('div');
      spin.className = 'of-cover-spin';
      spin.setAttribute('aria-hidden', 'true');
      cover.appendChild(spin);

      container.style.setProperty(
        'position',
        'relative',
        'important'
      );

      /*
       * This cover is in SubHub's DOM, above the OnlyFlix/CDNM iframe.
       * The intermediate MOVIE/title card keeps working underneath but
       * the subscriber never sees or taps it.
       */
      container.appendChild(cover);

      if (onlyflixCoverTimer) {
        clearTimeout(onlyflixCoverTimer);
      }

      onlyflixCoverTimer = setTimeout(function () {
        /*
         * Safety only. Normal reveal is driven by a deep-player stage.
         */
        revealOnlyFlixDeepPlayer('timeout-fallback');
      }, 11000);

      return true;
    } catch (_) {
      return false;
    }
  }

  function revealOnlyFlixDeepPlayer(reason) {
    try {
      if (onlyflixCoverTimer) {
        clearTimeout(onlyflixCoverTimer);
        onlyflixCoverTimer = 0;
      }

      const cover =
        document.getElementById(
          'subhub-onlyflix-cover-v3223'
        );

      if (cover) cover.remove();

      clearOpeningFeedback();

      const frame =
        document.querySelector(
          '#embedFrameContainer iframe'
        );

      if (frame) {
        frame.style.setProperty(
          'backface-visibility',
          'hidden',
          'important'
        );

        frame.style.setProperty(
          'transform',
          'translateZ(0)',
          'important'
        );

        void frame.offsetWidth;

        requestAnimationFrame(function () {
          frame.style.setProperty(
            'transform',
            'none',
            'important'
          );
        });
      }
    } catch (_) {
      clearOpeningFeedback();
    }
  }

  function playerModalIsOpen() {
    try {
      return !!document.querySelector(
        '#embedPlayerModal.open,' +
        '#videoPlayerModal.open,' +
        '#embedPlayerModal.inline-player-v265.open,' +
        '#videoPlayerModal.inline-player-v265.open'
      );
    } catch (_) {
      return false;
    }
  }

  function installOpeningFeedback() {
    ensureOpeningStyle();

    try {
      if (
        !window.__subhubOpeningObserverV3223 &&
        document.documentElement
      ) {
        window.__subhubOpeningObserverV3223 =
          new MutationObserver(function () {
            if (!openingBusy) return;

            if (playerModalIsOpen()) {
              ensureOnlyFlixCover();
            } else if (
              document.getElementById(
                'subhub-onlyflix-cover-v3223'
              )
            ) {
              revealOnlyFlixDeepPlayer('player-closed');
            }
          });

        window.__subhubOpeningObserverV3223.observe(
          document.documentElement,
          {
            childList: true,
            subtree: true,
            attributes: true,
            attributeFilter: ['class']
          }
        );
      }
    } catch (_) {}

    try {
      const fn = window.playSelectedWatchSource;

      if (
        typeof fn !== 'function' ||
        fn.__subhubOpeningWrappedV3223
      ) {
        return;
      }

      const wrapped = function () {
        const idx = selectedOnlyFlixIndex();

        /*
         * Very important:
         * every non-OnlyFlix source runs the original function untouched.
         */
        if (idx < 0) {
          return fn.apply(this, arguments);
        }

        if (openingBusy) return;

        const self = this;
        const args = arguments;

        markOpeningFeedback(idx);

        const run = function () {
          try {
            fn.apply(self, args);

            /*
             * openEmbedPlayer opens inline synchronously. Put our cover above
             * it in this same task, before share.cdnm's movie card can paint.
             */
            if (playerModalIsOpen()) {
              ensureOnlyFlixCover();
            } else {
              openingFallbackTimer =
                setTimeout(function () {
                  if (playerModalIsOpen()) {
                    ensureOnlyFlixCover();
                  } else {
                    clearOpeningFeedback();
                  }
                }, 300);
            }
          } catch (e) {
            revealOnlyFlixDeepPlayer('open-error');
            throw e;
          }
        };

        /*
         * One paint for immediate click feedback; there is no artificial
         * delay added to the player or media.
         */
        if (typeof requestAnimationFrame === 'function') {
          requestAnimationFrame(run);
        } else {
          setTimeout(run, 0);
        }
      };

      wrapped.__subhubOpeningWrappedV3223 = true;
      window.playSelectedWatchSource = wrapped;
    } catch (_) {}
  }

  function wrap(name, after) {
    try {
      const fn = window[name];

      if (
        typeof fn !== 'function' ||
        fn.__subhubNativeWrappedV223
      ) {
        return;
      }

      const wrapped = function () {
        const r = fn.apply(this, arguments);
        try { after(); } catch (_) {}
        return r;
      };

      wrapped.__subhubNativeWrappedV223 = true;
      window[name] = wrapped;
    } catch (_) {}
  }


  /* v322.3.1 — Android app chrome cleanup.
     The native WebView now handles status-bar insets; this part only simplifies
     the SubHub header and retires the old free-subscription broadcast card. */
  function cleanupLegacyAnnouncementV324() {
    try {
      if (
        typeof window.getNotifStore !== 'function' ||
        typeof window.setNotifStore !== 'function'
      ) return;

      const oldText =
        'أصبح بإمكانكم الآن الاشتراك مجاناً في SubHub';

      const all = window.getNotifStore();
      if (!Array.isArray(all) || !all.length) return;

      const removed = all.filter(function (n) {
        return !!(
          n &&
          n.broadcast &&
          String(n.message || '').indexOf(oldText) >= 0
        );
      });

      if (!removed.length) return;

      if (typeof window.addNotifGone === 'function') {
        window.addNotifGone(
          removed.map(function (n) {
            return String(n.nid || n.id || '');
          }).filter(Boolean)
        );
      }

      window.setNotifStore(
        all.filter(function (n) {
          return removed.indexOf(n) < 0;
        })
      );

      if (typeof window.updateNotifBadge === 'function') {
        window.updateNotifBadge();
      }

      if (typeof window.renderNotifList === 'function') {
        window.renderNotifList('notifList');
        window.renderNotifList('myNotifsPane');
      }
    } catch (_) {}
  }

  function installUiPolishV324() {
    try {
      if (!document.getElementById('subhub-android-ui-v324')) {
        const style = document.createElement('style');
        style.id = 'subhub-android-ui-v324';
        style.textContent = [
          '#brandMark{display:none!important;}',
          '#ownerVersionTag{display:none!important;}',
          '.brand{gap:8px!important;}',
          '.brand-text{min-width:88px!important;flex:1 1 auto!important;}',
          '.brand-name{direction:ltr!important;unicode-bidi:isolate!important;white-space:nowrap!important;}'
        ].join('\n');
        (document.head || document.documentElement).appendChild(style);
      }

      const name = document.querySelector('.brand-name');
      if (name) {
        name.innerHTML = 'Sub<span>Hub</span>';
        name.setAttribute('dir', 'ltr');
      }
    } catch (_) {}

    cleanupLegacyAnnouncementV324();

    try {
      const fn = window.pullBroadcastNotifications;
      if (
        typeof fn === 'function' &&
        !fn.__subhubLegacyBroadcastWrappedV324
      ) {
        const wrapped = async function () {
          const result = await fn.apply(this, arguments);
          cleanupLegacyAnnouncementV324();
          return result;
        };
        wrapped.__subhubLegacyBroadcastWrappedV324 = true;
        window.pullBroadcastNotifications = wrapped;
      }
    } catch (_) {}
  }

  stampBuild();
  installUiPolishV324();
  installOpeningFeedback();

  wrap(
    'updateSubtitleOverlay',
    function () { push(false); }
  );

  wrap(
    'applySubtitleStyle',
    function () { push(true); }
  );

  setTimeout(function () {
    stampBuild();
    installUiPolishV324();
    installOpeningFeedback();

    wrap(
      'updateSubtitleOverlay',
      function () { push(false); }
    );

    wrap(
      'applySubtitleStyle',
      function () { push(true); }
    );

    push(true);
  }, 800);

  setInterval(function () {
    if (!clockLinked) return;

    if (
      nowPerf() - lastClockReceivePerf >
      1200
    ) {
      return;
    }

    applyClock();
  }, 50);

  return true;
})();