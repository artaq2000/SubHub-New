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
  // 322.3.112 (site v403):
  //  - our own files of the episode come first, in the same black screen
  //    (keys "own:<slot>", opened by SubHubSeriesOwnSourceV3112);
  //  - each server's usual start time (ms) goes to the player, which waits
  //    longer for a server known to be slow (smart waiting);
  //  - «الحلقة التالية»: the player asks for the next episode and the search
  //    starts again for it (__subhubNextEpisodeV3112).
  //   site → window.__subhubAutoChainV3110({movieId, season, episode, keys, names, ms, fallback})
  //   app  → __subhubAutoOpenedV3110(chain, index) / __subhubAutoNextV3110(chain)
  //          / __subhubAutoOkV3110(chain) / __subhubNextEpisodeV3112(movieId, season, episode)
  // 322.3.127: films too (app_list_stream.js «📱 مشاهدة بالتطبيق»): the request
  // brings its own `launch(key)` (opens one app server of the film) and
  // `onResult(name, ok, ms)` (keeps what each server did, by name).

  const token = '__VIDSRC_GUARD_TOKEN__';
  const OPEN_WAIT_MS = 10000;   // a server that never opens the player is skipped
  const OWN_KEY = /^own:[a-z0-9]{1,12}$/;
  let chain = null;
  let openTimer = 0;

  function bridge() { return window.SubHubAndroidBridge; }
  function arm(c, i) {
    const more = i < c.keys.length - 1 || !!c.fallback;
    try {
      bridge().directStreamAutoArm(token, JSON.stringify({
        chain: c.id, index: i, total: c.keys.length, more: more,
        expectMs: Math.max(0, Math.min(120000, Number(c.ms[i]) || 0))
      }));
    } catch (_) {}
  }
  function endCover() {
    try { bridge().directStreamAutoEnd(token); } catch (_) {}
  }
  function record(c, ok) {
    const name = c.names[c.i] || '';
    const ms = Date.now() - (c.t0 || Date.now());
    if (typeof c.onResult === 'function') {   // 322.3.127: films
      try { if (name) c.onResult(name, !!ok, ms); } catch (_) {}
      return;
    }
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
  function stillHere(movieId) {
    try { return typeof currentMovie !== 'undefined' && currentMovie && String(currentMovie.id) === String(movieId); }
    catch (_) { return false; }
  }
  // This try never opened a player: go on with the next one.
  function skip(c, at) {
    if (chain === c && c.i === at && !c.opened[at]) { clearTimeout(openTimer); record(c, false); c.i++; attempt(); }
  }

  function attempt() {
    const c = chain;
    if (!c) return;
    clearTimeout(openTimer);
    if (!stillHere(c.movieId)) { chain = null; endCover(); return; }
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
    const at = c.i, key = c.keys[at];
    try {
      if (typeof c.launch === 'function') {
        // 322.3.127: a film's app server (the request's own way to open it).
        c.launch(key);
      } else if (OWN_KEY.test(key)) {
        // 322.3.112: our own file (R2 / direct link / our player page).
        const own = window.SubHubSeriesOwnSourceV3112;
        if (typeof own !== 'function') { c.i++; setTimeout(attempt, 150); return; }
        Promise.resolve(own({ movieId: c.movieId, season: c.season, episode: c.episode, slot: key.slice(4) }))
          .then(function (ok) { if (!ok) skip(c, at); }, function () { skip(c, at); });
      } else {
        window.seriesPlayExtraV383(c.season, c.episode, key);
      }
    } catch (_) { c.i++; setTimeout(attempt, 150); return; }
    // The server's script may stop before opening the player (missing page,
    // network): then go on with the next one.
    openTimer = setTimeout(function () { skip(c, at); }, OPEN_WAIT_MS);
  }

  window.__subhubAutoChainV3110 = function (req) {
    if (!req || !Array.isArray(req.keys) || !req.keys.length) return;
    clearTimeout(openTimer);
    const keys = req.keys.map(String).slice(0, 20);
    chain = {
      id: 'auto_' + Date.now() + '_' + Math.random().toString(36).slice(2, 8),
      movieId: String(req.movieId || ''),
      season: Number(req.season),
      episode: Number(req.episode),
      keys: keys,
      names: (Array.isArray(req.names) ? req.names : []).map(function (x) { return String(x || ''); }).slice(0, 20),
      ms: keys.map(function (_, i) { return Array.isArray(req.ms) ? (Number(req.ms[i]) || 0) : 0; }),
      fallback: String(req.fallback || ''),
      // 322.3.127: films — how to open one server, and where its result goes.
      launch: typeof req.launch === 'function' ? req.launch : null,
      onResult: typeof req.onResult === 'function' ? req.onResult : null,
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

  // 322.3.112: «الحلقة التالية» from the player (countdown or «▶ شغّل الآن»).
  // The player is already closed behind the black screen; select the episode on
  // the page and start «مشاهدة تلقائية» for it. Nothing to play → the black
  // screen goes away and the page shows its own message.
  window.__subhubNextEpisodeV3112 = function (movieId, season, episode) {
    const mid = String(movieId || ''), sn = Number(season), en = Number(episode);
    if (!stillHere(mid) || !(sn > 0) || !(en > 0)) { endCover(); return false; }
    try { if (typeof window.seriesSelectEpisodeV403 === 'function') window.seriesSelectEpisodeV403(sn, en); } catch (_) {}
    setTimeout(function () {
      let started = false;
      try {
        if (stillHere(mid) && typeof window.seriesAutoWatchV400 === 'function') {
          window.seriesAutoWatchV400(sn, en);
          started = !!chain;
        }
      } catch (_) {}
      if (!started) endCover();
    }, 400);
    return true;
  };
})();
