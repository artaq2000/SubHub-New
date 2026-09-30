(function () {
  'use strict';
  if (window.__subHubClockInstalledV223) return;
  window.__subHubClockInstalledV223 = true;

  const HOST_RE = /(?:^|\.)(?:onlyflix\.to|cdnm\.ink|cdnmovies-stream\.online|cdnmvs\.online|vidsrc\.to)$/i;

  function isVidSrcChainV3216() {
    try {
      if (/(?:^|\.)vidsrc\.to$/i.test(location.hostname || '')) return true;
      const ancestors = location.ancestorOrigins;
      if (!ancestors) return false;
      for (let i = 0; i < ancestors.length; i++) {
        try {
          const h = new URL(String(ancestors[i] || '')).hostname || '';
          if (/(?:^|\.)vidsrc\.to$/i.test(h)) return true;
        } catch (_) {}
      }
    } catch (_) {}
    return false;
  }

  if (!HOST_RE.test(location.hostname || '') && !isVidSrcChainV3216()) return;

  function isOnlyFlixHost() { return /(?:^|\.)onlyflix\.to$/i.test(location.hostname || ''); }
  function isShareHost() { return /(?:^|\.)share\.cdnm\.ink$/i.test(location.hostname || ''); }
  function isPlayerHost() { return /(?:^|\.)cdnmovies-stream\.online$/i.test(location.hostname || ''); }
  function isVidSrcHostV3215() { return /(?:^|\.)vidsrc\.to$/i.test(location.hostname || ''); }

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

  /*
   * v322.3.11 — safe generic controls for the VidSrc experiment.
   * SubHub's top page never needs cross-origin DOM access: it sends a small
   * command to the first iframe, and this document-start bridge relays it
   * downward until the frame that owns the real <video> handles it.
   */
  const SAFE_PLAYER_TYPE_V3211 = 'SUBHUB_SAFE_PLAYER_V1';

  function safePlayerCommandV3211(raw) {
    let d = raw;
    if (typeof d === 'string') {
      try { d = JSON.parse(d); } catch (_) { return null; }
    }
    if (!d || typeof d !== 'object' || d.type !== SAFE_PLAYER_TYPE_V3211) return null;
    return d;
  }

  function relaySafeCommandDownV3211(d) {
    try {
      document.querySelectorAll('iframe').forEach(function (fr) {
        try {
          if (fr && fr.contentWindow) fr.contentWindow.postMessage(d, '*');
        } catch (_) {}
      });
    } catch (_) {}
  }

  function findSafePlayTargetV3212() {
    const selectors = [
      'button[aria-label*="play" i]',
      '[role="button"][aria-label*="play" i]',
      '[title*="play" i]',
      '.vjs-big-play-button',
      '.jw-icon-playback',
      '.plyr__control--overlaid',
      '[data-plyr="play"]',
      'button.play',
      '.play-button',
      '.big-play-button',
      'button[class*="play"]'
    ];
    for (const sel of selectors) {
      try {
        const list = document.querySelectorAll(sel);
        for (const el of list) {
          const rect = el.getBoundingClientRect();
          const cs = getComputedStyle(el);
          if (rect.width >= 12 && rect.height >= 12 &&
              cs.display !== 'none' && cs.visibility !== 'hidden' &&
              Number(cs.opacity || 1) > 0.05) return el;
        }
      } catch (_) {}
    }
    return null;
  }

  function bootstrapSafePlayV3212() {
    try {
      const videos = document.querySelectorAll('video');
      for (const video of videos) {
        try {
          attach(video);
          chooseBest(video);
          const p = video.play();
          if (p && typeof p.catch === 'function') p.catch(function () {});
          return true;
        } catch (_) {}
      }
    } catch (_) {}

    try {
      const target = findSafePlayTargetV3212();
      if (target) {
        target.click();
        return true;
      }
    } catch (_) {}
    return false;
  }

  const vidSrcTakeoverControlsV3222 = new WeakMap();
  let vidSrcTakeoverActiveV3224 = false;
  let vidSrcCaptionScrubTimerV3224 = 0;
  let vidSrcProviderUiScrubTimerV3234 = 0;
  const vidSrcProviderUiHiddenV3234 = new WeakMap();

  function vidSrcDeepRootsV3226() {
    const roots = [document];
    const seen = new WeakSet();
    for (let i = 0; i < roots.length; i++) {
      const root = roots[i];
      if (!root || seen.has(root)) continue;
      seen.add(root);
      try {
        root.querySelectorAll('*').forEach(function (el) {
          try {
            if (el.shadowRoot && !seen.has(el.shadowRoot)) roots.push(el.shadowRoot);
          } catch (_) {}
        });
      } catch (_) {}
    }
    return roots;
  }

  function vidSrcDeepQueryAllV3226(selector) {
    const out = [];
    const seen = new WeakSet();
    vidSrcDeepRootsV3226().forEach(function (root) {
      try {
        root.querySelectorAll(selector).forEach(function (el) {
          if (!seen.has(el)) {
            seen.add(el);
            out.push(el);
          }
        });
      } catch (_) {}
    });
    return out;
  }

  const vidSrcTrackHookedV3225 = new WeakSet();

  function hookVidSrcTracksV3225(video) {
    if (!video || vidSrcTrackHookedV3225.has(video)) return;
    vidSrcTrackHookedV3225.add(video);

    const disable = function () {
      if (!vidSrcTakeoverActiveV3224) return;
      try {
        const tracks = video.textTracks;
        if (!tracks) return;
        for (let i = 0; i < tracks.length; i++) {
          try { tracks[i].mode = 'disabled'; } catch (_) {}
        }
      } catch (_) {}
    };

    try {
      const tracks = video.textTracks;
      if (tracks && typeof tracks.addEventListener === 'function') {
        tracks.addEventListener('change', disable);
        tracks.addEventListener('addtrack', function () {
          setTimeout(disable, 0);
          setTimeout(disable, 50);
          setTimeout(disable, 180);
        });
      }
    } catch (_) {}

    ['loadedmetadata','loadeddata','canplay','play','playing','timeupdate','seeked'].forEach(function (name) {
      try { video.addEventListener(name, disable, true); } catch (_) {}
    });

    disable();
  }

  // 322.3.50: player state classes (e.g. jw-flag-captions-enabled) are
  // not caption overlays. Protect media and every composed-tree ancestor.
  const vidSrcCaptionStylesV3250 = new Map();
  function vidSrcMediaAncestorsV3250() {
    const protectedNodes = new Set([document.documentElement, document.body]);
    vidSrcDeepQueryAllV3226('video,iframe,object,embed,media-player').forEach(function (media) {
      let node = media;
      while (node && !protectedNodes.has(node)) {
        protectedNodes.add(node);
        node = node.parentNode || node.host || null;
      }
    });
    return protectedNodes;
  }
  function restoreVidSrcCaptionNodeV3250(el) {
    const old = vidSrcCaptionStylesV3250.get(el);
    if (!old) return;
    old.forEach(function (entry) {
      if (entry[1]) el.style.setProperty(entry[0], entry[1], entry[2]);
      else el.style.removeProperty(entry[0]);
    });
    el.removeAttribute('data-subhub-hidden-caption-v3250');
    el.removeAttribute('data-subhub-hidden-overlay-v3224');
    vidSrcCaptionStylesV3250.delete(el);
  }
  function protectVidSrcCaptionMediaV3250(protectedNodes) {
    vidSrcCaptionStylesV3250.forEach(function (_, el) {
      if (!el.isConnected) vidSrcCaptionStylesV3250.delete(el);
      else if (protectedNodes.has(el)) restoreVidSrcCaptionNodeV3250(el);
    });
  }
  function hideVidSrcCaptionNodeV3250(el, protectedNodes) {
    if (!el || protectedNodes.has(el)) return;
    if (!vidSrcCaptionStylesV3250.has(el)) {
      vidSrcCaptionStylesV3250.set(el, ['display', 'visibility', 'opacity'].map(function (name) {
        return [name, el.style.getPropertyValue(name), el.style.getPropertyPriority(name)];
      }));
    }
    el.setAttribute('data-subhub-hidden-caption-v3250', '1');
    el.style.setProperty('display', 'none', 'important');
    el.style.setProperty('visibility', 'hidden', 'important');
    el.style.setProperty('opacity', '0', 'important');
  }

  function scrubVidSrcProviderCaptionsV3224() {
    if (!vidSrcTakeoverActiveV3224 || vidSrcProviderCaptionsAllowedV3231) return;
    const protectedNodes = vidSrcMediaAncestorsV3250();
    protectVidSrcCaptionMediaV3250(protectedNodes);

    try {
      vidSrcDeepQueryAllV3226('video').forEach(function (video) {
        try {
          hookVidSrcTracksV3225(video);
          const tracks = video.textTracks;
          if (tracks) {
            for (let i = 0; i < tracks.length; i++) {
              try { tracks[i].mode = 'disabled'; } catch (_) {}
            }
          }
        } catch (_) {}

        try {
          video.querySelectorAll('track').forEach(function (tr) {
            try {
              tr.default = false;
              tr.removeAttribute('default');
              if (tr.track) tr.track.mode = 'disabled';
            } catch (_) {}
          });
        } catch (_) {}
      });
    } catch (_) {}

    // Common caption renderers used by embedded players.
    try {
      vidSrcDeepQueryAllV3226(
        '.vjs-text-track-display,.jw-text-track-display,.jw-captions,' +
        '.plyr__captions,.shaka-text-container,' +
        '[class*="subtitle" i],[class*="caption" i],[class*="text-track" i],' +
        '[class*="cue" i],[data-testid*="subtitle" i],[data-testid*="caption" i]'
      ).forEach(function (el) {
        try {
          hideVidSrcCaptionNodeV3250(el, protectedNodes);
        } catch (_) {}
      });
    } catch (_) {}

    /*
     * Some VidSrc skins draw captions in a generic absolutely-positioned box
     * without a useful class name. While SubHub takeover is active, hide only
     * compact text overlays sitting over the lower/central area of the video.
     * Provider controls are already replaced by SubHub at this point.
     */
    try {
      const video =
        (activeVideo && activeVideo.isConnected ? activeVideo : null) ||
        vidSrcDeepQueryAllV3226('video')[0] ||
        null;
      if (video) {
        const vr = video.getBoundingClientRect();
        const minY = vr.top + vr.height * 0.28;
        const maxY = vr.bottom - 2;

        vidSrcDeepQueryAllV3226('div,span,p').forEach(function (el) {
          try {
            if (!el || protectedNodes.has(el)) return;
            const txt = String(el.textContent || '').replace(/\s+/g, ' ').trim();
            if (!txt || txt.length > 220) return;

            const cs = getComputedStyle(el);
            if (cs.display === 'none' || cs.visibility === 'hidden') return;
            const r = el.getBoundingClientRect();
            if (r.width < 24 || r.height < 12) return;
            if (r.bottom < minY || r.top > maxY) return;
            if (r.left > vr.right || r.right < vr.left) return;
            if (r.height > vr.height * 0.42) return;
            if (r.width > vr.width * 0.98) return;
            if ((r.width * r.height) > (vr.width * vr.height * 0.42)) return;

            const fs = parseFloat(cs.fontSize || '0') || 0;
            if (fs < 11) return;

            const tag = String(el.tagName || '').toLowerCase();
            if (tag === 'button' || tag === 'a') return;

            hideVidSrcCaptionNodeV3250(el, protectedNodes);
            el.setAttribute('data-subhub-hidden-overlay-v3224', '1');
          } catch (_) {}
        });
      }
    } catch (_) {}
  }

  function scrubVidSrcProviderUiV3234() {
    if (!vidSrcTakeoverActiveV3224 || vidSrcQualityMenuV3251) return;
    const media = activeVidSrcVideoV3227();
    if (!media || Number(media.readyState || 0) < 1) return;
    const protectedNodes = vidSrcMediaAncestorsV3250();

    /*
     * 322.3.35:
     * Hide only the provider's bottom control bars and its rewind/forward
     * shortcuts. Never hide play/pause or the central play target: doing that
     * made a reopened VidSrc player look normal but ignore taps.
     */
    const selector = [
      '.controls',
      '.control-bar',
      '.player-controls',
      '.bottom-controls',
      '.center-controls',
      '.play-pause',
      '.seek-bar',
      '.vjs-control-bar',
      '.jw-controlbar',
      '.jw-display-icon-container',
      '.vjs-big-play-button',
      '.plyr__control--overlaid',
      '.plyr__controls',
      '.shaka-controls-container',
      '.vds-controls',
      'media-control-bar',
      'media-seek-backward-button',
      'media-seek-forward-button',
      '[aria-label*="rewind" i]',
      '[aria-label*="seek backward" i]',
      '[aria-label*="seek forward" i]',
      '[aria-label*="back 10" i]',
      '[aria-label*="forward 10" i]',
      '[title*="rewind" i]',
      '[title*="seek backward" i]',
      '[title*="seek forward" i]',
      '[class*="rewind" i]',
      '[class*="seek-back" i]',
      '[class*="seek-forward" i]'
    ].join(',');

    const roots = typeof vidSrcDeepRootsV3226 === 'function'
      ? vidSrcDeepRootsV3226()
      : [document];

    roots.forEach(function (root) {
      try {
        root.querySelectorAll(selector).forEach(function (el) {
          try {
            if (!el || protectedNodes.has(el)) return;
            if (!vidSrcProviderUiHiddenV3234.has(el)) {
              vidSrcProviderUiHiddenV3234.set(el, {
                display: el.style.getPropertyValue('display') || '',
                displayPriority: el.style.getPropertyPriority('display') || '',
                visibility: el.style.getPropertyValue('visibility') || '',
                visibilityPriority: el.style.getPropertyPriority('visibility') || '',
                opacity: el.style.getPropertyValue('opacity') || '',
                opacityPriority: el.style.getPropertyPriority('opacity') || '',
                pointerEvents: el.style.getPropertyValue('pointer-events') || '',
                pointerEventsPriority: el.style.getPropertyPriority('pointer-events') || ''
              });
            }
            el.style.setProperty('display', 'none', 'important');
            el.style.setProperty('visibility', 'hidden', 'important');
            el.style.setProperty('opacity', '0', 'important');
            el.style.setProperty('pointer-events', 'none', 'important');
            el.setAttribute('data-subhub-provider-ui-hidden-v3234', '1');
          } catch (_) {}
        });
      } catch (_) {}
    });
  }

  function restoreVidSrcProviderUiV3234() {
    try {
      vidSrcDeepQueryAllV3226('[data-subhub-provider-ui-hidden-v3234="1"]').forEach(function (el) {
        try {
          const old = vidSrcProviderUiHiddenV3234.get(el);
          if (old) {
            old.display
              ? el.style.setProperty('display', old.display, old.displayPriority)
              : el.style.removeProperty('display');
            old.visibility
              ? el.style.setProperty('visibility', old.visibility, old.visibilityPriority)
              : el.style.removeProperty('visibility');
            old.opacity
              ? el.style.setProperty('opacity', old.opacity, old.opacityPriority)
              : el.style.removeProperty('opacity');
            old.pointerEvents
              ? el.style.setProperty('pointer-events', old.pointerEvents, old.pointerEventsPriority)
              : el.style.removeProperty('pointer-events');
          } else {
            el.style.removeProperty('display');
            el.style.removeProperty('visibility');
            el.style.removeProperty('opacity');
            el.style.removeProperty('pointer-events');
          }
          el.removeAttribute('data-subhub-provider-ui-hidden-v3234');
        } catch (_) {}
      });
    } catch (_) {}
  }

  function setVidSrcProviderUiScrubV3234(active) {
    clearInterval(vidSrcProviderUiScrubTimerV3234);
    vidSrcProviderUiScrubTimerV3234 = 0;

    if (!active) {
      restoreVidSrcProviderUiV3234();
      return;
    }

    scrubVidSrcProviderUiV3234();
    vidSrcProviderUiScrubTimerV3234 = setInterval(
      scrubVidSrcProviderUiV3234,
      250
    );
  }

  function setVidSrcCaptionScrubV3224(active) {
    vidSrcTakeoverActiveV3224 = !!active;
    clearInterval(vidSrcCaptionScrubTimerV3224);
    vidSrcCaptionScrubTimerV3224 = 0;

    if (!vidSrcTakeoverActiveV3224) {
      vidSrcCaptionStylesV3250.forEach(function (_, el) { restoreVidSrcCaptionNodeV3250(el); });
      return;
    }

    scrubVidSrcProviderCaptionsV3224();
    vidSrcCaptionScrubTimerV3224 = setInterval(
      scrubVidSrcProviderCaptionsV3224,
      80
    );
  }

  function applyVidSrcTakeoverV3222(active) {
    try {
      const html = document.documentElement;
      if (html) {
        html.classList.toggle('subhub-vidsrc-takeover-v3222', !!active);
        if (active && !vidSrcProviderCaptionsAllowedV3231) {
          html.classList.add('subhub-provider-captions-off-v3231');
        }
        if (!active) html.classList.remove('subhub-provider-captions-off-v3231');
      }
      setVidSrcCaptionScrubV3224(!!active);
      setVidSrcProviderUiScrubV3234(!!active);

      let style = document.getElementById('__subhub_vidsrc_takeover_v3222');
      if (!style) {
        style = document.createElement('style');
        style.id = '__subhub_vidsrc_takeover_v3222';
        style.textContent = [
          'html.subhub-vidsrc-takeover-v3222 .vjs-control-bar,',
          'html.subhub-vidsrc-takeover-v3222 .vjs-big-play-button,',
          'html.subhub-vidsrc-takeover-v3222 .jw-controlbar,',
          'html.subhub-vidsrc-takeover-v3222 .jw-display-icon-container,',
          'html.subhub-vidsrc-takeover-v3222.subhub-provider-captions-off-v3231 .jw-captions,',
          'html.subhub-vidsrc-takeover-v3222.subhub-provider-captions-off-v3231 .jw-text-track-display,',
          'html.subhub-vidsrc-takeover-v3222 .plyr__controls,',
          'html.subhub-vidsrc-takeover-v3222 .plyr__control--overlaid,',
          'html.subhub-vidsrc-takeover-v3222.subhub-provider-captions-off-v3231 .plyr__captions,',
          'html.subhub-vidsrc-takeover-v3222.subhub-provider-captions-off-v3231 .vjs-text-track-display,',
          'html.subhub-vidsrc-takeover-v3222.subhub-provider-captions-off-v3231 .shaka-text-container,',
          'html.subhub-vidsrc-takeover-v3222.subhub-provider-captions-off-v3231 [data-subhub-hidden-caption-v3250="1"]{',
          'display:none!important;visibility:hidden!important;opacity:0!important;}',
          'html.subhub-vidsrc-takeover-v3222.subhub-provider-captions-off-v3231 video::cue{',
          'color:transparent!important;background:transparent!important;text-shadow:none!important;}',
          'html.subhub-vidsrc-takeover-v3222.subhub-provider-captions-off-v3231 video::-webkit-media-text-track-container,',
          'html.subhub-vidsrc-takeover-v3222.subhub-provider-captions-off-v3231 video::-webkit-media-text-track-display{',
          'display:none!important;visibility:hidden!important;opacity:0!important;}'
        ].join('');
        (document.head || document.documentElement).appendChild(style);
      }

      document.querySelectorAll('video').forEach(function (video) {
        try {
          if (active) {
            if (!vidSrcTakeoverControlsV3222.has(video)) {
              vidSrcTakeoverControlsV3222.set(video, !!video.controls);
            }
            video.controls = false;

            const tracks = video.textTracks;
            if (tracks) {
              for (let i = 0; i < tracks.length; i++) {
                try { tracks[i].mode = 'disabled'; } catch (_) {}
              }
            }
          } else if (vidSrcTakeoverControlsV3222.has(video)) {
            video.controls = !!vidSrcTakeoverControlsV3222.get(video);
            vidSrcTakeoverControlsV3222.delete(video);
          }
        } catch (_) {}
      });
    } catch (_) {}
  }

  let vidSrcCaptionProbeBusyV3227 = false;
  let vidSrcProviderCaptionsAllowedV3231 = false;

  function activeVidSrcVideoV3227() {
    try {
      if (activeVideo && activeVideo.isConnected) return activeVideo;
      const deep = typeof vidSrcDeepQueryAllV3226 === 'function'
        ? vidSrcDeepQueryAllV3226('video')
        : Array.from(document.querySelectorAll('video'));
      return deep && deep[0] ? deep[0] : null;
    } catch (_) {
      return null;
    }
  }

  function disableVidSrcTracksNowV3227(video) {
    if (!video) return false;
    let changed = false;
    try {
      const tracks = video.textTracks;
      if (tracks) {
        for (let i = 0; i < tracks.length; i++) {
          try {
            if (tracks[i].mode !== 'disabled') {
              tracks[i].mode = 'disabled';
              changed = true;
            }
          } catch (_) {}
        }
      }
    } catch (_) {}

    try {
      video.querySelectorAll && video.querySelectorAll('track').forEach(function (tr) {
        try {
          tr.default = false;
          tr.removeAttribute('default');
          if (tr.track) tr.track.mode = 'disabled';
        } catch (_) {}
      });
    } catch (_) {}

    try {
      if (typeof window.jwplayer === 'function') {
        const p = window.jwplayer();
        if (p && typeof p.setCurrentCaptions === 'function') p.setCurrentCaptions(-1);
      }
    } catch (_) {}

    return changed;
  }

  function deepVisibleV3227(el) {
    try {
      if (!el || !el.getBoundingClientRect) return false;
      const r = el.getBoundingClientRect();
      if (r.width < 2 || r.height < 2) return false;
      const s = getComputedStyle(el);
      return s.display !== 'none' && s.visibility !== 'hidden' && Number(s.opacity || 1) > 0.01;
    } catch (_) {
      return false;
    }
  }

  function deepTextV3227(el) {
    try {
      return String(
        el.textContent ||
        el.getAttribute('aria-label') ||
        el.getAttribute('title') ||
        ''
      ).replace(/\s+/g, ' ').trim().toLowerCase();
    } catch (_) {
      return '';
    }
  }

  function findVidSrcOffDeepV3227() {
    const all = typeof vidSrcDeepQueryAllV3226 === 'function'
      ? vidSrcDeepQueryAllV3226('button,li,[role="menuitem"],[role="option"],div,span')
      : Array.from(document.querySelectorAll('button,li,[role="menuitem"],[role="option"],div,span'));

    for (const el of all) {
      if (!deepVisibleV3227(el)) continue;
      if (deepTextV3227(el) !== 'off') continue;

      const target = el.closest && el.closest('button,li,[role="menuitem"],[role="option"],a');
      return target || el;
    }
    return null;
  }

  function findVidSrcCcDeepV3227() {
    const selectors = [
      'button[aria-label*="caption" i]',
      'button[aria-label*="subtitle" i]',
      '[role="button"][aria-label*="caption" i]',
      '[role="button"][aria-label*="subtitle" i]',
      '.vjs-subs-caps-button',
      '.vjs-captions-button',
      '.jw-icon-cc',
      '.jw-icon-subtitles',
      '[data-plyr="captions"]'
    ];

    const roots = typeof vidSrcDeepRootsV3226 === 'function' ? vidSrcDeepRootsV3226() : [document];
    for (const root of roots) {
      for (const sel of selectors) {
        try {
          const list = root.querySelectorAll(sel);
          for (const el of list) {
            if (deepVisibleV3227(el)) return el;
          }
        } catch (_) {}
      }
    }

    const buttons = typeof vidSrcDeepQueryAllV3226 === 'function'
      ? vidSrcDeepQueryAllV3226('button,[role="button"]')
      : Array.from(document.querySelectorAll('button,[role="button"]'));

    for (const el of buttons) {
      if (!deepVisibleV3227(el)) continue;
      const t = deepTextV3227(el);
      if (t === 'cc' || t === 'subtitles' || t === 'captions') return el;
    }
    return null;
  }

  function forceVidSrcCaptionOffV3227() {
    if (!isVidSrcChainV3216()) return false;
    const video = activeVidSrcVideoV3227();
    if (!video) return false;

    disableVidSrcTracksNowV3227(video);
    scrubVidSrcProviderCaptionsV3224();

    if (vidSrcCaptionProbeBusyV3227) return true;

    const off = findVidSrcOffDeepV3227();
    if (off) {
      try { off.click(); } catch (_) {}
      disableVidSrcTracksNowV3227(video);
      scrubVidSrcProviderCaptionsV3224();
      return true;
    }

    const cc = findVidSrcCcDeepV3227();
    if (!cc) return true;

    vidSrcCaptionProbeBusyV3227 = true;

    let mask = document.getElementById('__subhub_caption_probe_mask_v3227');
    if (!mask) {
      mask = document.createElement('style');
      mask.id = '__subhub_caption_probe_mask_v3227';
      mask.textContent = [
        'html.subhub-caption-probe-v3227 .vjs-menu,',
        'html.subhub-caption-probe-v3227 .jw-settings-menu,',
        'html.subhub-caption-probe-v3227 .jw-settings-submenu,',
        'html.subhub-caption-probe-v3227 .plyr__menu__container,',
        'html.subhub-caption-probe-v3227 [role="menu"]{',
        'opacity:0!important;visibility:visible!important;pointer-events:none!important;}'
      ].join('');
      (document.head || document.documentElement).appendChild(mask);
    }

    try { document.documentElement.classList.add('subhub-caption-probe-v3227'); } catch (_) {}
    try { cc.click(); } catch (_) {}

    const finish = function () {
      try {
        const x = findVidSrcOffDeepV3227();
        if (x) {
          try { x.click(); } catch (_) {}
        }
        disableVidSrcTracksNowV3227(video);
        scrubVidSrcProviderCaptionsV3224();
      } catch (_) {}
    };

    setTimeout(finish, 60);
    setTimeout(finish, 180);
    setTimeout(function () {
      finish();
      try { document.documentElement.classList.remove('subhub-caption-probe-v3227'); } catch (_) {}
      vidSrcCaptionProbeBusyV3227 = false;
    }, 420);

    return true;
  }

  function findVidSrcProviderButtonV3231(kind) {
    const selectors = kind === 'quality'
      ? [
          'button[aria-label*="settings" i]',
          '[role="button"][aria-label*="settings" i]',
          'button[title*="settings" i]',
          '.vjs-settings-control',
          '.jw-icon-settings',
          '[data-plyr="settings"]',
          'button[aria-label*="quality" i]',
          'button[title*="quality" i]',
          '[data-testid*="settings" i]',
          'button[class*="settings" i]'
        ]
      : [
          'button[aria-label*="caption" i]',
          'button[aria-label*="subtitle" i]',
          '[role="button"][aria-label*="caption" i]',
          '[role="button"][aria-label*="subtitle" i]',
          '.vjs-subs-caps-button',
          '.vjs-captions-button',
          '.jw-icon-cc',
          '.jw-icon-subtitles',
          '[data-plyr="captions"]'
        ];

    const roots = typeof vidSrcDeepRootsV3226 === 'function'
      ? vidSrcDeepRootsV3226()
      : [document];

    for (const root of roots) {
      for (const sel of selectors) {
        try {
          const el = root.querySelector(sel);
          if (el) return el;
        } catch (_) {}
      }
    }

    const all = typeof vidSrcDeepQueryAllV3226 === 'function'
      ? vidSrcDeepQueryAllV3226('button,[role="button"]')
      : Array.from(document.querySelectorAll('button,[role="button"]'));

    for (const el of all) {
      const t = deepTextV3227(el);
      if (kind === 'quality') {
        if (t === 'settings' || t.indexOf('setting') >= 0 || t.indexOf('quality') >= 0) return el;
      } else {
        if (t === 'cc' || t.indexOf('subtitle') >= 0 || t.indexOf('caption') >= 0) return el;
      }
    }

    return null;
  }

  let vidSrcQualityMenuV3251 = null;
  function reportVidSrcQualityV3251(id, open) {
    try { window.top.postMessage({type:'SUBHUB_VIDSRC_QUALITY_V3251',requestId:id,open:open}, '*'); } catch (_) {}
  }

  function closeVidSrcQualityV3251() {
    const s = vidSrcQualityMenuV3251;
    if (!s) return;
    vidSrcQualityMenuV3251 = null;
    clearTimeout(s.timer);
    document.removeEventListener('click', s.choose, true);
    document.documentElement.classList.remove('subhub-provider-menu-v3251');
    // Close only a button that explicitly reports that its menu remains open.
    try { if (s.button.getAttribute('aria-expanded') === 'true') s.button.click(); } catch (_) {}
    setVidSrcProviderUiScrubV3234(vidSrcTakeoverActiveV3224);
    reportVidSrcQualityV3251(s.id, false);
  }

  function openVidSrcProviderQualityV3231(d) {
    try {
      // Only the frame containing the selected media should open a menu.
      if (!activeVidSrcVideoV3227() || !d.requestId) return false;
      const b = findVidSrcProviderButtonV3231('quality');
      if (!b) return false;
      closeVidSrcQualityV3251();
      const s = {id:d.requestId, button:b, timer:0, choose:null};
      vidSrcQualityMenuV3251 = s;
      restoreVidSrcProviderUiV3234();
      let style = document.getElementById('subhub-provider-menu-style-v3251');
      if (!style) {
        style = document.createElement('style');
        style.id = 'subhub-provider-menu-style-v3251';
        style.textContent = 'html.subhub-vidsrc-takeover-v3222.subhub-provider-menu-v3251 .vjs-control-bar,html.subhub-vidsrc-takeover-v3222.subhub-provider-menu-v3251 .jw-controlbar,html.subhub-vidsrc-takeover-v3222.subhub-provider-menu-v3251 .plyr__controls{display:flex!important;visibility:visible!important;opacity:1!important;pointer-events:auto!important}';
        (document.head || document.documentElement).appendChild(style);
      }
      document.documentElement.classList.add('subhub-provider-menu-v3251');
      s.choose = function (ev) {
        const el = ev.target && ev.target.closest ? ev.target.closest('button,[role="menuitem"],[role="menuitemradio"],[role="radio"],label') : null;
        const text = String(el && (el.textContent || el.getAttribute('aria-label')) || '').trim();
        if (/^(auto(?:matic)?|تلقائي|(?:٢١٦٠|١٤٤٠|١٠٨٠|٧٢٠|٤٨٠|٣٦٠|٢٤٠|١٤٤|2160|1440|1080|720|480|360|240|144)\s*[pP]?(?:\s*.*)?)$/i.test(text)) {
          setTimeout(function () { if (vidSrcQualityMenuV3251 === s) closeVidSrcQualityV3251(); }, 300);
        }
      };
      document.addEventListener('click', s.choose, true);
      if (b.getAttribute('aria-expanded') !== 'true') b.click();
      s.timer = setTimeout(closeVidSrcQualityV3251, 15000);
      reportVidSrcQualityV3251(s.id, true);
      return true;
    } catch (_) { closeVidSrcQualityV3251(); return false; }
  }

  function openVidSrcProviderSubsV3231() {
    try {
      vidSrcProviderCaptionsAllowedV3231 = true;
      const html = document.documentElement;
      if (html) html.classList.remove('subhub-provider-captions-off-v3231');
      clearInterval(vidSrcCaptionScrubTimerV3224);
      vidSrcCaptionScrubTimerV3224 = 0;

      const b = findVidSrcProviderButtonV3231('subs');
      if (!b) return false;
      b.click();
      return true;
    } catch (_) {
      return false;
    }
  }

  function forceVidSrcProviderCaptionsOffV3231() {
    try {
      vidSrcProviderCaptionsAllowedV3231 = false;
      const html = document.documentElement;
      if (html && vidSrcTakeoverActiveV3224) {
        html.classList.add('subhub-provider-captions-off-v3231');
      }
      setVidSrcCaptionScrubV3224(vidSrcTakeoverActiveV3224);
      forceVidSrcCaptionOffV3227();
      return true;
    } catch (_) {
      return false;
    }
  }

  // Commands and results use Android IPC, independent of provider message handlers.
  // Capture the media methods before provider scripts can wrap play()/pause().
  const vidSrcMediaPlayV3253 = typeof HTMLMediaElement !== 'undefined' ? HTMLMediaElement.prototype.play : null;
  const vidSrcMediaPauseV3253 = typeof HTMLMediaElement !== 'undefined' ? HTMLMediaElement.prototype.pause : null;
  const vidSrcPlaybackTokenV3253 = '__VIDSRC_GUARD_TOKEN__';
  const vidSrcCompletedV3253 = new Set();
  let vidSrcOperationV3253 = null;

  function mediaActionV3253(video, playing) {
    const method = playing ? vidSrcMediaPlayV3253 : vidSrcMediaPauseV3253;
    return method ? method.call(video) : (playing ? video.play() : video.pause());
  }

  function reportVidSrcPlaybackV3253(op, phase, error) {
    if (vidSrcOperationV3253 !== op) return;
    const b = bridge();
    const p = payload(op.video, phase === 'preparing' ? 'waiting' : (op.video.paused ? 'pause' : 'playing'));
    p.type = 'SUBHUB_NATIVE_PLAYBACK_V3253';
    p.requestId = op.id;
    p.phase = phase;
    p.error = error || '';
    p.playing = !op.video.paused && !op.video.ended;
    p.ok = phase === 'complete';
    if (phase !== 'preparing') {
      vidSrcOperationV3253 = null;
      vidSrcCompletedV3253.add(op.id);
      if (vidSrcCompletedV3253.size > 32) vidSrcCompletedV3253.delete(vidSrcCompletedV3253.values().next().value);
    }
    try { b.vidSrcPlaybackResult(vidSrcPlaybackTokenV3253, JSON.stringify(p)); } catch (_) {}
  }

  function abortVidSrcPlaybackV3253(op, error, report) {
    if (vidSrcOperationV3253 !== op) return;
    // pause() cancels an outstanding play promise; it must not start after closing.
    if (op.playing) { try { mediaActionV3253(op.video, false); } catch (_) {} }
    if (report) reportVidSrcPlaybackV3253(op, 'error', error);
    else vidSrcOperationV3253 = null;
  }

  function startVidSrcPlaybackV3253(d, video) {
    if (!d.requestId || d.targetSource !== sourceId || vidSrcCompletedV3253.has(d.requestId)) return;
    if (vidSrcOperationV3253) {
      if (vidSrcOperationV3253.id === d.requestId) return;
      abortVidSrcPlaybackV3253(vidSrcOperationV3253, 'cancelled', false);
    }
    if (!video || !video.isConnected) return;
    // Decide once from the real element, never from the page's cached pause icon.
    const op = {id:d.requestId, video, playing:!!(video.paused || video.ended),
      startedTime:Number(video.currentTime || 0), deadline:Date.now()+Math.min(14700, Number(d.timeoutMs) || 14700)};
    vidSrcOperationV3253 = op;
    try {
      const result = mediaActionV3253(video, op.playing);
      if (!op.playing) {
        reportVidSrcPlaybackV3253(op, video.paused ? 'complete' : 'error', video.paused ? '' : 'pause-failed');
        return;
      }
      // This phase starts only after calling the actual media play method.
      reportVidSrcPlaybackV3253(op, 'preparing');
      if (result && typeof result.then === 'function') {
        result.then(function () {
          if (vidSrcOperationV3253 !== op) return;
          if (!video.isConnected || video !== activeVideo) { abortVidSrcPlaybackV3253(op,'media-replaced',true); return; }
          if (!video.paused && !video.ended && video.readyState >= 2) reportVidSrcPlaybackV3253(op,'complete');
          else abortVidSrcPlaybackV3253(op,'play-interrupted',true);
        }, function (error) {
          if (vidSrcOperationV3253 === op) abortVidSrcPlaybackV3253(op, String(error && error.name || 'play-failed'), true);
        });
      }
    } catch (error) {
      abortVidSrcPlaybackV3253(op, String(error && error.name || 'play-failed'), true);
    }
  }

  function pollVidSrcPlaybackV3253() {
    if (!isVidSrcChainV3216()) return;
    const b = bridge();
    if (!b || typeof b.pollVidSrcPlayback !== 'function') return;
    try {
      const d = JSON.parse(b.pollVidSrcPlayback(vidSrcPlaybackTokenV3253, sourceId) || '{}');
      const op = vidSrcOperationV3253;
      if (op && d.activeRequest !== op.id) abortVidSrcPlaybackV3253(op,'cancelled',false);
      if (d.requestId) startVidSrcPlaybackV3253(d, activeVideo);
    } catch (_) {}
    const op = vidSrcOperationV3253;
    if (!op) return;
    if (!op.video.isConnected || op.video !== activeVideo) { abortVidSrcPlaybackV3253(op,'media-replaced',true); return; }
    if (op.video.error) { abortVidSrcPlaybackV3253(op,'media-error-'+op.video.error.code,true); return; }
    // Progress is a second real confirmation for providers with nonstandard play promises.
    if (!op.video.paused && !op.video.ended && op.video.readyState >= 2
        && Number(op.video.currentTime) > op.startedTime + 0.02 && !op.video.seeking) {
      reportVidSrcPlaybackV3253(op,'complete');
    } else if (Date.now() >= op.deadline) {
      abortVidSrcPlaybackV3253(op,'buffer-timeout',true);
    }
  }

  const vidSrcPlaybackCommandsV3252 = new Map();
  function applyVidSrcPlaybackV3252(d, video) {
    if (d.targetSource !== sourceId) return false;
    if (!d.requestId || typeof d.playing !== 'boolean') return true;
    const previous = vidSrcPlaybackCommandsV3252.get(d.requestId);
    if (previous) {
      if (previous.reply) window.top.postMessage(previous.reply, '*');
      return true;
    }
    const entry = {reply:null};
    vidSrcPlaybackCommandsV3252.set(d.requestId,entry);
    if (vidSrcPlaybackCommandsV3252.size > 32) vidSrcPlaybackCommandsV3252.delete(vidSrcPlaybackCommandsV3252.keys().next().value);
    const finish = function (success) {
      try { if (video) send(video,true,d.playing ? 'playing' : 'pause'); } catch (_) {}
      entry.reply = {type:'SUBHUB_PLAYBACK_ACK_V3252',requestId:d.requestId,source:sourceId,
        playing:!!(video && !video.paused && !video.ended),ok:!!success,seq:seq};
      try { window.top.postMessage(entry.reply,'*'); } catch (_) {}
    };
    if (!video || !video.isConnected || !Number.isFinite(d.expiresAt) || Date.now() > d.expiresAt) { finish(false); return true; }
    try {
      if (d.playing) {
        if (!video.paused && !video.ended) { finish(true); return true; }
        Promise.resolve(video.play()).then(function () { finish(!video.paused); },function () { finish(false); });
      } else {
        video.pause();
        finish(video.paused);
      }
    } catch (_) { finish(false); }
    return true;
  }

  function handleSafeCommandV3211(d) {
    const cmd = String(d.command || '').toLowerCase();
    const v = activeVideo && activeVideo.isConnected ? activeVideo : activeVidSrcVideoV3227();

    if (cmd === 'setplayback') {
      if (!applyVidSrcPlaybackV3252(d, v)) relaySafeCommandDownV3211(d);
      return;
    }
    try {
      if (cmd === 'takeover') {
        applyVidSrcTakeoverV3222(!!d.active);
      } else if (cmd === 'captionoff') {
        if (!vidSrcQualityMenuV3251) forceVidSrcCaptionOffV3227();
      } else if (cmd === 'providerquality') {
        openVidSrcProviderQualityV3231(d);
      } else if (cmd === 'providerqualityclose') {
        if (vidSrcQualityMenuV3251 && vidSrcQualityMenuV3251.id === d.requestId) closeVidSrcQualityV3251();
      } else if (cmd === 'providersubs') {
        openVidSrcProviderSubsV3231();
      } else if (cmd === 'providercaptionsoff') {
        forceVidSrcProviderCaptionsOffV3231();
      } else if (cmd === 'toggle') {
        if (v) {
          if (v.paused || v.ended) {
            const p = v.play();
            if (p && typeof p.catch === 'function') p.catch(function () {});
          } else v.pause();
          send(v, true, 'safe-toggle');
        } else bootstrapSafePlayV3212();
      } else if (cmd === 'play') {
        if (v) {
          const p = v.play();
          if (p && typeof p.catch === 'function') p.catch(function () {});
        } else {
          bootstrapSafePlayV3212();
        }
      } else if (v && cmd === 'pause') {
        v.pause();
        send(v, true, 'safe-pause');
      } else if (v && cmd === 'seek') {
        const t = Number(d.time);
        if (Number.isFinite(t)) {
          const dur = Number(v.duration);
          v.currentTime = Math.max(0, Number.isFinite(dur) && dur > 0 ? Math.min(dur, t) : t);
        }
      } else if (v && cmd === 'getstatus') {
        send(v, true, 'safe-status');
      }
    } catch (_) {}

    relaySafeCommandDownV3211(d);
  }

  try {
    window.addEventListener('message', function (ev) {
      if (!ev || window.parent === window || ev.source !== window.parent) return;
      const d = safePlayerCommandV3211(ev.data);
      if (!d) return;
      handleSafeCommandV3211(d);
    }, true);
  } catch (_) {}

  function bridge() {
    try {
      return window.SubHubAndroidBridge && typeof window.SubHubAndroidBridge.mediaClock === 'function'
        ? window.SubHubAndroidBridge : null;
    } catch (_) { return null; }
  }

  /* v322.2.3 — OnlyFlix/CDNM deep-player handoff.
     share.cdnm.ink shows an intermediate movie card before it creates the
     real cdnmovies-stream player. Advance that wrapper in the background and
     report the deep-player stage to the SubHub page. No other source is touched. */
  const stageSent = new Set();
  const shareInnerTracked = new WeakSet();
  let shareAdvanceClicked = false;

  function sendStage(stage, extra) {
    const key = String(stage || '');
    if (!key || stageSent.has(key)) return;
    const b = bridge();
    if (!b) return;
    stageSent.add(key);
    const p = Object.assign({
      source: sourceId,
      seq: ++seq,
      event: 'stage:' + key,
      stage: key,
      page: location.href,
      host: location.hostname || '',
      currentTime: 0,
      duration: 0,
      paused: true,
      seeking: false,
      waiting: false,
      playbackRate: 1,
      readyState: 0,
      ended: false,
      visibleArea: 0,
      score: 0,
      at: Date.now()
    }, extra || {});
    try { b.mediaClock(JSON.stringify(p)); } catch (_) {}
  }

  function visibleRect(el) {
    try {
      if (!el) return null;
      const cs = getComputedStyle(el);
      if (cs.display === 'none' || cs.visibility === 'hidden' || Number(cs.opacity || 1) <= 0.02) return null;
      const r = el.getBoundingClientRect();
      if (!r || r.width < 28 || r.height < 28) return null;
      if (r.bottom < 0 || r.right < 0 || r.top > innerHeight || r.left > innerWidth) return null;
      return r;
    } catch (_) { return null; }
  }

  function tryAdvanceShareWrapper() {
    if (!isShareHost() || shareAdvanceClicked) return false;
    if (findFrameMatching(/cdnmovies-stream\.online\/imdb\//i)) return false;

    try {
      const nodes = Array.from(document.querySelectorAll(
        'button,a,[role="button"],[onclick],[class*="play"],[id*="play"]'
      ));
      let best = null;
      let bestScore = -1;

      for (const el of nodes) {
        const r = visibleRect(el);
        if (!r) continue;

        const text = String(el.innerText || el.textContent || '').trim().toLowerCase();
        const meta = [
          el.id || '',
          el.className || '',
          el.getAttribute && el.getAttribute('title') || '',
          el.getAttribute && el.getAttribute('aria-label') || ''
        ].join(' ').toLowerCase();

        if (/trailer|preview|advert|ads|menu|more|share|report/.test(text + ' ' + meta)) continue;

        let score = 0;
        if (/play|watch|start|تشغيل|مشاهدة/.test(text + ' ' + meta)) score += 18;
        try {
          if (el.querySelector && el.querySelector('svg,[class*="play"],[id*="play"]')) score += 7;
        } catch (_) {}

        const ratio = r.width / Math.max(1, r.height);
        if (ratio > 0.72 && ratio < 1.38 && r.width >= 44 && r.width <= 150 && r.height <= 150) score += 7;
        if (r.width * r.height >= 2600) score += 3;
        if (r.top > innerHeight * 0.22) score += 2;

        if (score > bestScore) {
          best = el;
          bestScore = score;
        }
      }

      if (!best || bestScore < 9) return false;
      shareAdvanceClicked = true;
      sendStage('share-wrapper-advancing', { score: bestScore });
      try { best.click(); } catch (_) {
        try {
          best.dispatchEvent(new MouseEvent('click', { bubbles:true, cancelable:true, view:window }));
        } catch (_) {}
      }
      return true;
    } catch (_) {
      return false;
    }
  }

  function watchShareInnerPlayer() {
    if (!isShareHost()) return false;
    const frame = findFrameMatching(/cdnmovies-stream\.online\/imdb\//i);
    if (!frame) return false;

    maximizeFrameInCurrentDocument(frame);
    sendStage('deep-frame-found');

    if (!shareInnerTracked.has(frame)) {
      shareInnerTracked.add(frame);
      try {
        frame.addEventListener('load', function () {
          sendStage('deep-frame-loaded');
        }, { once:true });
      } catch (_) {}
      /* If the frame existed before our listener was attached, allow the deep
         document itself to send deep-player-ui-ready below. */
    }
    return true;
  }

  function detectDeepPlayerUi() {
    if (!isPlayerHost()) return;
    try {
      const video = document.querySelector('video');
      const shell = document.querySelector('#player,.player,.oframeplayer,.jwplayer,.video-js,[class*="player"]');
      if (!video && !shell) return;
      requestAnimationFrame(function () {
        requestAnimationFrame(function () {
          sendStage('deep-player-ui-ready', {
            readyState: video ? Number(video.readyState || 0) : 0,
            duration: video && Number.isFinite(Number(video.duration)) ? Number(video.duration) : 0
          });
        });
      });
    } catch (_) {}
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
    if (!force && sig === lastSig && now - lastSentAt < 500) return;
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

  function installVidSrcInteractionGuardV3217() {
    if (!isVidSrcChainV3216()) return;

    try {
      if (!window.__subhubVidSrcWindowOpenV3217) {
        window.__subhubVidSrcWindowOpenV3217 = true;
        window.open = function () { return null; };
      }
    } catch (_) {}

    try {
      const id = '__subhub_vidsrc_controls_v3217';
      if (!document.getElementById(id)) {
        const style = document.createElement('style');
        style.id = id;
        style.textContent = [
          '.vjs-fullscreen-control{display:none!important;}',
          '.jw-icon-fullscreen{display:none!important;}',
          '.plyr__control[data-plyr="fullscreen"]{display:none!important;}',
          'button[aria-label*="fullscreen" i]{display:none!important;}',
          '[role="button"][aria-label*="fullscreen" i]{display:none!important;}',
          '[title*="fullscreen" i]{display:none!important;}',
          '[data-fullscreen]{display:none!important;}'
        ].join('');
        (document.head || document.documentElement).appendChild(style);
      }
    } catch (_) {}

    try {
      if (!window.__subhubVidSrcClickGuardV3217) {
        window.__subhubVidSrcClickGuardV3217 = true;
        document.addEventListener('click', function (ev) {
          try {
            const t = ev && ev.target && ev.target.closest ? ev.target : null;
            if (!t) return;

            const fs = t.closest(
              '.vjs-fullscreen-control,.jw-icon-fullscreen,' +
              '.plyr__control[data-plyr="fullscreen"],' +
              'button[aria-label*="fullscreen" i],' +
              '[role="button"][aria-label*="fullscreen" i],' +
              '[title*="fullscreen" i],[data-fullscreen]'
            );
            if (fs) {
              ev.preventDefault();
              ev.stopImmediatePropagation();
              return;
            }

            const a = t.closest('a[href],a[target="_blank"]');
            if (a) {
              const href = String(a.getAttribute('href') || '');
              if (/^(?:https?:)?\/\//i.test(href) || a.target === '_blank') {
                ev.preventDefault();
                ev.stopImmediatePropagation();
                return;
              }
            }

            const control = t.closest(
              '.vjs-control,.vjs-progress-control,.jw-controlbar,.jw-slider-time,' +
              '.plyr__controls,.plyr__progress,[role="slider"],input[type="range"]'
            );
            if (control) {
              /*
               * Let the control's own target/root handler run first, then stop
               * the click before document/window popunder handlers see it.
               */
              ev.stopImmediatePropagation();
            }
          } catch (_) {}
        }, false);
      }
    } catch (_) {}
  }

  let lastVidSrcCaptionOffV3221 = 0;
  let vidSrcCaptionOffConfirmedV3221 = false;
  let vidSrcCaptionMenuOpenedV3221 = false;

  function isVisibleV3221(el) {
    try {
      if (!el || !el.getBoundingClientRect) return false;
      const r = el.getBoundingClientRect();
      if (r.width < 2 || r.height < 2) return false;
      const s = getComputedStyle(el);
      return s.display !== 'none' &&
        s.visibility !== 'hidden' &&
        Number(s.opacity || 1) > 0.02;
    } catch (_) {
      return false;
    }
  }

  function exactTextV3221(el) {
    try {
      return String(
        el.textContent ||
        el.getAttribute('aria-label') ||
        el.getAttribute('title') ||
        ''
      ).replace(/\s+/g, ' ').trim().toLowerCase();
    } catch (_) {
      return '';
    }
  }

  function findVidSrcOffItemV3221() {
    try {
      const xp = document.evaluate(
        "//*[normalize-space(translate(text(),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'))='off']",
        document,
        null,
        XPathResult.ORDERED_NODE_SNAPSHOT_TYPE,
        null
      );

      for (let i = 0; i < xp.snapshotLength; i++) {
        let el = xp.snapshotItem(i);
        if (!el || !isVisibleV3221(el)) continue;

        let p = el;
        let ok = false;
        for (let depth = 0; p && depth < 9; depth++, p = p.parentElement) {
          const txt = String(p.textContent || '').toLowerCase();
          const cls = String(p.className || '').toLowerCase();
          if (
            txt.includes('subtitles') ||
            txt.includes('subtitle') ||
            txt.includes('captions') ||
            txt.includes('search by language') ||
            cls.includes('subtitle') ||
            cls.includes('caption')
          ) {
            ok = true;
            break;
          }
        }

        if (!ok) continue;

        const target =
          el.closest('button,[role="menuitem"],[role="option"],li,a') ||
          el;
        if (isVisibleV3221(target)) return target;
      }
    } catch (_) {}

    return null;
  }

  function findVidSrcCaptionButtonV3221() {
    const selectors = [
      'button[aria-label*="caption" i]',
      'button[aria-label*="subtitle" i]',
      '[role="button"][aria-label*="caption" i]',
      '[role="button"][aria-label*="subtitle" i]',
      '.vjs-subs-caps-button',
      '.vjs-captions-button',
      '.jw-icon-cc',
      '.jw-icon-subtitles',
      '[data-plyr="captions"]',
      '[class*="subtitle" i][role="button"]',
      '[class*="caption" i][role="button"]'
    ];

    try {
      for (const sel of selectors) {
        const list = document.querySelectorAll(sel);
        for (const el of list) {
          if (isVisibleV3221(el)) return el;
        }
      }
    } catch (_) {}

    try {
      const buttons = document.querySelectorAll('button,[role="button"]');
      for (const el of buttons) {
        if (!isVisibleV3221(el)) continue;
        const t = exactTextV3221(el);
        if (t === 'cc' || t === 'subtitles' || t === 'captions') return el;
      }
    } catch (_) {}

    return null;
  }

  function disableVidSrcTextTracksV3221() {
    try {
      document.querySelectorAll('video').forEach(function (video) {
        try {
          video.querySelectorAll('track').forEach(function (tr) {
            try {
              tr.default = false;
              tr.removeAttribute('default');
              if (tr.track) tr.track.mode = 'disabled';
            } catch (_) {}
          });
        } catch (_) {}

        try {
          const tracks = video.textTracks;
          if (!tracks) return;
          for (let i = 0; i < tracks.length; i++) {
            try { tracks[i].mode = 'disabled'; } catch (_) {}
          }
        } catch (_) {}
      });
    } catch (_) {}

    try {
      if (typeof window.jwplayer === 'function') {
        const player = window.jwplayer();
        if (player && typeof player.setCurrentCaptions === 'function') {
          player.setCurrentCaptions(-1);
        }
      }
    } catch (_) {}
  }

  function clickVidSrcOffV3221() {
    const off = findVidSrcOffItemV3221();
    if (!off) return false;

    try {
      off.click();
      vidSrcCaptionOffConfirmedV3221 = true;
      vidSrcCaptionMenuOpenedV3221 = false;
      disableVidSrcTextTracksV3221();

      /*
       * The menu in VidSrc may stay open after selecting Off. Close it only if
       * its own CC button is still visible; never touch SubHub's CC button,
       * because this code runs inside the provider frame chain only.
       */
      setTimeout(function () {
        try {
          const b = findVidSrcCaptionButtonV3221();
          if (b && findVidSrcOffItemV3221()) b.click();
        } catch (_) {}
      }, 120);

      return true;
    } catch (_) {
      return false;
    }
  }

  function forceVidSrcCaptionsOffV3221() {
    if (!isVidSrcChainV3216()) return;

    disableVidSrcTextTracksV3221();

    if (vidSrcCaptionOffConfirmedV3221) return;

    const now = Date.now();
    if (now - lastVidSrcCaptionOffV3221 < 650) return;
    lastVidSrcCaptionOffV3221 = now;

    if (clickVidSrcOffV3221()) return;

    /*
     * The Off entry does not exist until VidSrc opens its subtitle panel.
     * Open the provider's own CC control once, select Off, then close it.
     */
    if (!vidSrcCaptionMenuOpenedV3221) {
      const cc = findVidSrcCaptionButtonV3221();
      if (cc) {
        try {
          vidSrcCaptionMenuOpenedV3221 = true;
          cc.click();
          setTimeout(clickVidSrcOffV3221, 80);
          setTimeout(clickVidSrcOffV3221, 220);
          setTimeout(function () {
            if (!vidSrcCaptionOffConfirmedV3221) {
              vidSrcCaptionMenuOpenedV3221 = false;
            }
          }, 500);
        } catch (_) {
          vidSrcCaptionMenuOpenedV3221 = false;
        }
      }
    }
  }

  function suppressVidSrcCaptionsV3215() {
    if (!isVidSrcChainV3216()) return;

    try {
      document.querySelectorAll('video').forEach(function (video) {
        try {
          const tracks = video.textTracks;
          if (!tracks) return;
          for (let i = 0; i < tracks.length; i++) {
            try {
              if (tracks[i].mode !== 'disabled') tracks[i].mode = 'disabled';
            } catch (_) {}
          }
        } catch (_) {}
      });
    } catch (_) {}

    /*
     * A few VidSrc skins render captions as normal DOM instead of TextTrack.
     * Hide only well-known caption layers inside the VidSrc frame chain.
     */
    try {
      const id = '__subhub_vidsrc_caption_hide_v3216';
      if (!document.getElementById(id)) {
        const style = document.createElement('style');
        style.id = id;
        style.textContent = [
          '.vjs-text-track-display{display:none!important;}',
          '.jw-text-track-display{display:none!important;}',
          '.jw-captions{display:none!important;}',
          '.plyr__captions{display:none!important;}',
          'video::cue{color:transparent!important;background:transparent!important;text-shadow:none!important;}',
          'video::-webkit-media-text-track-container,video::-webkit-media-text-track-display{display:none!important;visibility:hidden!important;opacity:0!important;}',
        ].join('');
        (document.head || document.documentElement).appendChild(style);
      }
    } catch (_) {}
  }

  function scan() {
    try {
      ensurePrepareOverlay();
      maximizeServer1Frame();
      maximizePlayerDocument();

      if (isShareHost()) {
        if (!watchShareInnerPlayer()) tryAdvanceShareWrapper();
      }
      if (isPlayerHost()) detectDeepPlayerUi();

      document.querySelectorAll('video').forEach(attach);
      installVidSrcInteractionGuardV3217();
      suppressVidSrcCaptionsV3215();
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
    try { pollVidSrcPlaybackV3253(); } catch (_) {}
  }, 90);
})();
