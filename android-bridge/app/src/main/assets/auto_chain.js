(function () {
  'use strict';
  if (window.__subhubAutoChainV3110) return;
  // 322.3.110: «مشاهدة تلقائية» for an episode (site v400). The page gives the
  // app servers of the episode in order — the one that worked last time first —
  // and the app opens them one by one: if a server does not play, the player
  // hands over to the next one by itself (black screen + «جارٍ تجهيز المشاهدة…
  // السيرفر ٢ من ٥», no error in between). The first that plays is remembered
  // on this device for the next time. After the last app server comes the site's
  // own player (VidFast) when the episode has it.
  // Site v401 also sends the servers' names: each try is recorded by NAME for
  // this series (worked + how fast it started, or failed), so the same server
  // is tried first in the other episodes too.
  //   site → window.__subhubAutoChainV3110({movieId, season, episode, keys, fallback})
  //   app  → __subhubAutoOpenedV3110(chain, index) / __subhubAutoNextV3110(chain)
  //          / __subhubAutoOkV3110(chain)

  const token = '__VIDSRC_GUARD_TOKEN__';
  const OPEN_WAIT_MS = 10000;   // a server that never opens the player is skipped
  let chain = null;
  let openTimer = 0;

  function bridge() { return window.SubHubAndroidBridge; }
  function arm(c, i) {
    const more = i < c.keys.length - 1 || !!c.fallback;
    try { bridge().directStreamAutoArm(token, JSON.stringify({ chain: c.id, index: i, total: c.keys.length, more: more })); }
    catch (_) {}
  }
  function endCover() {
    try { bridge().directStreamAutoEnd(token); } catch (_) {}
  }
  function record(c, ok) {
    const name = c.names[c.i] || '';
    const ms = Date.now() - (c.t0 || Date.now());
    try {
      if (name && typeof window.__subhubServerStatV401 === 'function') {
        window.__subhubServerStatV401(c.movieId, name, ok, ms);
        return;
      }
    } catch (_) {}
    if (ok) {
      try {
        if (typeof window.__subhubSeriesAutoOkV400 === 'function')
          window.__subhubSeriesAutoOkV400(c.movieId, c.season, c.episode, c.keys[c.i]);
      } catch (_) {}
    }
  }
  function stillHere(c) {
    try { return typeof currentMovie !== 'undefined' && currentMovie && String(currentMovie.id) === c.movieId; }
    catch (_) { return false; }
  }

  function attempt() {
    const c = chain;
    if (!c) return;
    clearTimeout(openTimer);
    if (!stillHere(c)) { chain = null; endCover(); return; }
    if (c.i >= c.keys.length) {
      chain = null;
      endCover();
      if (c.fallback && typeof window.seriesPlayExtraV383 === 'function') {
        try { window.seriesPlayExtraV383(c.season, c.episode, c.fallback); } catch (_) {}
      }
      return;
    }
    arm(c, c.i);
    c.t0 = Date.now();
    try { window.seriesPlayExtraV383(c.season, c.episode, c.keys[c.i]); }
    catch (_) { c.i++; setTimeout(attempt, 150); return; }
    // The server's script may stop before opening the player (missing page,
    // network): then go on with the next one.
    const at = c.i;
    openTimer = setTimeout(function () {
      if (chain === c && c.i === at && !c.opened[at]) { record(c, false); c.i++; attempt(); }
    }, OPEN_WAIT_MS);
  }

  window.__subhubAutoChainV3110 = function (req) {
    if (!req || !Array.isArray(req.keys) || !req.keys.length) return;
    clearTimeout(openTimer);
    chain = {
      id: 'auto_' + Date.now() + '_' + Math.random().toString(36).slice(2, 8),
      movieId: String(req.movieId || ''),
      season: Number(req.season),
      episode: Number(req.episode),
      keys: req.keys.map(String).slice(0, 20),
      names: (Array.isArray(req.names) ? req.names : []).map(function (x) { return String(x || ''); }).slice(0, 20),
      fallback: String(req.fallback || ''),
      i: 0,
      opened: {}
    };
    attempt();
  };
  window.__subhubAutoOpenedV3110 = function (id, index) {
    if (chain && chain.id === id) { chain.opened[Number(index)] = true; clearTimeout(openTimer); }
  };
  window.__subhubAutoNextV3110 = function (id) {
    if (!chain || chain.id !== id) { endCover(); return; }
    record(chain, false);
    chain.i++;
    setTimeout(attempt, 150);
  };
  window.__subhubAutoOkV3110 = function (id) {
    if (!chain || chain.id !== id) return;
    const c = chain;
    chain = null;
    clearTimeout(openTimer);
    record(c, true);
  };
  window.__subhubAutoCancelV3110 = function () {
    chain = null;
    clearTimeout(openTimer);
  };
})();
