(function () {
  'use strict';
  if (window.__subHubSiteBridgeV223) return true;
  window.__subHubSiteBridgeV223 = true;

  const BRIDGE_BUILD = '322.3.51';

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

  const VIDSRC_GUARD_TOKEN = '__VIDSRC_GUARD_TOKEN__';
  let lastVidSrcGuardState = null;
  let vidSrcGuardObserver = null;

  function isVidSrcFrameActiveV328() {
    try {
      const modal = document.querySelector(
        '#embedPlayerModal.open,' +
        '#embedPlayerModal.inline-player-v265.open'
      );
      if (!modal) return false;

      const frame = document.querySelector(
        '#embedFrameContainer iframe'
      );
      if (!frame) return false;

      const src = String(
        frame.getAttribute('src') ||
        frame.src ||
        ''
      );

      if (!src) return false;
      const u = new URL(src, location.href);
      const h = String(u.hostname || '').toLowerCase();

      return (
        u.protocol === 'https:' &&
        (h === 'vidsrc.to' || h.endsWith('.vidsrc.to'))
      );
    } catch (_) {
      return false;
    }
  }

  function syncVidSrcGuardV328(force) {
    const active = isVidSrcFrameActiveV328();
    if (!force && active === lastVidSrcGuardState) return;
    lastVidSrcGuardState = active;
    if (!active) endVidSrcQualityV3251();

    try {
      if (!active && typeof setVidSrcTakeoverActiveV3222 === 'function') {
        setVidSrcTakeoverActiveV3222(false);
      }
    } catch (_) {}

    try {
      const b = window.SubHubAndroidBridge;
      if (b && typeof b.setVidSrcGuard === 'function') {
        b.setVidSrcGuard(VIDSRC_GUARD_TOKEN, active);
      }
    } catch (_) {}
  }

  function installVidSrcGuardV328() {
    try {
      if (!vidSrcGuardObserver && document.documentElement) {
        vidSrcGuardObserver = new MutationObserver(function () {
          syncVidSrcGuardV328(false);
        });
        vidSrcGuardObserver.observe(document.documentElement, {
          childList: true,
          subtree: true,
          attributes: true,
          attributeFilter: ['class', 'src']
        });
      }
    } catch (_) {}

    syncVidSrcGuardV328(true);
  }

  /*
   * v322.3.9 — VidSrc refuses SubHub's normal sandbox. In the Android app only,
   * replace the owner trial opener so this one provider is rendered without
   * sandbox, while the native WebView guard blocks popup/new-window escapes.
   * The public website is untouched.
   */

  // Commands are sent once to this exact player. A delayed play must never
  // undo a later pause or leak into the next movie.
  function sendVidSrcSafeCommandV3211(command, extra) {
    try {
      const frame = document.querySelector('#embedFrameContainer iframe');
      if (!frame || !frame.contentWindow || !isVidSrcFrameActiveV328()) return false;
      frame.contentWindow.postMessage(Object.assign({
        type: 'SUBHUB_SAFE_PLAYER_V1', command: String(command || '')
      }, extra && typeof extra === 'object' ? extra : {}), '*');
      return true;
    } catch (_) { return false; }
  }

  function nativeTapVidSrcV3213() {
    try {
      const b = window.SubHubAndroidBridge;
      const frame = document.querySelector('#embedFrameContainer iframe');
      if (
        !b ||
        typeof b.tapVidSrc !== 'function' ||
        !frame ||
        typeof isVidSrcFrameActiveV328 !== 'function' ||
        !isVidSrcFrameActiveV328()
      ) return false;

      const rect = frame.getBoundingClientRect();
      const vw = Math.max(1, Number(window.innerWidth || document.documentElement.clientWidth || 1));
      const vh = Math.max(1, Number(window.innerHeight || document.documentElement.clientHeight || 1));
      const cx = Math.max(rect.left + 12, Math.min(rect.right - 12, rect.left + rect.width * 0.5));
      const cy = Math.max(rect.top + 12, Math.min(rect.bottom - 12, rect.top + rect.height * 0.5));
      const nx = Math.max(0.02, Math.min(0.98, cx / vw));
      const ny = Math.max(0.02, Math.min(0.98, cy / vh));

      const shield = document.getElementById('vidfastSafeV294');
      if (shield) shield.style.setProperty('pointer-events', 'none', 'important');

      b.tapVidSrc(VIDSRC_GUARD_TOKEN, nx, ny);

      setTimeout(function () {
        try {
          const s = document.getElementById('vidfastSafeV294');
          if (s) s.style.removeProperty('pointer-events');
        } catch (_) {}
      }, 420);

      return true;
    } catch (_) {
      return false;
    }
  }

  function installVidSrcSafeControlsV3211() {
    try {
      if (window.__subhubVidSrcSafeControlsV3211) return;
      window.__subhubVidSrcSafeControlsV3211 = true;

      const oldToggle = window._vidfastToggleV294;
      const oldSeekDelta = window._vidfastSeekDeltaV294;
      const oldSeekInput = window._vidfastSeekInputV294;

      window._vidfastToggleV294 = function () {
        if (
          typeof isVidSrcFrameActiveV328 === 'function' &&
          isVidSrcFrameActiveV328()
        ) {
          const playing = !!window.__subhubVidSrcPlayingV3222;
          sendVidSrcSafeCommandV3211('toggle');
          try {
            if (typeof _vidfastSetStateV294 === 'function') {
              _vidfastSetStateV294(
                playing ? '⏸ إيقاف…' : '▶ تشغيل…',
                false
              );
            }
          } catch (_) {}
          return;
        }
        if (typeof oldToggle === 'function') {
          return oldToggle.apply(this, arguments);
        }
      };

      window._vidfastSeekDeltaV294 = function (delta) {
        if (
          typeof isVidSrcFrameActiveV328 === 'function' &&
          isVidSrcFrameActiveV328()
        ) {
          const base =
            (typeof _bridgeTime !== 'undefined')
              ? Number(_bridgeTime || 0)
              : 0;
          const dur = Number(window.__subhubVidSrcDurationV3211 || 0);
          let t = Math.max(0, base + Number(delta || 0));
          if (dur > 0) t = Math.min(dur, t);
          sendVidSrcSafeCommandV3211('seek', {time: t});
          try {
            if (typeof _vidfastUiV294 === 'function') {
              _vidfastUiV294(t, dur, false);
            }
          } catch (_) {}
          return;
        }
        if (typeof oldSeekDelta === 'function') {
          return oldSeekDelta.apply(this, arguments);
        }
      };

      window._vidfastSeekInputV294 = function (el) {
        if (
          typeof isVidSrcFrameActiveV328 === 'function' &&
          isVidSrcFrameActiveV328()
        ) {
          const dur = Number(window.__subhubVidSrcDurationV3211 || 0);
          if (!dur || !el) return;
          const t = dur * (Number(el.value || 0) / 1000);
          sendVidSrcSafeCommandV3211('seek', {time: t});
          return;
        }
        if (typeof oldSeekInput === 'function') {
          return oldSeekInput.apply(this, arguments);
        }
      };
    } catch (_) {}
  }

  function installVidSrcCenteredLayoutV3217() {
    try {
      if (document.getElementById('__subhub_vidsrc_layout_v3217')) return;
      const style = document.createElement('style');
      style.id = '__subhub_vidsrc_layout_v3217';
      style.textContent = [
        '#embedPlayerModal .video-modal-box[data-subhub-vidsrc="1"].pseudo-fullscreen{',
        'position:fixed!important;inset:0!important;width:100%!important;height:100%!important;',
        'max-width:none!important;max-height:none!important;margin:0!important;padding:0!important;',
        'border-radius:0!important;background:#000!important;overflow:hidden!important;',
        'box-sizing:border-box!important;',
        '}',
        '#embedPlayerModal .video-modal-box[data-subhub-vidsrc="1"].pseudo-fullscreen #embedFrameContainer{',
        'position:absolute!important;inset:0!important;width:100%!important;height:100%!important;',
        'max-width:none!important;max-height:none!important;aspect-ratio:auto!important;',
        'margin:0!important;padding:0!important;overflow:hidden!important;background:#000!important;',
        'display:flex!important;align-items:center!important;justify-content:center!important;',
        'box-sizing:border-box!important;',
        '}',
        '#embedPlayerModal .video-modal-box[data-subhub-vidsrc="1"].pseudo-fullscreen #embedFrameContainer .screen-frame-stage-v342{',
        'position:absolute!important;left:0!important;top:0!important;width:100%!important;height:100%!important;',
        'margin:0!important;padding:0!important;transform-origin:center center!important;',
        'box-sizing:border-box!important;',
        '}',
        '#embedPlayerModal .video-modal-box[data-subhub-vidsrc="1"].pseudo-fullscreen #embedFrameContainer iframe{',
        'position:absolute!important;inset:0!important;left:0!important;top:0!important;',
        'width:100%!important;height:100%!important;max-width:none!important;max-height:none!important;',
        'margin:0!important;padding:0!important;border:0!important;',
        'box-sizing:border-box!important;',
        '}',
        '#embedPlayerModal .video-modal-box[data-subhub-vidsrc="1"].pseudo-fullscreen .video-top-controls{',
        'position:fixed!important;z-index:2147483000!important;top:10px!important;left:10px!important;',
        '}'
      ].join('');
      (document.head || document.documentElement).appendChild(style);
    } catch (_) {}
  }

  let vidSrcProviderUiSyncTimerV3233 = 0;
  let vidSrcProviderUiInactiveTicksV3233 = 0;

  function ensureVidSrcProviderShortcutsV3231() {
    try {
      const box = document.querySelector('#embedPlayerModal .video-modal-box[data-subhub-vidsrc="1"]');
      const controls = box && box.querySelector('#embedTopBar, .video-top-controls');
      if (!box || !controls) return false;

      if (!document.getElementById('subhub-vidsrc-provider-style-v3233')) {
        const s = document.createElement('style');
        s.id = 'subhub-vidsrc-provider-style-v3233';
        s.textContent = [
          '#embedPlayerModal .video-modal-box[data-subhub-vidsrc="1"] .subhub-provider-shortcut-v3231{',
          'display:inline-flex!important;visibility:visible!important;opacity:1!important;',
          'align-items:center;justify-content:center;min-width:48px;height:44px;',
          'padding:0 10px;border:0;border-radius:999px;background:rgba(8,12,18,.78);color:#fff;',
          'font:800 13px/1 system-ui,sans-serif;backdrop-filter:blur(8px);',
          'pointer-events:auto!important;position:relative;z-index:2147483645!important;}',
          '#embedPlayerModal .video-modal-box[data-subhub-vidsrc="1"] .subhub-provider-shortcut-v3231[data-provider-action="quality"]{min-width:52px;}',
          '#embedPlayerModal .video-modal-box[data-subhub-vidsrc="1"] .subhub-provider-shortcut-v3231[data-provider-action="subs"]{display:none!important;}',
          '#embedPlayerModal .video-modal-box[data-subhub-vidsrc="1"] .subhub-provider-close-hidden-v3233{',
          'display:none!important;visibility:hidden!important;opacity:0!important;',
          'pointer-events:none!important;width:0!important;min-width:0!important;',
          'margin:0!important;padding:0!important;overflow:hidden!important;}'
        ].join('');
        (document.head || document.documentElement).appendChild(s);
      }

      const cc = document.getElementById('embedCcBtn');
      const children = Array.prototype.slice.call(controls.children || []);
      let closeEl = null;

      try {
        closeEl = children.find(function (el) {
          if (!el || el.getAttribute('data-provider-action')) return false;
          if (el === cc || el.id === 'embedCcBtn') return false;
          const txt = String(el.textContent || '').replace(/\s+/g, '').trim();
          const aria = String(el.getAttribute('aria-label') || '');
          const title = String(el.getAttribute('title') || '');
          const action = String(el.getAttribute('onclick') || '');
          return /^[×✕✖xX]$/.test(txt) ||
            /(close|dismiss|إغلاق|اغلاق)/i.test(aria + ' ' + title) || /closeEmbedPlayer/.test(action);
        }) || null;
      } catch (_) {}

      if (closeEl) {
        try {
          closeEl.classList.add('subhub-provider-close-hidden-v3233');
          closeEl.setAttribute('data-subhub-hidden-close-v3233', '1');
          closeEl.style.setProperty('display', 'none', 'important');
          closeEl.style.setProperty('visibility', 'hidden', 'important');
          closeEl.style.setProperty('opacity', '0', 'important');
          closeEl.style.setProperty('pointer-events', 'none', 'important');
        } catch (_) {}
      }

      let q = controls.querySelector('[data-provider-action="quality"]');
      if (!q) {
        q = document.createElement('button');
        q.type = 'button';
        q.className = 'video-top-btn subhub-provider-shortcut-v3231';
        q.setAttribute('data-provider-action', 'quality');
        q.setAttribute('aria-label', 'جودة المصدر');
        q.setAttribute('title', 'جودة المصدر');
        q.textContent = 'الجودة';
        q.addEventListener('click', function (ev) {
          try { ev.preventDefault(); ev.stopPropagation(); } catch (_) {}
          try { toggleVidSrcQualityV3251(); } catch (_) {}
        }, true);
      }

      try {
        q.style.setProperty('display', 'inline-flex', 'important');
        q.style.setProperty('visibility', 'visible', 'important');
        q.style.setProperty('opacity', '1', 'important');
        q.style.setProperty('pointer-events', 'auto', 'important');

        const anchor =
          (closeEl && closeEl.parentElement === controls) ? closeEl :
          (cc && cc.parentElement === controls ? cc : controls.firstElementChild);

        if (q.parentElement !== controls || q.nextElementSibling !== anchor) {
          controls.insertBefore(q, anchor || null);
        }

        // The old close button remains in the DOM but hidden. HD takes its slot.
        q.style.setProperty('order', '', '');
        q.removeAttribute('hidden');
      } catch (_) {}

      try {
        const oldSubs = controls.querySelector('[data-provider-action="subs"]');
        if (oldSubs) {
          oldSubs.style.setProperty('display', 'none', 'important');
          oldSubs.style.setProperty('pointer-events', 'none', 'important');
        }
      } catch (_) {}

      return true;
    } catch (_) {
      return false;
    }
  }

  function startVidSrcProviderUiSyncV3233() {
    try {
      if (vidSrcProviderUiSyncTimerV3233) return true;
      vidSrcProviderUiInactiveTicksV3233 = 0;

      const tick = function () {
        let active = false;
        try {
          const modal = document.querySelector(
            '#embedPlayerModal.open,#embedPlayerModal.inline-player-v265.open'
          );
          const box = document.querySelector(
            '#embedPlayerModal .video-modal-box[data-subhub-vidsrc="1"]'
          );
          active = !!(modal && box);
        } catch (_) {}

        if (active) {
          vidSrcProviderUiInactiveTicksV3233 = 0;
          try { ensureVidSrcProviderShortcutsV3231(); } catch (_) {}
        } else {
          vidSrcProviderUiInactiveTicksV3233 += 1;
          if (vidSrcProviderUiInactiveTicksV3233 >= 6) {
            clearInterval(vidSrcProviderUiSyncTimerV3233);
            vidSrcProviderUiSyncTimerV3233 = 0;
          }
        }
      };

      tick();
      vidSrcProviderUiSyncTimerV3233 = setInterval(tick, 500);
      return true;
    } catch (_) {
      return false;
    }
  }

  function prepareVidSrcSubHubUiV3216() {
    try {
      if (
        typeof isVidSrcFrameActiveV328 !== 'function' ||
        !isVidSrcFrameActiveV328()
      ) return false;

      const box = document.querySelector('#embedPlayerModal .video-modal-box');
      const frame = document.querySelector('#embedFrameContainer iframe');
      const cc = document.getElementById('embedCcBtn');

      installVidSrcCenteredLayoutV3217();

      if (box) {
        try { box.setAttribute('data-subhub-vidsrc', '1'); } catch (_) {}
      }

      if (cc) cc.style.display = 'flex';

      if (box && typeof _moveSubPanelTo === 'function') {
        try { _moveSubPanelTo(box); } catch (_) {}
      }

      if (typeof _mountEmbedSubtitleOverlayV336 === 'function') {
        try { _mountEmbedSubtitleOverlayV336(); } catch (_) {}
      }

      if (typeof applySubtitleStyle === 'function') {
        try { applySubtitleStyle(); } catch (_) {}
      }

      try {
        const overlay = document.getElementById('embedSubtitleOverlay');
        if (overlay) {
          overlay.style.setProperty('z-index', '2147483500', 'important');
          overlay.style.setProperty('pointer-events', 'none', 'important');
          overlay.style.setProperty('touch-action', 'none', 'important');
        }
        const tx = overlay && overlay.querySelector('.sub-text');
        if (tx) {
          tx.style.setProperty('pointer-events', 'none', 'important');
          tx.style.setProperty('touch-action', 'none', 'important');
        }
      } catch (_) {}

      try { bindVidSrcSubtitleDragV3225(); } catch (_) {}
      try { ensureVidSrcSubtitleGestureV3229(); } catch (_) {}
      try { syncVidSrcSubPanelLayoutV3230(); } catch (_) {}
      try { ensureVidSrcProviderShortcutsV3231(); } catch (_) {}
      try { startVidSrcProviderUiSyncV3233(); } catch (_) {}

      if (frame) {
        try { frame.removeAttribute('allowfullscreen'); } catch (_) {}
        try { frame.removeAttribute('webkitallowfullscreen'); } catch (_) {}
        try {
          const allow = String(frame.getAttribute('allow') || '')
            .split(';')
            .map(function (x) { return x.trim(); })
            .filter(function (x) { return x && !/^fullscreen\b/i.test(x); })
            .join('; ');
          frame.setAttribute(
            'allow',
            allow || 'autoplay; encrypted-media; picture-in-picture'
          );
        } catch (_) {}
      }

      if (typeof _autoSelectFirstSub === 'function') {
        Promise.resolve(_autoSelectFirstSub()).catch(function () {});
      }

      try {
        if (typeof setVidSrcTakeoverActiveV3222 === 'function') {
          setVidSrcTakeoverActiveV3222(true);
        }
      } catch (_) {}

      try {
        if (typeof _applyScreenModeV342 === 'function') {
          _applyScreenModeV342(false, false);
        }
      } catch (_) {}

      return true;
    } catch (_) {
      return false;
    }
  }


  let vidSrcTakeoverDraggingV3222 = false;
  let vidSrcTakeoverHideTimerV3222 = 0;
  let vidSrcTakeoverEnabledV3223 = false;
  let vidSrcCaptionOffAtV3227 = 0;

  function requestVidSrcCaptionOffV3227(force) {
    const now = Date.now();
    if (!force && now - vidSrcCaptionOffAtV3227 < 1400) return;
    vidSrcCaptionOffAtV3227 = now;
    try { sendVidSrcSafeCommandV3211('captionoff'); } catch (_) {}
  }


  function wakeVidSrcControlsV3251(box) {
    box.classList.remove('sh-v374-idle');
    box.classList.add('sh-v374-awake');
    try {
      if (typeof _vidsrcSessionV374 !== 'undefined' && _vidsrcSessionV374 && _vidsrcSessionV374.box === box) {
        _vidsrcSessionV374.deadline = performance.now() + 3000;
      }
    } catch (_) {}
  }

  function syncVidSrcViewportControlsV3251(root) {
    const box = root && root.parentElement;
    const frame = document.getElementById('embedFrameContainer');
    if (!box || !frame) return;
    const r = frame.getBoundingClientRect(), b = box.getBoundingClientRect();
    const scaleX = b.width / (box.offsetWidth || b.width || 1);
    const scaleY = b.height / (box.offsetHeight || b.height || 1);
    const values = {
      left: (r.left-b.left)/scaleX + box.scrollLeft - box.clientLeft,
      top: (r.top-b.top)/scaleY + box.scrollTop - box.clientTop,
      width: r.width/scaleX, height: r.height/scaleY
    };
    root.style.setProperty('inset', 'auto', 'important');
    Object.keys(values).forEach(function (key) {
      const value = Math.max(0, values[key]) + 'px';
      if (root.style.getPropertyValue(key) !== value) root.style.setProperty(key, value, 'important');
    });
  }

  function installVidSrcViewportControlsV3251(root, box) {
    const style = document.createElement('style');
    style.id = 'subhub-vidsrc-controls-v3251';
    style.textContent = `
      #subhub-vidsrc-takeover-v3222 .sh-v3251-surface{position:absolute;inset:0;pointer-events:none;touch-action:manipulation}
      #subhub-vidsrc-takeover-v3222.sh-v3251-ready .sh-v3251-surface{pointer-events:auto}
      #embedPlayerModal #subhub-vidsrc-takeover-v3222.sh-v3251-ready .sh-v3222-center{display:flex!important;align-items:center;justify-content:center;visibility:visible!important;opacity:1!important;pointer-events:auto!important;z-index:2;touch-action:manipulation}
      #embedPlayerModal #subhub-vidsrc-takeover-v3222 .sh-v3222-bar{bottom:8px!important;z-index:3;direction:rtl}
      #embedPlayerModal #subhub-vidsrc-takeover-v3222 .sh-v3222-time{white-space:nowrap;min-width:0;font-size:12px}
      #embedPlayerModal .video-modal-box:not(.pseudo-fullscreen) #subhub-vidsrc-takeover-v3222 .sh-v3222-bar{display:flex!important;visibility:visible!important;opacity:1!important;pointer-events:auto!important;flex-wrap:wrap}
      #embedPlayerModal .video-modal-box:not(.pseudo-fullscreen) #subhub-vidsrc-takeover-v3222 .sh-v3222-seek{min-width:60px;width:60px}
      #embedPlayerModal .video-modal-box.sh-v374-idle #subhub-vidsrc-takeover-v3222 .sh-v3222-center,
      #embedPlayerModal #subhub-vidsrc-takeover-v3222.controls-hidden .sh-v3222-center{opacity:0!important;visibility:hidden!important;pointer-events:none!important}
      #embedPlayerModal .video-modal-box.sh-v3251-quality #subhub-vidsrc-takeover-v3222,
      #embedPlayerModal .video-modal-box.sh-v3251-quality #vidsrcWakeV374{display:none!important}
      #embedPlayerModal #embedTopBar .subhub-provider-shortcut-v3231{font-size:12px;min-width:48px;padding:0 6px;flex-shrink:0}
      #embedPlayerModal #embedTopBar .subhub-provider-close-hidden-v3233{display:none!important}
      #subhub-vidsrc-subtitle-gesture-v3229{z-index:2147483502!important}
      #embedPlayerModal #embedTopBar{max-width:calc(100% - 62px);flex-wrap:nowrap}
      #embedPlayerModal .sh-v3251-quality-note{position:absolute;left:12px;right:12px;top:62px;padding:8px;border-radius:10px;background:rgba(8,12,18,.9);color:white;z-index:2147483645;text-align:center;font-size:13px;pointer-events:none}
    `;
    if (!document.getElementById(style.id)) (document.head || document.documentElement).appendChild(style);
    const surface = document.createElement('div');
    surface.className = 'sh-v3251-surface';
    surface.setAttribute('aria-hidden', 'true');
    root.insertBefore(surface, root.firstChild);
    syncVidSrcViewportControlsV3251(root);
    if (typeof ResizeObserver === 'function') {
      const observer = new ResizeObserver(function () { syncVidSrcViewportControlsV3251(root); });
      observer.observe(box);
      const frame = document.getElementById('embedFrameContainer');
      if (frame) observer.observe(frame);
    }
  }

  let vidSrcQualitySessionV3251 = null;
  function endVidSrcQualityV3251() {
    const s = vidSrcQualitySessionV3251;
    if (!s) return;
    vidSrcQualitySessionV3251 = null;
    clearTimeout(s.timer);
    s.box.classList.remove('sh-v3251-quality');
    if (s.button) { s.button.textContent = 'الجودة'; s.button.setAttribute('aria-expanded','false'); }
    wakeVidSrcControlsV3251(s.box);
    if (document.querySelector('#embedFrameContainer iframe') === s.frame) {
      sendVidSrcSafeCommandV3211('providerqualityclose', {requestId:s.id});
    }
  }

  function toggleVidSrcQualityV3251() {
    if (vidSrcQualitySessionV3251) { endVidSrcQualityV3251(); return; }
    const frame = document.querySelector('#embedFrameContainer iframe');
    const box = document.querySelector('#embedPlayerModal .video-modal-box');
    if (!frame || !box || !isVidSrcFrameActiveV328()) return;
    const id = Array.from(crypto.getRandomValues(new Uint32Array(4))).join('-');
    const button = box.querySelector('[data-provider-action="quality"]');
    const s = {id, frame, box, button, timer:0};
    vidSrcQualitySessionV3251 = s;
    wakeVidSrcControlsV3251(box);
    if (button) button.textContent = '…';
    sendVidSrcSafeCommandV3211('providerquality', {requestId:id});
    s.timer = setTimeout(function () {
      if (vidSrcQualitySessionV3251 !== s) return;
      endVidSrcQualityV3251();
      const note = document.createElement('div');
      note.className = 'sh-v3251-quality-note';
      note.textContent = 'لم تظهر إعدادات الجودة لهذا المصدر. حاول بعد بدء الفيديو.';
      box.appendChild(note);
      setTimeout(function () { note.remove(); }, 3500);
    }, 1800);
  }

  window.addEventListener('message', function (ev) {
    const d = ev.data, s = vidSrcQualitySessionV3251;
    if (!s || !d || d.type !== 'SUBHUB_VIDSRC_QUALITY_V3251' || d.requestId !== s.id) return;
    if (!s.frame.isConnected || document.querySelector('#embedFrameContainer iframe') !== s.frame) { endVidSrcQualityV3251(); return; }
    if (d.open) {
      clearTimeout(s.timer);
      s.box.classList.add('sh-v3251-quality');
      if (s.button) { s.button.textContent = 'عودة'; s.button.setAttribute('aria-expanded','true'); }
      s.timer = setTimeout(endVidSrcQualityV3251, 15000);
    } else endVidSrcQualityV3251();
  });

  function fmtVidSrcTimeV3222(v) {
    v = Math.max(0, Number(v || 0));
    const s = Math.floor(v % 60);
    const m = Math.floor(v / 60) % 60;
    const h = Math.floor(v / 3600);
    const mm = h > 0 ? String(m).padStart(2, '0') : String(m);
    return (h > 0 ? h + ':' : '') + mm + ':' + String(s).padStart(2, '0');
  }

  function ensureVidSrcTakeoverV3222() {
    try {
      let root = document.getElementById('subhub-vidsrc-takeover-v3222');
      if (root) { syncVidSrcViewportControlsV3251(root); return root; }

      const box = document.querySelector('#embedPlayerModal .video-modal-box');
      if (!box) return null;

      const style = document.createElement('style');
      style.id = 'subhub-vidsrc-takeover-style-v3222';
      style.textContent = [
        '#subhub-vidsrc-takeover-v3222{position:absolute;inset:0;z-index:2147483000;',
        'display:none;background:transparent;pointer-events:none;touch-action:none;user-select:none;}',
        '#subhub-vidsrc-takeover-v3222 .sh-v3225-more,',
        '#subhub-vidsrc-takeover-v3222 .sh-v3222-center,',
        '#subhub-vidsrc-takeover-v3222 .sh-v3222-bar,',
        '#subhub-vidsrc-takeover-v3222 .sh-v3222-bar *,',
        '#subhub-vidsrc-takeover-v3222 .sh-v3227-menu,',
        '#subhub-vidsrc-takeover-v3222 .sh-v3227-menu *{pointer-events:auto!important;}',
        '#subhub-vidsrc-takeover-v3222.on{display:block;}',
        '#subhub-vidsrc-takeover-v3222 .sh-v3225-more{position:absolute;left:14px;top:14px;',
        'width:54px;height:54px;border-radius:50%;z-index:20;font-size:30px;line-height:1;',
        'background:rgba(8,12,18,.78);backdrop-filter:blur(8px);',
        'visibility:hidden!important;opacity:0!important;pointer-events:none!important;}',
        '#subhub-vidsrc-takeover-v3222 .sh-v3227-menu{position:absolute;left:14px;top:76px;',
        'display:none;gap:8px;align-items:center;padding:8px;border-radius:16px;',
        'background:rgba(7,11,18,.92);backdrop-filter:blur(10px);z-index:21;}',
        '#subhub-vidsrc-takeover-v3222.menu-open-v3227 .sh-v3227-menu{display:flex;}',
        '#subhub-vidsrc-takeover-v3222 .sh-v3227-menu button{width:auto;min-width:48px;height:42px;',
        'padding:0 12px;border-radius:11px;font-size:15px;white-space:nowrap;}',
        '#subhub-vidsrc-takeover-v3222 .sh-v3222-center{position:absolute;left:50%;top:50%;',
        'transform:translate(-50%,-50%);width:76px;height:76px;border-radius:50%;',
        'border:1px solid rgba(255,255,255,.28);background:rgba(0,0,0,.52);color:#fff;',
        'font-size:34px;display:none!important;visibility:hidden!important;opacity:0!important;pointer-events:none!important;}',
        '#subhub-vidsrc-takeover-v3222 .sh-v3222-bar{position:absolute;left:18px;right:18px;bottom:18px;',
        'padding:12px 14px;border-radius:18px;background:linear-gradient(180deg,rgba(0,0,0,.18),rgba(0,0,0,.78));',
        'display:flex;align-items:center;gap:10px;pointer-events:auto;transition:opacity .18s ease;}',
        '#subhub-vidsrc-takeover-v3222.controls-hidden .sh-v3222-bar,',
        '#subhub-vidsrc-takeover-v3222.controls-hidden .sh-v3222-center{opacity:0;pointer-events:none;}',
        '#subhub-vidsrc-takeover-v3222 button{width:50px;height:44px;border:0;border-radius:12px;',
        'background:rgba(12,18,28,.84);color:#fff;font-weight:800;font-size:18px;}',
        '#subhub-vidsrc-takeover-v3222 .sh-v3222-play{display:none!important;font-size:24px;}',
        '#subhub-vidsrc-takeover-v3222 input[type=range]{flex:1;min-width:80px;accent-color:#f4b83f;}',
        '#subhub-vidsrc-takeover-v3222 .sh-v3222-time{min-width:126px;text-align:center;color:#fff;',
        'font:700 15px/1.2 system-ui,sans-serif;direction:ltr;}',
        '.video-modal-box:not(.pseudo-fullscreen) #subhub-vidsrc-takeover-v3222 .sh-v3225-more{',
        'left:8px;top:8px;width:44px;height:44px;font-size:26px;}',
        '#embedPlayerModal .video-modal-box.subhub-menu-open-v3225 .video-top-controls{',
        'display:flex!important;visibility:visible!important;opacity:1!important;',
        'pointer-events:auto!important;z-index:2147483002!important;}',
        '#embedPlayerModal .video-modal-box.subhub-menu-open-v3225 #embedCcBtn{',
        'display:flex!important;visibility:visible!important;opacity:1!important;pointer-events:auto!important;}',
        '#embedPlayerModal .video-modal-box[data-subhub-vidsrc="1"] #embedSubtitleOverlay{',
        'position:absolute!important;z-index:2147483500!important;pointer-events:none!important;',
        'touch-action:none!important;}',
        '#embedPlayerModal .video-modal-box[data-subhub-vidsrc="1"] #embedSubtitleOverlay .sub-text{',
        'pointer-events:none!important;touch-action:none!important;cursor:grab!important;position:relative!important;',
        'z-index:2147483501!important;}',
        '#embedPlayerModal .video-modal-box[data-subhub-vidsrc="1"] #subPanel{',
        'z-index:2147483646!important;pointer-events:auto!important;}',
        '#embedPlayerModal .video-modal-box[data-subhub-vidsrc="1"].pseudo-fullscreen.subhub-subpanel-open-v3230 #embedFrameContainer{',
        'inset:0 0 auto 0!important;width:100%!important;height:62vh!important;',
        'max-height:62vh!important;}',
        '#embedPlayerModal .video-modal-box[data-subhub-vidsrc="1"].pseudo-fullscreen.subhub-subpanel-open-v3230 #subPanel{',
        'position:fixed!important;left:18px!important;right:18px!important;top:auto!important;bottom:6px!important;',
        'width:auto!important;height:36vh!important;max-height:36vh!important;margin:0!important;',
        'overflow-y:auto!important;overflow-x:hidden!important;overscroll-behavior:contain!important;',
        '-webkit-overflow-scrolling:touch!important;',
        'transform:none!important;z-index:2147483646!important;pointer-events:auto!important;}',
        '#embedPlayerModal .video-modal-box[data-subhub-vidsrc="1"] .video-top-controls{',
        'z-index:2147483600!important;}',
        '#embedPlayerModal .video-modal-box[data-subhub-vidsrc="1"].subhub-menu-open-v3225 .video-top-controls{',
        'position:absolute!important;top:8px!important;left:66px!important;right:auto!important;',
        'display:flex!important;visibility:visible!important;opacity:1!important;',
        'pointer-events:auto!important;z-index:2147483600!important;}',
        '.video-modal-box:not(.pseudo-fullscreen) #subhub-vidsrc-takeover-v3222 .sh-v3222-center{',
        'width:62px;height:62px;font-size:28px;}',
        '.video-modal-box:not(.pseudo-fullscreen) #subhub-vidsrc-takeover-v3222 .sh-v3222-bar{',
        'left:10px;right:10px;bottom:8px;padding:8px 9px;gap:6px;border-radius:14px;}',
        '.video-modal-box:not(.pseudo-fullscreen) #subhub-vidsrc-takeover-v3222 button{',
        'width:42px;height:38px;border-radius:10px;font-size:15px;}',
        '.video-modal-box:not(.pseudo-fullscreen) #subhub-vidsrc-takeover-v3222 .sh-v3222-play{font-size:20px;}',
        '.video-modal-box:not(.pseudo-fullscreen) #subhub-vidsrc-takeover-v3222 .sh-v3222-time{',
        'min-width:92px;font-size:12px;}',
        '#embedPlayerModal .video-modal-box[data-subhub-vidsrc="1"].pseudo-fullscreen #subhub-vidsrc-takeover-v3222 .sh-v3222-bar{',
        'min-height:54px;padding-top:8px!important;}',
        '#embedPlayerModal .video-modal-box[data-subhub-vidsrc="1"].pseudo-fullscreen #subhub-vidsrc-takeover-v3222 button[data-sh3222="back"]{',
        'position:absolute!important;left:14px!important;top:-56px!important;}',
        '#embedPlayerModal .video-modal-box[data-subhub-vidsrc="1"].pseudo-fullscreen #subhub-vidsrc-takeover-v3222 button[data-sh3222="play"]{',
        'position:absolute!important;left:50%!important;top:-56px!important;transform:translateX(-50%)!important;}',
        '#embedPlayerModal .video-modal-box[data-subhub-vidsrc="1"].pseudo-fullscreen #subhub-vidsrc-takeover-v3222 button[data-sh3222="forward"]{',
        'position:absolute!important;right:14px!important;top:-56px!important;}'
      ].join('');
      (document.head || document.documentElement).appendChild(style);

      root = document.createElement('div');
      root.id = 'subhub-vidsrc-takeover-v3222';
      root.innerHTML =
        '<button class="sh-v3225-more" type="button" data-sh3222="more" aria-label="المزيد">⋮</button>' +
        '<div class="sh-v3227-menu">' +
          '<button type="button" data-sh3222="subtitles">CC</button>' +
          '<button type="button" data-sh3222="provider-subs">ترجمة المصدر</button>' +
          '<button type="button" data-sh3222="screen">١٠٠٪</button>' +
          '<button type="button" data-sh3222="fullscreen">⛶</button>' +
        '</div>' +
        '<button class="sh-v3222-center" type="button" aria-label="تشغيل">▶</button>' +
        '<div class="sh-v3222-bar">' +
          '<button type="button" data-sh3222="back">−١٠</button>' +
          '<button type="button" class="sh-v3222-play" data-sh3222="play">▶</button>' +
          '<button type="button" data-sh3222="forward">+١٠</button>' +
          '<input class="sh-v3222-seek" type="range" min="0" max="1000" step="1" value="0">' +
          '<div class="sh-v3222-time">٠:٠٠ / --:--</div>' +
        '</div>';

      box.appendChild(root);
      installVidSrcViewportControlsV3251(root, box);

      const wake = function () {
        root.classList.remove('controls-hidden');
        clearTimeout(vidSrcTakeoverHideTimerV3222);
        vidSrcTakeoverHideTimerV3222 = setTimeout(function () {
          if (
            root.classList.contains('on') &&
            !vidSrcTakeoverDraggingV3222 &&
            window.__subhubVidSrcPlayingV3222 &&
            !box.classList.contains('subhub-menu-open-v3225')
          ) root.classList.add('controls-hidden');
        }, 2600);
      };

      root.addEventListener('click', function (ev) {
        const target = ev.target && ev.target.closest ? ev.target.closest('button') : null;
        if (!target) {
          wake();
          wakeVidSrcControlsV3251(box);
          ev.preventDefault();
          ev.stopPropagation();
          return;
        }

        ev.preventDefault();
        ev.stopPropagation();
        wake();
        wakeVidSrcControlsV3251(box);

        const kind = target.getAttribute('data-sh3222');

        if (kind === 'more') {
          const open = !root.classList.contains('menu-open-v3227');
          root.classList.toggle('menu-open-v3227', open);
          root.classList.remove('controls-hidden');
          return;
        }

        if (kind === 'subtitles') {
          try { sendVidSrcSafeCommandV3211('providercaptionsoff'); } catch (_) {}
          try {
            const panel = document.getElementById('subPanel');
            if (panel && panel.parentElement !== box && typeof _moveSubPanelTo === 'function') {
              _moveSubPanelTo(box);
            }
            if (panel) {
              panel.style.setProperty('z-index', '2147483646', 'important');
              panel.style.setProperty('pointer-events', 'auto', 'important');
            }
            if (typeof toggleSubtitleModal === 'function') toggleSubtitleModal();
          } catch (_) {}
          root.classList.remove('menu-open-v3227');
          return;
        }

        if (kind === 'provider-subs') {
          try { sendVidSrcSafeCommandV3211('providersubs'); } catch (_) {}
          root.classList.remove('menu-open-v3227');
          return;
        }

        if (kind === 'screen') {
          try {
            if (typeof _cycleScreenModeV342 === 'function') _cycleScreenModeV342();
            const meta = typeof _screenModeMetaV342 === 'function' ? _screenModeMetaV342() : null;
            if (meta && meta.icon) target.textContent = meta.icon;
          } catch (_) {}
          return;
        }

        if (kind === 'fullscreen') {
          try {
            if (typeof toggleEmbedFullscreen === 'function') toggleEmbedFullscreen();
          } catch (_) {}
          root.classList.remove('menu-open-v3227');
          return;
        }

        if (target.classList.contains('sh-v3222-center') || kind === 'play') {
          sendVidSrcSafeCommandV3211('toggle');
          return;
        }

        const t = Number(window.__subhubVidSrcTimeV3222 || 0);
        const d = Number(window.__subhubVidSrcDurationV3211 || 0);
        if (kind === 'back') {
          sendVidSrcSafeCommandV3211('seek', {time: Math.max(0, t - 10)});
        } else if (kind === 'forward') {
          const next = t + 10;
          sendVidSrcSafeCommandV3211('seek', {time: d > 0 ? Math.min(d, next) : next});
        }
      }, true);

      const seek = root.querySelector('.sh-v3222-seek');
      if (seek) {
        const begin = function () {
          vidSrcTakeoverDraggingV3222 = true;
          wake();
        };
        const end = function () {
          const d = Number(window.__subhubVidSrcDurationV3211 || 0);
          if (d > 0) {
            const t = d * Number(seek.value || 0) / 1000;
            sendVidSrcSafeCommandV3211('seek', {time: t});
          }
          vidSrcTakeoverDraggingV3222 = false;
          wake();
        };
        seek.addEventListener('pointerdown', begin, true);
        seek.addEventListener('touchstart', begin, {passive:true});
        seek.addEventListener('change', end, true);
        seek.addEventListener('pointerup', end, true);
        seek.addEventListener('touchend', end, {passive:true});
      }

      return root;
    } catch (_) {
      return null;
    }
  }

  let vidSrcSubtitleDragTargetV3226 = null;
  let vidSrcGestureHitV3229 = null;
  let vidSrcGestureRafV3229 = 0;
  let vidSrcGestureFontPxV3229 = 0;

  function applyVidSrcGestureFontV3229() {
    try {
      if (
        typeof isVidSrcFrameActiveV328 !== 'function' ||
        !isVidSrcFrameActiveV328() ||
        !vidSrcGestureFontPxV3229
      ) return false;

      const overlay = document.getElementById('embedSubtitleOverlay');
      const tx = overlay && overlay.querySelector('.sub-text');
      if (!tx) return false;

      tx.style.setProperty(
        'font-size',
        Math.max(10, Math.min(120, vidSrcGestureFontPxV3229)) + 'px',
        'important'
      );
      return true;
    } catch (_) {
      return false;
    }
  }

  function ensureVidSrcSubtitleGestureV3229() {
    try {
      if (
        typeof isVidSrcFrameActiveV328 !== 'function' ||
        !isVidSrcFrameActiveV328()
      ) return false;

      const overlay = document.getElementById('embedSubtitleOverlay');
      const tx = overlay && overlay.querySelector('.sub-text');
      const box = document.querySelector('#embedPlayerModal .video-modal-box[data-subhub-vidsrc="1"]');
      if (!overlay || !tx || !box) return false;

      let hit = vidSrcGestureHitV3229;
      if (!hit || !hit.isConnected) {
        hit = document.createElement('div');
        hit.id = 'subhub-vidsrc-subtitle-gesture-v3229';
        hit.setAttribute('aria-label', 'تحريك وتكبير الترجمة');
        hit.style.cssText = [
          'position:fixed',
          'display:none',
          'background:transparent',
          'z-index:2147482990',
          'pointer-events:auto',
          'touch-action:none',
          'user-select:none',
          '-webkit-user-select:none',
          'cursor:grab'
        ].join(';');
        (document.body || document.documentElement).appendChild(hit);
        vidSrcGestureHitV3229 = hit;

        let mode = '';
        let dragStartY = 0;
        let dragStartPos = 7;
        let pinchStartDist = 0;
        let pinchStartFont = 0;

        const readPos = function () {
          try {
            return Math.max(2, Math.min(88, Number(
              (typeof _subSettings === 'object' && _subSettings)
                ? (_subSettings.position || 7)
                : 7
            )));
          } catch (_) {
            return 7;
          }
        };

        const writePos = function (v) {
          v = Math.max(2, Math.min(88, Number(v || 7)));
          try {
            if (typeof _subSettings === 'object' && _subSettings) {
              _subSettings.position = v;
            }
          } catch (_) {}
          try { overlay.style.setProperty('bottom', v + '%', 'important'); } catch (_) {}
          try { if (typeof applySubtitleStyle === 'function') applySubtitleStyle(); } catch (_) {}
          try { applyVidSrcGestureFontV3229(); } catch (_) {}
          try { push(true); } catch (_) {}
        };

        const dist = function (a, b) {
          const dx = Number(a.clientX || 0) - Number(b.clientX || 0);
          const dy = Number(a.clientY || 0) - Number(b.clientY || 0);
          return Math.sqrt(dx * dx + dy * dy);
        };

        hit.addEventListener('touchstart', function (ev) {
          try {
            if (!ev.touches || !ev.touches.length) return;

            if (ev.touches.length >= 2) {
              mode = 'pinch';
              pinchStartDist = Math.max(20, dist(ev.touches[0], ev.touches[1]));
              const cs = getComputedStyle(tx);
              pinchStartFont = Math.max(10, parseFloat(cs.fontSize || '0') || 28);
              if (vidSrcGestureFontPxV3229 > 0) pinchStartFont = vidSrcGestureFontPxV3229;
            } else {
              mode = 'drag';
              dragStartY = ev.touches[0].clientY;
              dragStartPos = readPos();
            }

            ev.preventDefault();
            ev.stopPropagation();
          } catch (_) {}
        }, {capture:true, passive:false});

        hit.addEventListener('touchmove', function (ev) {
          try {
            if (!ev.touches || !ev.touches.length) return;

            if (ev.touches.length >= 2) {
              if (mode !== 'pinch') {
                mode = 'pinch';
                pinchStartDist = Math.max(20, dist(ev.touches[0], ev.touches[1]));
                const cs = getComputedStyle(tx);
                pinchStartFont = Math.max(10, parseFloat(cs.fontSize || '0') || 28);
                if (vidSrcGestureFontPxV3229 > 0) pinchStartFont = vidSrcGestureFontPxV3229;
              }

              const ratio = dist(ev.touches[0], ev.touches[1]) / Math.max(20, pinchStartDist);
              vidSrcGestureFontPxV3229 = Math.max(10, Math.min(120, pinchStartFont * ratio));
              applyVidSrcGestureFontV3229();
              try { push(true); } catch (_) {}
            } else if (mode === 'drag') {
              const h = Math.max(120, box.getBoundingClientRect().height || 0);
              const deltaPct = (dragStartY - ev.touches[0].clientY) / h * 100;
              writePos(dragStartPos + deltaPct);
            }

            ev.preventDefault();
            ev.stopPropagation();
          } catch (_) {}
        }, {capture:true, passive:false});

        const endTouch = function (ev) {
          try {
            if (!ev.touches || ev.touches.length === 0) mode = '';
            ev.preventDefault();
            ev.stopPropagation();
          } catch (_) {}
        };
        hit.addEventListener('touchend', endTouch, {capture:true, passive:false});
        hit.addEventListener('touchcancel', endTouch, {capture:true, passive:false});

        // Mouse/stylus fallback. Android touch uses the touch handlers above.
        let pointerDrag = false;
        let pointerStartY = 0;
        let pointerStartPos = 7;
        hit.addEventListener('pointerdown', function (ev) {
          try {
            if (ev.pointerType === 'touch') return;
            pointerDrag = true;
            pointerStartY = ev.clientY;
            pointerStartPos = readPos();
            hit.setPointerCapture && hit.setPointerCapture(ev.pointerId);
            ev.preventDefault();
            ev.stopPropagation();
          } catch (_) {}
        }, true);
        hit.addEventListener('pointermove', function (ev) {
          if (!pointerDrag || ev.pointerType === 'touch') return;
          try {
            const h = Math.max(120, box.getBoundingClientRect().height || 0);
            const deltaPct = (pointerStartY - ev.clientY) / h * 100;
            writePos(pointerStartPos + deltaPct);
            ev.preventDefault();
            ev.stopPropagation();
          } catch (_) {}
        }, true);
        const pointerEnd = function (ev) {
          if (!pointerDrag || ev.pointerType === 'touch') return;
          pointerDrag = false;
          try { ev.preventDefault(); ev.stopPropagation(); } catch (_) {}
        };
        hit.addEventListener('pointerup', pointerEnd, true);
        hit.addEventListener('pointercancel', pointerEnd, true);

        // If the user changes "حجم الخط" from the normal SubHub panel,
        // release the gesture override so the normal setting remains authoritative.
        document.addEventListener('click', function (ev) {
          try {
            if (!vidSrcGestureFontPxV3229) return;
            const panel = document.getElementById('subPanel');
            if (!panel || !panel.contains(ev.target)) return;
            let row = ev.target && ev.target.closest
              ? ev.target.closest('div,section,label')
              : null;
            let txt = String((row && row.textContent) || '');
            if (!/حجم\s*الخط/.test(txt)) return;
            vidSrcGestureFontPxV3229 = 0;
            try { tx.style.removeProperty('font-size'); } catch (_) {}
          } catch (_) {}
        }, true);
      }

      cancelAnimationFrame(vidSrcGestureRafV3229);
      const sync = function () {
        try {
          const modal = document.getElementById('embedPlayerModal');
          const active =
            modal &&
            getComputedStyle(modal).display !== 'none' &&
            typeof isVidSrcFrameActiveV328 === 'function' &&
            isVidSrcFrameActiveV328();

          const r = tx.getBoundingClientRect();
          if (!active || r.width < 4 || r.height < 4) {
            hit.style.display = 'none';
          } else {
            const padX = 18;
            const padY = 14;
            hit.style.display = 'block';
            hit.style.left = Math.max(0, r.left - padX) + 'px';
            hit.style.top = Math.max(0, r.top - padY) + 'px';
            hit.style.width = Math.max(44, r.width + padX * 2) + 'px';
            hit.style.height = Math.max(38, r.height + padY * 2) + 'px';
          }
        } catch (_) {}
        vidSrcGestureRafV3229 = requestAnimationFrame(sync);
      };
      vidSrcGestureRafV3229 = requestAnimationFrame(sync);

      return true;
    } catch (_) {
      return false;
    }
  }

  function syncVidSrcSubPanelLayoutV3230() {
    try {
      const box = document.querySelector('#embedPlayerModal .video-modal-box[data-subhub-vidsrc="1"]');
      const panel = document.getElementById('subPanel');
      if (!box) return false;

      let open = false;
      if (panel) {
        const cs = getComputedStyle(panel);
        const r = panel.getBoundingClientRect();
        open =
          cs.display !== 'none' &&
          cs.visibility !== 'hidden' &&
          Number(cs.opacity || 1) > 0.01 &&
          r.width > 80 &&
          r.height > 80;
      }

      box.classList.toggle('subhub-subpanel-open-v3230', open);
      return open;
    } catch (_) {
      return false;
    }
  }

  function bindVidSrcSubtitleDragV3225() {

    const overlay = document.getElementById('embedSubtitleOverlay');
    const tx = overlay && overlay.querySelector('.sub-text');
    const box = document.querySelector('#embedPlayerModal .video-modal-box');
    if (!overlay || !tx || !box) return false;
    if (vidSrcSubtitleDragTargetV3226 === overlay) return true;
    vidSrcSubtitleDragTargetV3226 = overlay;

    let dragging = false;
    let startY = 0;
    let startPos = 7;
    let pointerId = null;

    const readPos = function () {
      try {
        return Math.max(2, Math.min(88, Number(
          (typeof _subSettings === 'object' && _subSettings)
            ? (_subSettings.position || 7)
            : 7
        )));
      } catch (_) {
        return 7;
      }
    };

    const writePos = function (v) {
      v = Math.max(2, Math.min(88, Number(v || 7)));
      try {
        if (typeof _subSettings === 'object' && _subSettings) {
          _subSettings.position = v;
        }
      } catch (_) {}

      try {
        overlay.style.setProperty('bottom', v + '%', 'important');
      } catch (_) {}

      try {
        if (typeof applySubtitleStyle === 'function') applySubtitleStyle();
      } catch (_) {}

      try { push(true); } catch (_) {}
    };

    const dragTarget = overlay;
    dragTarget.addEventListener('pointerdown', function (ev) {
      try {
        dragging = true;
        pointerId = ev.pointerId;
        startY = ev.clientY;
        startPos = readPos();
        dragTarget.setPointerCapture && dragTarget.setPointerCapture(pointerId);
        ev.preventDefault();
        ev.stopPropagation();
      } catch (_) {}
    }, true);

    dragTarget.addEventListener('pointermove', function (ev) {
      if (!dragging || (pointerId !== null && ev.pointerId !== pointerId)) return;
      try {
        const h = Math.max(120, box.getBoundingClientRect().height || 0);
        const deltaPct = (startY - ev.clientY) / h * 100;
        writePos(startPos + deltaPct);
        ev.preventDefault();
        ev.stopPropagation();
      } catch (_) {}
    }, true);

    const finish = function (ev) {
      if (!dragging) return;
      dragging = false;
      try {
        if (pointerId !== null && dragTarget.releasePointerCapture) dragTarget.releasePointerCapture(pointerId);
      } catch (_) {}
      pointerId = null;
      try { ev && ev.preventDefault(); ev && ev.stopPropagation(); } catch (_) {}
    };

    dragTarget.addEventListener('pointerup', finish, true);
    dragTarget.addEventListener('pointercancel', finish, true);

    let touchStartY = 0;
    let touchStartPos = 7;
    dragTarget.addEventListener('touchstart', function (ev) {
      try {
        if (!ev.touches || !ev.touches.length) return;
        touchStartY = ev.touches[0].clientY;
        touchStartPos = readPos();
        ev.preventDefault();
        ev.stopPropagation();
      } catch (_) {}
    }, {capture:true, passive:false});

    dragTarget.addEventListener('touchmove', function (ev) {
      try {
        if (!ev.touches || !ev.touches.length) return;
        const h = Math.max(120, box.getBoundingClientRect().height || 0);
        const deltaPct = (touchStartY - ev.touches[0].clientY) / h * 100;
        writePos(touchStartPos + deltaPct);
        ev.preventDefault();
        ev.stopPropagation();
      } catch (_) {}
    }, {capture:true, passive:false});

    dragTarget.addEventListener('touchend', function (ev) {
      try { ev.preventDefault(); ev.stopPropagation(); } catch (_) {}
    }, {capture:true, passive:false});
    return true;
  }


  function updateVidSrcTakeoverV3222(t, d, playing) {
    try {
      window.__subhubVidSrcTimeV3222 = Number(t || 0);
      window.__subhubVidSrcPlayingV3222 = !!playing;
      if (Number.isFinite(Number(d)) && Number(d) > 0) {
        window.__subhubVidSrcDurationV3211 = Number(d);
      }

      const root = ensureVidSrcTakeoverV3222();
      if (!root) return;

      const duration = Number(window.__subhubVidSrcDurationV3211 || 0);
      const current = Number(window.__subhubVidSrcTimeV3222 || 0);
      const time = root.querySelector('.sh-v3222-time');
      const seek = root.querySelector('.sh-v3222-seek');
      const center = root.querySelector('.sh-v3222-center');
      const play = root.querySelector('[data-sh3222="play"]');

      if (time) time.textContent =
        fmtVidSrcTimeV3222(current) + ' / ' +
        (duration > 0 ? fmtVidSrcTimeV3222(duration) : '--:--');

      if (seek && !vidSrcTakeoverDraggingV3222 && duration > 0) {
        seek.value = String(Math.max(0, Math.min(1000, current / duration * 1000)));
      }

      const icon = playing ? '❚❚' : '▶';
      if (center) { center.textContent = icon; center.setAttribute('aria-label', playing ? 'إيقاف مؤقت' : 'تشغيل'); }
      if (play) play.textContent = icon;
      root.classList.toggle('sh-v3251-ready', clockReadyState >= 1);
    } catch (_) {}
  }

  function setVidSrcTakeoverActiveV3222(active) {
    try {
      active = !!active;
      const root = ensureVidSrcTakeoverV3222();
      if (!root) return false;
      if (vidSrcTakeoverEnabledV3223 === active && root.classList.contains('on') === active) {
        return true;
      }
      vidSrcTakeoverEnabledV3223 = active;
      root.classList.toggle('on', active);
      root.classList.remove('controls-hidden');

      sendVidSrcSafeCommandV3211('takeover', {active: !!active});
      if (active) requestVidSrcCaptionOffV3227(true);

      if (active) {
        clearTimeout(vidSrcTakeoverHideTimerV3222);
        vidSrcTakeoverHideTimerV3222 = setTimeout(function () {
          if (window.__subhubVidSrcPlayingV3222) root.classList.add('controls-hidden');
        }, 2600);
      } else {
        clearTimeout(vidSrcTakeoverHideTimerV3222);
      }
      return true;
    } catch (_) {
      return false;
    }
  }

  function setVidSrcImmersiveV3219(enabled) {
    try {
      const b = window.SubHubAndroidBridge;
      if (!b || typeof b.setVidSrcImmersive !== 'function') return false;
      b.setVidSrcImmersive(VIDSRC_GUARD_TOKEN, !!enabled);
      return true;
    } catch (_) {
      return false;
    }
  }

  function installVidSrcPseudoFullscreenV3216() {
    try {
      const current = window.toggleEmbedFullscreen;
      if (
        typeof current !== 'function' ||
        current.__subhubVidSrcPseudoFullscreenV3216
      ) return;

      const wrapped = function () {
        const box = document.querySelector('#embedPlayerModal .video-modal-box');
        const vidsrcActive =
          typeof isVidSrcFrameActiveV328 === 'function' &&
          isVidSrcFrameActiveV328();

        if (!vidsrcActive || !box) {
          return current.apply(this, arguments);
        }

        /*
         * VidSrc + Android WebView freezes the picture when Chromium moves the
         * iframe into a native custom-view fullscreen. Keep the complete SubHub
         * box in the normal WebView and expand it with the existing CSS pseudo
         * fullscreen instead. This also keeps our CC button, subtitle panel and
         * Arabic overlay in the same DOM as the video.
         */
        installVidSrcCenteredLayoutV3217();
        try { box.setAttribute('data-subhub-vidsrc', '1'); } catch (_) {}

        const pseudo = box.classList.contains('pseudo-fullscreen');

        if (pseudo) {
          if (typeof _exitAnyFullscreen === 'function') {
            _exitAnyFullscreen(box);
          } else {
            box.classList.remove('pseudo-fullscreen');
            document.body.classList.remove('pseudo-fs-lock');
          }
          setVidSrcTakeoverActiveV3222(true);
          setVidSrcImmersiveV3219(false);
        } else {
          if (typeof _activatePseudoFullscreen === 'function') {
            _activatePseudoFullscreen(box);
          } else {
            box.classList.add('pseudo-fullscreen');
            document.body.classList.add('pseudo-fs-lock');
          }

          /*
           * Reapply the current SubHub screen mode after the box has its final
           * fullscreen dimensions. This is what makes ١٠٠٪ / ملاءمة / قص /
           * تمديد work in VidSrc fullscreen instead of being overwritten by
           * the fullscreen layout.
           */
          setVidSrcImmersiveV3219(true);
          setVidSrcTakeoverActiveV3222(true);

          setTimeout(function () {
            try {
              if (typeof _applyScreenModeV342 === 'function') {
                _applyScreenModeV342(false, false);
              }
            } catch (_) {}
          }, 0);
        }

        try {
          if (typeof _syncEmbedFsIcon === 'function') _syncEmbedFsIcon();
        } catch (_) {}
      };

      wrapped.__subhubVidSrcPseudoFullscreenV3216 = true;
      window.toggleEmbedFullscreen = wrapped;
    } catch (_) {}
  }

  function installVidSrcNoSandboxV329() {
    try {
      const trial = window.openVidSrcTrialV355;
      if (
        typeof trial !== 'function' ||
        trial.__subhubVidSrcNoSandboxV329
      ) return;

      const wrapped = async function () {
        /*
         * Important: SubHub's page declares several globals with let/const.
         * They are visible by name to scripts but are NOT properties of window.
         * Using window.currentMovie/window.isLoggedIn made the whole VidSrc
         * button look dead even though the small × control still worked.
         */
        const movie =
          (typeof currentMovie !== 'undefined')
            ? currentMovie
            : null;
        const logged =
          (typeof isLoggedIn !== 'undefined')
            ? !!isLoggedIn
            : false;
        const ownerCheck =
          (typeof checkOwnerAccess === 'function')
            ? checkOwnerAccess
            : null;

        const movieId = movie && movie.id;

        if (
          !ownerCheck ||
          !(await ownerCheck()) ||
          !logged ||
          !movie ||
          movie.id !== movieId
        ) return;

        const id =
          (typeof _vidfastMovieIdV302 === 'function')
            ? _vidfastMovieIdV302()
            : '';

        if (
          !id ||
          typeof vidsrcTrialAddedV355 !== 'function' ||
          !vidsrcTrialAddedV355() ||
          typeof openEmbedPlayer !== 'function'
        ) return;

        const url =
          'https://vidsrc.to/embed/movie/' +
          encodeURIComponent(id);

        const originalDetector =
          (typeof isVidFastUrlV293 === 'function')
            ? isVidFastUrlV293
            : null;

        try {
          /*
           * _renderEmbedPlayer already has a tested provider-specific
           * no-sandbox path. Borrow ONLY that switch for VidSrc; do not enable
           * VidFast's overlay/time bridge.
           */
          window.isVidFastUrlV293 = function (candidate) {
            try {
              const u = new URL(String(candidate || ''), location.href);
              const h = String(u.hostname || '').toLowerCase();
              if (h === 'vidsrc.to' || h.endsWith('.vidsrc.to')) return true;
            } catch (_) {}

            return typeof originalDetector === 'function'
              ? originalDetector.apply(this, arguments)
              : false;
          };

          syncVidSrcGuardV328(true);
          /*
           * v322.3.14 — Aloha-style strategy:
           * keep VidSrc's own player controls fully interactive and let Android
           * block popups/external navigation. Do not put the old SubHub/VidFast
           * control shield above this provider; that shield caused stalled
           * playback and broken seeking on VidSrc.
           */
          openEmbedPlayer(url, {
            vidfastNoSandbox: true
          });
          syncVidSrcGuardV328(true);
          installVidSrcPseudoFullscreenV3216();
          prepareVidSrcSubHubUiV3216();
          ensureVidSrcTakeoverV3222();
          setTimeout(prepareVidSrcSubHubUiV3216, 120);
          setTimeout(prepareVidSrcSubHubUiV3216, 500);
        } finally {
          window.isVidFastUrlV293 = originalDetector;
        }
      };

      wrapped.__subhubVidSrcNoSandboxV329 = true;
      window.openVidSrcTrialV355 = wrapped;
    } catch (_) {}
  }

  // Hydrate the subscriber entry with the same native UI setup as the
  // owner entry. The page's existing access checks and opener still run.
  function installVidSrcSubscriberV3250() {
    const original = window.openVidSrcForSubscriberV369;
    if (typeof original !== 'function' || original.__subhubSubscriberV3250) return;
    const wrapped = function () {
      const result = original.apply(this, arguments);
      const frame = document.querySelector('#embedFrameContainer iframe');
      const prepare = function () {
        if (!frame || !frame.isConnected ||
            document.querySelector('#embedFrameContainer iframe') !== frame ||
            !isVidSrcFrameActiveV328()) return;
        syncVidSrcGuardV328(true);
        installVidSrcPseudoFullscreenV3216();
        prepareVidSrcSubHubUiV3216();
        ensureVidSrcTakeoverV3222();
      };
      prepare();
      setTimeout(prepare, 120);
      setTimeout(prepare, 500);
      return result;
    };
    wrapped.__subhubSubscriberV3250 = true;
    window.openVidSrcForSubscriberV369 = wrapped;
  }

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
      /*
       * VidSrc uses the same protected SubHub control skin that was already
       * proven on VidFast, but its actual clock comes from Android's injected
       * player bridge instead of provider postMessage events.
       */
      if (
        typeof isVidSrcFrameActiveV328 === 'function' &&
        isVidSrcFrameActiveV328()
      ) {
        if (typeof _bridgeTime !== 'undefined') _bridgeTime = t;
        if (typeof _bridgeActive !== 'undefined') _bridgeActive = true;

        requestVidSrcCaptionOffV3227(false);
        updateVidSrcTakeoverV3222(
          t,
          Number.isFinite(Number(window.__subhubVidSrcDurationV3211))
            ? Number(window.__subhubVidSrcDurationV3211)
            : 0,
          !clockPaused && !clockEnded
        );

        if (typeof updateSubtitleOverlay === 'function') {
          updateSubtitleOverlay();
        }
        try { bindVidSrcSubtitleDragV3225(); } catch (_) {}
        try { ensureVidSrcSubtitleGestureV3229(); } catch (_) {}
        try { applyVidSrcGestureFontV3229(); } catch (_) {}
        return true;
      }

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

    if (
      typeof isVidSrcFrameActiveV328 === 'function' &&
      isVidSrcFrameActiveV328()
    ) {
      const d = Number(payload.duration || 0);
      if (Number.isFinite(d) && d > 0) {
        window.__subhubVidSrcDurationV3211 = d;
      }
      if (clockReadyState >= 1 && source !== window.__subhubTakeoverSourceV3251) {
        window.__subhubTakeoverSourceV3251 = source;
        sendVidSrcSafeCommandV3211('takeover', {active:true});
      }
      try {
        if (clockReadyState >= 1 && typeof setVidSrcTakeoverActiveV3222 === 'function') {
          setVidSrcTakeoverActiveV3222(true);
        }
      } catch (_) {}

      try {
        if (typeof _vidfastSetStateV294 === 'function') {
          _vidfastSetStateV294(
            clockReadyState >= 2
              ? '🛡️ أدوات SubHub مفعّلة'
              : '⏳ جارٍ تجهيز الفيديو…',
            clockReadyState >= 2
          );
        }
      } catch (_) {}
    }

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

  function normalizeImdbTargetV3249(raw) {
    try {
      let s = String(raw || '').trim();
      if (!s) return '';

      if (/^tt\d{5,12}$/i.test(s)) {
        return 'https://www.imdb.com/title/' + s.toLowerCase() + '/';
      }

      if (/^(?:www\.)?imdb\.com\//i.test(s)) {
        s = 'https://' + s;
      }

      const u = new URL(s, location.href);
      const h = String(u.hostname || '').toLowerCase();
      if (
        (u.protocol === 'https:' || u.protocol === 'http:') &&
        (h === 'imdb.com' || h.endsWith('.imdb.com'))
      ) {
        if (u.protocol === 'http:') u.protocol = 'https:';
        return u.href;
      }
    } catch (_) {}
    return '';
  }

  function openImdbExternalV3249(raw) {
    const url = normalizeImdbTargetV3249(raw);
    if (!url) return false;
    try {
      const b = window.SubHubAndroidBridge;
      if (!b || typeof b.openImdb !== 'function') return false;
      b.openImdb(VIDSRC_GUARD_TOKEN, url);
      return true;
    } catch (_) {
      return false;
    }
  }

  function installImdbExternalOpenV3249() {
    try {
      if (window.__subhubImdbExternalV3249) return;
      window.__subhubImdbExternalV3249 = true;

      document.addEventListener('click', function (ev) {
        try {
          const t = ev && ev.target;
          const a = t && t.closest ? t.closest('a[href]') : null;
          if (!a) return;
          const raw = String(a.getAttribute('href') || a.href || '');
          if (!normalizeImdbTargetV3249(raw)) return;

          ev.preventDefault();
          ev.stopPropagation();
          if (typeof ev.stopImmediatePropagation === 'function') {
            ev.stopImmediatePropagation();
          }
          openImdbExternalV3249(raw);
        } catch (_) {}
      }, true);

      const oldOpen = window.open;
      if (typeof oldOpen === 'function' && !oldOpen.__subhubImdbExternalV3249) {
        const wrappedOpen = function (url) {
          if (openImdbExternalV3249(url)) return null;
          return oldOpen.apply(this, arguments);
        };
        wrappedOpen.__subhubImdbExternalV3249 = true;
        window.open = wrappedOpen;
      }
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
  installImdbExternalOpenV3249();
  installOpeningFeedback();
  installVidSrcGuardV328();
  installVidSrcPseudoFullscreenV3216();
  installVidSrcNoSandboxV329();
  installVidSrcSubscriberV3250();

  wrap(
    'updateSubtitleOverlay',
    function () { push(false); }
  );

  wrap(
    'applySubtitleStyle',
    function () {
      try { applyVidSrcGestureFontV3229(); } catch (_) {}
      push(true);
    }
  );

  setTimeout(function () {
    stampBuild();
    installUiPolishV324();
    installImdbExternalOpenV3249();
    installOpeningFeedback();
    installVidSrcGuardV328();
    installVidSrcPseudoFullscreenV3216();
    installVidSrcNoSandboxV329();
    installVidSrcSubscriberV3250();

    wrap(
      'updateSubtitleOverlay',
      function () { push(false); }
    );

    wrap(
      'applySubtitleStyle',
      function () {
        try { applyVidSrcGestureFontV3229(); } catch (_) {}
        push(true);
      }
    );

    push(true);
  }, 800);

  setInterval(function () {
    syncVidSrcGuardV328(false);
    if (vidSrcQualitySessionV3251) wakeVidSrcControlsV3251(vidSrcQualitySessionV3251.box);
    if (vidSrcQualitySessionV3251 && (!vidSrcQualitySessionV3251.frame.isConnected || document.querySelector('#embedFrameContainer iframe') !== vidSrcQualitySessionV3251.frame)) endVidSrcQualityV3251();
    try { syncVidSrcSubPanelLayoutV3230(); } catch (_) {}
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

