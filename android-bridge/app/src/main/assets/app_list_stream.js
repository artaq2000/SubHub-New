(function () {
  'use strict';
  if (window.__subhubAppListInstalledV3107) return;
  window.__subhubAppListInstalledV3107 = true;
  // 322.3.107: «📺 سيرفرات التطبيق» — a LIST of up to 10 app servers per film
  // and per episode, instead of one fixed card per server. Each entry uses the
  // verify-first system of «مفتوح ٣/٤»: the owner adds a server by trying it in
  // the Moviesmod page's own player (nothing captured, no black screen), saves
  // it, and it reopens at once in the SubHub player (capture + subtitles) to
  // set the timing. Viewers see «🎬 مشاهدة بالتطبيق ١، ٢…» — list servers first,
  // then the old cards — and the owner sees the same number on a 👁 badge.
  //   films:    subtitles/{id}.appServers = [item…]
  //   episodes: subtitles/{id}.seriesAppServers['s1e2'] = [item…]
  //   item = {id, label, key, pageUrl, embedUrl, subtitleOffsetMs, subtitlePin?}
  // Per-server subtitle pin: films use playerSubtitleByServer['as_<id>'] (site
  // pin), episodes keep it on the item. Timing is saved on the item.

  const token = '__VIDSRC_GUARD_TOKEN__';
  const MAX = 10;
  const AR = '٠١٢٣٤٥٦٧٨٩';
  const ACCENT = '#22d3ee';
  let active = null;
  let opening = false;
  let lastClosed = null;
  let film = { movieId: '', loaded: false, loading: false, failedAt: 0, items: [] };

  // ------------------------------------------------------------------ basics
  function current() {
    try { return typeof currentMovie !== 'undefined' ? currentMovie : null; }
    catch (_) { return null; }
  }
  function currentId() {
    const c = current();
    return c ? String(c.id || '') : '';
  }
  function isOwner() {
    try { return typeof isLoggedIn !== 'undefined' && !!isLoggedIn; }
    catch (_) { return false; }
  }
  function notify(text, type) {
    try { if (typeof showToast === 'function') showToast(text, type || 'error'); } catch (_) {}
  }
  function arNum(n) {
    return String(n).replace(/\d/g, function (d) { return AR.charAt(+d); });
  }
  function report(session, ok, text) {
    try {
      const b = window.SubHubAndroidBridge;
      if (b && typeof b.directStreamSaveResult === 'function') b.directStreamSaveResult(token, session, !!ok, text);
    } catch (_) {}
  }
  function movieDoc() {
    try { return window._lastRenderedMovieDoc || null; }
    catch (_) { return null; }
  }
  function ownMovieDoc(movieId) {
    const id = String(movieId || '');
    if (!id) return null;
    try {
      if (typeof _mdocLoad === 'function') {
        const hit = (_mdocLoad() || {})[id];
        if (hit && hit.d && typeof hit.d === 'object') return hit.d;
      }
    } catch (_) {}
    return null;
  }
  function cleanLabel(raw) {
    return String(raw || '').replace(/[⭐★☆]/g, '').replace(/\s+/g, ' ').trim().slice(0, 80);
  }
  function validLabel(raw) {
    const v = cleanLabel(raw).toLowerCase();
    if (!v || v.length > 48) return false;
    if (/^(watch now|play|play now|home|movies|select server|trailer|download|settings)$/.test(v)) return false;
    return v.replace(/[^a-z0-9]/g, '').length >= 2;
  }
  function keyOf(label) {
    return cleanLabel(label).toLowerCase().replace(/[^a-z0-9._-]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 60);
  }
  // 322.3.117: fbox (like Moviesmod, more servers). Its title pages are named by
  // slug (/watch/movie-<slug>-<id>), not by TMDB id.
  // 322.3.118: fbox, vumoo, movies2watch… are ONE site under several names (the
  // same film has the same /watch/movie-inception-2g84ihso path on all of them;
  // SmashyStream hands out a different name each visit, names get closed). So
  // a saved page is really its PATH: it opens on the first name that answers,
  // in the owner's order (subtitles/_appSettings.fboxDomains, editable here).
  const FBOX_DEFAULT_DOMAINS = ['fbox.gd', 'vumoo.gd', 'movies2watch.gs'];
  let fboxDomainList = FBOX_DEFAULT_DOMAINS.slice();
  let fboxDomainsLoaded = false;
  let fboxDomainsTried = false;
  // 322.3.121: ONE rule — the chosen name (✓, first in the list) is the one that
  // opens. The owner adding or changing a server gets exactly that name (if it is
  // blocked he is told, and changes it himself). A viewer's player starts on it
  // and moves to the next names by itself when the page does not open; a name
  // whose page did not open in the player on this device is tried last for 10
  // minutes. No probing from here: a blocked page «answers» like a working one.
  const FBOX_BAD_KEY = 'subhub_fbox_bad_v3120';
  const FBOX_BAD_MS = 600000;
  let fboxBadMem = null;
  function fboxBadMap() {
    if (!fboxBadMem) {
      fboxBadMem = {};
      try {
        const m = JSON.parse(localStorage.getItem(FBOX_BAD_KEY) || '{}');
        if (m && typeof m === 'object' && !Array.isArray(m)) fboxBadMem = m;
      } catch (_) {}
    }
    const now = Date.now();
    Object.keys(fboxBadMem).forEach(function (h) {
      const t = Number(fboxBadMem[h]);
      if (!cleanHost(h) || !(t > now - FBOX_BAD_MS && t <= now + 60000)) delete fboxBadMem[h];
    });
    return fboxBadMem;
  }
  function fboxMark(h, bad) {
    const v = cleanHost(h);
    if (!v) return;
    const m = fboxBadMap();
    if (bad) m[v] = Date.now(); else delete m[v];
    try { localStorage.setItem(FBOX_BAD_KEY, JSON.stringify(m)); } catch (_) {}
  }
  // The owner's order, with the names that failed lately at the end.
  function fboxOrder() {
    const bad = fboxBadMap();
    const list = fboxDomainList.length ? fboxDomainList.slice() : FBOX_DEFAULT_DOMAINS.slice();
    return list.filter(function (h) { return !bad[h]; }).concat(list.filter(function (h) { return !!bad[h]; }));
  }
  function cleanHost(h) {
    const v = String(h || '').trim().toLowerCase().replace(/^https?:\/\//, '').replace(/\/.*$/, '').replace(/^www\./, '');
    return /^[a-z0-9-]+(\.[a-z0-9-]+)+$/.test(v) && v.length <= 80 ? v : '';
  }
  function fboxHost(h) {
    const v = cleanHost(h);
    if (!v) return false;
    if (/(^|\.)fbox\.[a-z]{2,12}$/.test(v)) return true;
    return fboxDomainList.concat(FBOX_DEFAULT_DOMAINS).indexOf(v) >= 0;
  }
  function isFboxPage(raw) {
    try {
      const u = new URL(String(raw || ''));
      // 322.3.119: a film page is known by its PATH on any name — a name taken out
      // of the list must never turn a saved server into a Moviesmod one (or drop it).
      return u.protocol === 'https:' && !!cleanHost(u.hostname) && !/(^|\.)moviesmod\.gd$/i.test(u.hostname)
        && /^\/watch\/(movie|tv)-[a-z0-9-]+/i.test(u.pathname);
    } catch (_) { return false; }
  }
  // 322.3.119: a saved server keeps the film's path under ONE fixed name that every
  // app version knows (fbox.gd). It still opens on the chosen name — or the next one
  // that answers — so deleting / renaming names never breaks a saved server.
  const FBOX_STORE_HOST = 'fbox.gd';
  function fboxStoreUrl(raw) {
    if (!isFboxPage(raw)) return String(raw || '');
    try { const u = new URL(String(raw)); u.hostname = FBOX_STORE_HOST; u.hash = ''; return u.href; }
    catch (_) { return String(raw || ''); }
  }
  function isFboxAny(raw) {
    try { const u = new URL(String(raw || '')); return u.protocol === 'https:' && fboxHost(u.hostname); }
    catch (_) { return false; }
  }
  function fboxPath(raw) {
    try { const u = new URL(String(raw || '')); return u.pathname + (u.search || ''); } catch (_) { return ''; }
  }
  function fboxIdOf(raw) {
    try { const m = new URL(String(raw || '')).pathname.match(/^\/watch\/((?:movie|tv)-[a-z0-9-]+)/i); return m ? m[1].toLowerCase() : ''; }
    catch (_) { return ''; }
  }
  async function loadFboxDomains(force) {
    if (fboxDomainsLoaded && !force) return fboxDomainList;
    if (namesBusy) return fboxDomainList;   // 322.3.121: a change being saved is already in place
    try {
      const d = await db.collection('subtitles').doc('_appSettings').get();
      const arr = d.exists && Array.isArray(d.data().fboxDomains) ? d.data().fboxDomains.map(cleanHost).filter(Boolean) : [];
      if (arr.length) fboxDomainList = arr.slice(0, 12);
      fboxDomainsLoaded = true;
    } catch (_) {}
    return fboxDomainList;
  }
  function fboxChosen() {
    return fboxDomainList[0] || FBOX_DEFAULT_DOMAINS[0];
  }
  // The same page path on another name (the saved copy keeps fbox.gd).
  function onFboxName(raw, host) {
    try { const u = new URL(String(raw || '')); u.hostname = host; u.hash = ''; return u.href; }
    catch (_) { return String(raw || ''); }
  }
  // This film's page on fbox (path only matters). 322.3.121: an fbox page the owner
  // removed («🗑️ مسح») is kept as an empty value, so an old server's page is not
  // taken in its place.
  function filmFboxUrl() {
    const d = movieDoc();
    if (d && Object.prototype.hasOwnProperty.call(d, 'fboxUrl') && !String(d.fboxUrl || '').trim()) return '';
    const v = String((d && d.fboxUrl) || '').trim();
    if (isFboxPage(v)) return v;
    const it = (film.items || []).find(function (x) { return isFboxPage(x.pageUrl); });
    return it ? it.pageUrl : '';
  }
  function filmTitle() {
    const sel = current();
    return sel ? String(sel.title || '').trim() : '';
  }
  // Does the fbox page look like this film? Compares the slug words with the title.
  function slugWords(raw) {
    const id = fboxIdOf(raw).replace(/^(movie|tv)-/, '');
    const parts = id.split('-');
    if (parts.length > 1) parts.pop();          // the site's own id (2g84ihso)
    return parts.filter(Boolean);
  }
  function titleWords(t) {
    return String(t || '').toLowerCase().normalize('NFKD').replace(/[̀-ͯ]/g, '')
      .replace(/&/g, ' ').replace(/[^a-z0-9]+/g, ' ').trim().split(/\s+/).filter(Boolean);
  }
  function samePageFilm(raw, title) {
    const a = slugWords(raw).join(' '), b = titleWords(title).join(' ');
    if (!a || !b) return true;               // nothing to compare (non-latin title…)
    return a === b || a.indexOf(b) === 0 || b.indexOf(a) === 0;
  }
  function prettySlug(raw) {
    return slugWords(raw).map(function (w) { return w.charAt(0).toUpperCase() + w.slice(1); }).join(' ') || fboxIdOf(raw);
  }
  function normalizePageUrl(raw) {
    const v = String(raw || '').trim();
    if (!v || v.length > 2200) return '';
    try {
      const u = new URL(v);
      const h = String(u.hostname || '').toLowerCase();
      if (u.protocol === 'https:' && fboxHost(h)) return u.href;   // 322.3.118: any page of the fbox family
      if (isFboxPage(u.href)) return u.href;                        // 322.3.119: a film page on any name
      if (u.protocol !== 'https:' || !(h === 'moviesmod.gd' || h.endsWith('.moviesmod.gd'))) return '';
      return u.href;
    } catch (_) { return ''; }
  }
  function normalizeEmbedUrl(raw) {
    const v = String(raw || '').trim();
    if (!v || v.length > 2200) return '';
    try {
      const u = new URL(v);
      const h = String(u.hostname || '').toLowerCase();
      if (u.protocol !== 'https:' || h === 'moviesmod.gd' || h.endsWith('.moviesmod.gd') || fboxHost(h)) return '';
      return u.href;
    } catch (_) { return ''; }
  }
  function clampOffset(n) {
    const v = Number(n);
    return Number.isFinite(v) ? Math.max(-600000, Math.min(600000, Math.round(v))) : 0;
  }
  function cleanPin(v) {
    const kind = v && (v.kind === 'member' || v.kind === 'official') ? v.kind : '';
    const ref = String((v && v.ref) || '').trim();
    return kind && ref ? { kind: kind, ref: ref } : null;
  }
  function newId() {
    return 'a' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
  }
  function safeYear(v) {
    const m = String(v || '').match(/(?:19|20)\d{2}/);
    return m ? m[0] : '';
  }

  // ------------------------------------------------------------------ items
  function normItem(x) {
    if (!x || typeof x !== 'object') return null;
    const id = String(x.id || '').replace(/[^A-Za-z0-9]/g, '').slice(0, 24);
    const label = cleanLabel(x.label);
    const pageUrl = normalizePageUrl(x.pageUrl);
    if (!id || !validLabel(label) || !pageUrl) return null;
    return {
      id: id,
      label: label,
      key: String(x.key || '').trim().slice(0, 80) || keyOf(label),
      pageUrl: pageUrl,
      embedUrl: normalizeEmbedUrl(x.embedUrl),
      subtitleOffsetMs: clampOffset(x.subtitleOffsetMs),
      subtitlePin: cleanPin(x.subtitlePin),
      createdAt: Number(x.createdAt) || 0,
      updatedAt: Number(x.updatedAt) || 0
    };
  }
  function normItems(arr) {
    const seen = {};
    return (Array.isArray(arr) ? arr : []).map(normItem).filter(function (x) {
      if (!x || seen[x.id]) return false;
      seen[x.id] = 1;
      return true;
    }).slice(0, MAX);
  }
  function toStore(it) {
    const o = {
      id: it.id,
      label: it.label,
      key: it.key || keyOf(it.label),
      pageUrl: it.pageUrl,
      embedUrl: it.embedUrl || '',
      subtitleOffsetMs: clampOffset(it.subtitleOffsetMs),
      createdAt: Number(it.createdAt) || Date.now(),
      updatedAt: Number(it.updatedAt) || Date.now()
    };
    if (it.subtitlePin) o.subtitlePin = { kind: it.subtitlePin.kind, ref: it.subtitlePin.ref };
    return o;
  }
  function H() {
    const h = window.__subhubSeriesHelpersV3107;
    if (!h) throw new Error('series helpers unavailable');
    return h;
  }
  // 322.3.111: where this viewer stopped on the episode, as the site keeps it.
  function episodeProgress(mid, season, episode) {
    let resumeMs = 0;
    try {
      const p = typeof _seriesProgress === 'function' ? _seriesProgress(mid, season, episode) : null;
      if (p && p.d > 0 && p.t > 5 && p.t / p.d < 0.9) resumeMs = Math.round(p.t * 1000);
    } catch (_) {}
    return { movieId: String(mid || ''), season: Number(season), episode: Number(episode), resumeMs: resumeMs };
  }
  function ekOf(ctx) {
    return 's' + Number(ctx.season) + 'e' + Number(ctx.episode);
  }

  async function freshDocData(movieId) {
    const snap = await db.collection('subtitles').doc(String(movieId)).get();
    return snap && snap.exists ? (snap.data() || {}) : {};
  }

  // Film list (Firestore first, this film's own cache entry as fallback).
  async function loadFilm(movieId, force) {
    const id = String(movieId || '');
    if (!force && film.movieId === id && film.loaded) return film.items.slice();
    if (film.movieId === id) film.loading = true;
    let items = null;
    try {
      items = normItems((await freshDocData(id)).appServers);
    } catch (_) {
      const own = ownMovieDoc(id);
      if (own) items = normItems(own.appServers);
    }
    if (film.movieId === id) {
      film.loading = false;
      if (items) { film.items = items; film.loaded = true; film.failedAt = 0; }
      else film.failedAt = Date.now();
    }
    if (!items) throw new Error('load failed');
    return items.slice();
  }

  async function episodeItems(ctx, fresh) {
    const ek = ekOf(ctx);
    if (fresh) {
      try {
        const map = (await freshDocData(ctx.movieId)).seriesAppServers;
        return normItems(map && typeof map === 'object' ? map[ek] : []);
      } catch (_) {}
    }
    const d = currentId() === String(ctx.movieId) ? movieDoc() : ownMovieDoc(ctx.movieId);
    const map = d && d.seriesAppServers;
    return normItems(map && typeof map === 'object' ? map[ek] : []);
  }

  // Merge a change into THIS title's page doc / cache entry only (322.3.104 rule).
  function syncDoc(movieId, apply) {
    try {
      const id = String(movieId);
      const own = ownMovieDoc(id);
      const isCur = currentId() === id;
      const base = own || (isCur ? movieDoc() : null);
      if (!base) return;
      const merged = Object.assign({}, base);
      apply(merged);
      if (isCur) window._lastRenderedMovieDoc = merged;
      if (own && typeof _mdocPut === 'function') _mdocPut(id, merged);
    } catch (_) {}
  }

  async function writeItems(ctx, items) {
    const arr = items.slice(0, MAX).map(toStore);
    if (ctx.kind === 'tv') {
      const ek = ekOf(ctx);
      await db.collection('subtitles').doc(String(ctx.movieId)).set({
        seriesAppServers: { [ek]: arr },
        appServersUpdatedAt: Date.now()
      }, { merge: true });
      syncDoc(ctx.movieId, function (d) {
        d.seriesAppServers = Object.assign({}, d.seriesAppServers || {}, { [ek]: arr });
      });
      try {
        if (currentId() === String(ctx.movieId) && typeof refreshSeriesSelectionV265 === 'function') {
          setTimeout(function () { try { refreshSeriesSelectionV265(); } catch (_) {} }, 60);
        }
      } catch (_) {}
    } else {
      await db.collection('subtitles').doc(String(ctx.movieId)).set({
        appServers: arr,
        appServersUpdatedAt: Date.now()
      }, { merge: true });
      if (film.movieId === String(ctx.movieId)) { film.items = normItems(arr); film.loaded = true; }
      syncDoc(ctx.movieId, function (d) { d.appServers = arr; });
      render(true);
    }
  }

  async function resolveTmdb(selected) {
    const rawId = String(selected && selected.id || '').trim();
    const stored = rawId.match(/^tmdb_(movie|tv)_(\d{1,12})$/i);
    if (stored) return { kind: stored[1].toLowerCase(), id: stored[2] };
    const wantKind = selected && selected.type === 'series' ? 'tv' : 'movie';
    if (typeof TMDB_API_KEY === 'undefined' || typeof TMDB_BASE === 'undefined'
        || !TMDB_API_KEY || String(TMDB_API_KEY).includes('ضع_مفتاحك')) {
      throw new Error('tmdb unavailable');
    }
    if (/^tt\d{5,12}$/i.test(rawId)) {
      const r = await fetch(
        `${TMDB_BASE}/find/${encodeURIComponent(rawId)}?api_key=${encodeURIComponent(TMDB_API_KEY)}&external_source=imdb_id&language=en-US`
      );
      const d = await r.json();
      const first = wantKind === 'tv'
        ? ((d.tv_results || [])[0] || (d.movie_results || [])[0])
        : ((d.movie_results || [])[0] || (d.tv_results || [])[0]);
      if (first && first.id) {
        const actualKind = (d.tv_results || []).some(x => x && x.id === first.id) ? 'tv' : 'movie';
        return { kind: actualKind, id: String(first.id) };
      }
    }
    const title = String(selected && selected.title || '').trim();
    if (!title) throw new Error('no title');
    const year = safeYear(selected && selected.year);
    const yearArg = year
      ? '&' + (wantKind === 'tv' ? 'first_air_date_year' : 'year') + '=' + encodeURIComponent(year)
      : '';
    const r = await fetch(
      `${TMDB_BASE}/search/${wantKind}?api_key=${encodeURIComponent(TMDB_API_KEY)}&language=en-US&query=${encodeURIComponent(title)}${yearArg}`
    );
    const d = await r.json();
    const first = d && Array.isArray(d.results) ? d.results[0] : null;
    if (!first || !first.id) throw new Error('tmdb not found');
    return { kind: wantKind, id: String(first.id) };
  }

  // ------------------------------------------------------------------ player
  // ctx: {movieId, kind:'movie'} or {movieId, kind:'tv', season, episode}
  // slotId: saved entry (null = add a new one). manual: owner chooses again.
  async function openSession(ctx, slotId, manual, overridePageUrl) {
    if (opening || active) return;
    const selected = current();
    if (!selected || String(selected.id || '') !== String(ctx.movieId)) return;
    opening = true;
    try {
      const owner = isOwner();
      if (owner && (typeof checkOwnerAccess !== 'function' || !(await checkOwnerAccess()) || current() !== selected)) return;
      const bridge = window.SubHubAndroidBridge;
      if (!bridge || typeof bridge.openDirectStream !== 'function') throw new Error('bridge unavailable');

      const items = ctx.kind === 'tv' ? await episodeItems(ctx, true) : await loadFilm(ctx.movieId, true);
      const item = slotId ? (items.find(function (x) { return x.id === slotId; }) || null) : null;
      if (slotId && !item) { notify('هذا السيرفر لم يعد موجوداً.'); return; }
      if (!owner && !item) return;
      if (owner && !item && items.length >= MAX) { notify('وصلت للحد الأقصى: ١٠ سيرفرات.'); return; }
      const auto = !!item && !manual;

      let tmdbId = '', kind = 'movie';
      if (ctx.kind === 'tv') {
        tmdbId = String(await H().resolveTmdbTv({ movieId: ctx.movieId, season: ctx.season, episode: ctx.episode }));
        kind = 'tv';
      } else {
        const r = await resolveTmdb(selected);
        tmdbId = String(r.id);
        kind = r.kind;
      }
      if (!/^\d{1,12}$/.test(tmdbId)) { notify('تعذّر تحديد صفحة العنوان في Moviesmod.'); return; }
      if (current() !== selected) return;

      let catalog, defaultIndex;
      if (ctx.kind === 'tv') {
        try { if (typeof setSubServerKeyV382 === 'function') setSubServerKeyV382('series_list'); } catch (_) {}
        await H().ensureEpisodeMembers(ctx.movieId);
        if (current() !== selected) return;
        catalog = H().episodeCatalog(ctx.season, ctx.episode);
        defaultIndex = H().applyDefaultsWithPin(catalog, item && item.subtitlePin, ctx.season, ctx.episode);
      } else {
        if (typeof _buildSubtitleTrackCatalog !== 'function') throw new Error('catalog unavailable');
        // The subtitle pinned to THIS list server (if any) becomes the default.
        try { if (typeof setSubServerKeyV382 === 'function') setSubServerKeyV382('as_' + (item ? item.id : 'new')); } catch (_) {}
        catalog = _buildSubtitleTrackCatalog().slice();
        const explicit = catalog.findIndex(function (x) { return x.isDefault === true; });
        defaultIndex = explicit >= 0 ? explicit : (catalog.length ? 0 : -1);
      }

      if (typeof stopInlinePlayersV265 === 'function') stopInlinePlayersV265();
      if (typeof closeEmbedPlayer === 'function') closeEmbedPlayer();

      const manualPage = normalizePageUrl(overridePageUrl || '') || (item ? item.pageUrl : '')
        || (ctx.kind === 'tv' ? H().episodePageUrl(tmdbId, ctx.season, ctx.episode) : '');
      let startPage = auto ? item.pageUrl : manualPage;
      // 322.3.121: fbox — the chosen name (✓) opens.
      //   owner adding / changing a server: exactly the chosen name. A film page
      //   opens as it is; any other fbox page (their home) starts the search for
      //   this film's title there.
      //   playing a saved server: the chosen name first; the player moves to the
      //   next names by itself (names that failed lately on this device last).
      let searchText = '', fboxHosts = [];
      if (isFboxAny(startPage) || isFboxPage(startPage)) {
        await loadFboxDomains(false);
        if (current() !== selected) return;
        if (!auto) {
          const name = fboxChosen();
          if (isFboxPage(startPage)) {
            startPage = onFboxName(startPage, name);
          } else {
            startPage = 'https://' + name + '/';
            searchText = String(selected.title || '').trim().slice(0, 80);
          }
        } else {
          const order = fboxOrder();
          startPage = onFboxName(startPage, order[0] || fboxChosen());
          fboxHosts = order.slice(1, 12);
        }
      }
      const base = String(selected.id || ('tmdb_' + kind + '_' + tmdbId)).replace(/[^A-Za-z0-9_.-]/g, '_').slice(0, 60);
      const stableKey = (base + (ctx.kind === 'tv' ? '_' + ekOf(ctx) : '') + '_' + (item ? item.id : 'new')).slice(0, 100);
      const session = 'applist_' + Date.now() + '_' + Math.random().toString(36).slice(2);

      active = {
        session: session,
        ctx: ctx,
        owner: owner,
        slotId: item ? item.id : null,
        itemLabel: item ? item.label : '',
        auto: auto,
        catalog: catalog,
        startPage: startPage,
        pending: null,
        subtitleOffsetMs: item ? item.subtitleOffsetMs : 0
      };

      bridge.openDirectStream(token, JSON.stringify({
        mode: 'list',
        // 322.3.117: which page lists the servers — Moviesmod (by TMDB id) or fbox (saved page).
        site: isFboxAny(startPage) ? 'fbox' : 'moviesmod',
        searchText: searchText,
        fboxHosts: fboxHosts,
        session: session,
        movieId: stableKey,
        resumeKey: stableKey,
        tmdbId: tmdbId,
        kind: kind,
        // 322.3.109: the device subtitle file belongs to this film / episode.
        episodeKey: ctx.kind === 'tv' ? ekOf(ctx) : '',
        // 322.3.111: episodes only — where the viewer stops, kept by the site.
        progress: ctx.kind === 'tv' ? episodeProgress(ctx.movieId, ctx.season, ctx.episode) : null,
        // 322.3.112: «الحلقة التالية» card for episodes (viewers / owner playing a saved server).
        next: (ctx.kind === 'tv' && !(owner && !auto) && typeof H().nextInfo === 'function')
          ? H().nextInfo(ctx.movieId, ctx.season, ctx.episode) : null,
        // 322.3.114: «حلقات» in the player menu.
        episodes: (ctx.kind === 'tv' && !(owner && !auto) && typeof H().episodeList === 'function')
          ? H().episodeList(ctx.movieId, ctx.season, ctx.episode) : [],
        interactiveSource: owner && !auto,
        ownerMode: owner,
        openChooser: owner && !auto && !!startPage,
        serverKey: auto ? item.key : '',
        serverLabel: auto ? item.label : '',
        serverPageUrl: startPage,
        // 322.3.113: episodes — the saved player link first, the Moviesmod page if it does not play.
        embedUrl: (auto && item && ctx.kind === 'tv') ? (item.embedUrl || '') : '',
        subtitleOffsetMs: active.subtitleOffsetMs,
        catalog: catalog.map(function (x) { return { name: String(x.name || 'ترجمة SubHub'), pinned: x.pinned === true }; }),
        defaultIndex: defaultIndex
      }));
    } catch (_) {
      active = null;
      notify('تعذّر فتح سيرفر التطبيق داخل المشغّل.');
    } finally {
      opening = false;
    }
  }

  async function savePending(st, rawOffsetMs) {
    try {
      if (!isOwner() || typeof checkOwnerAccess !== 'function' || !(await checkOwnerAccess())) {
        return { ok: false, text: 'تعذّر الحفظ' };
      }
      const p = st.pending;
      if (!p || !p.pageUrl) return { ok: false, text: 'تعذّر معرفة صفحة السيرفر — اضغط السيرفر في الصفحة مرة أخرى' };
      const ctx = st.ctx;
      // 322.3.118: an fbox page of ANOTHER film (reached through their search) is
      // never saved on this film without the owner saying so.
      if (isFboxAny(p.pageUrl) && ctx.kind !== 'tv') {
        if (!isFboxPage(p.pageUrl)) return { ok: false, text: 'ادخل صفحة الفيلم في fbox أولاً، ثم اضغط السيرفر' };
        const sel = current();
        const title = sel ? String(sel.title || '') : '';
        // 322.3.121: a saved page of ANOTHER film is simply replaced by this one; the
        // owner is asked only when this page is not the film's, or when the film
        // already has a different page that also looks like it (same title).
        const known = filmFboxUrl();
        const otherId = !!known && samePageFilm(known, title) && fboxIdOf(known) !== fboxIdOf(p.pageUrl);
        if (!samePageFilm(p.pageUrl, title) || otherId) {
          const msg = '⚠️ هذي صفحة «' + prettySlug(p.pageUrl) + '» في fbox — وفيلمك «' + title + '».'
            + (otherId ? '\nوهذا الفيلم محفوظ له صفحة ثانية: ' + fboxIdOf(known) : '')
            + '\n\nأحفظ السيرفر لهذا الفيلم مع ذلك؟';
          if (!confirm(msg)) return { ok: false, text: 'ما انحفظ — صفحة فيلم آخر' };
        }
      }
      const items = ctx.kind === 'tv' ? await episodeItems(ctx, true) : await loadFilm(ctx.movieId, true);
      const idx = st.slotId ? items.findIndex(function (x) { return x.id === st.slotId; }) : -1;
      const now = Date.now();
      let id = st.slotId;
      if (idx >= 0) {
        const old = items[idx];
        const same = old.label === p.label;
        items[idx] = Object.assign({}, old, {
          label: p.label,
          key: p.key,
          pageUrl: fboxStoreUrl(p.pageUrl),
          embedUrl: p.embedUrl || (same ? old.embedUrl : ''),
          // a different server is another video version: timing/pin start over
          subtitleOffsetMs: same ? clampOffset(Number.isFinite(Number(rawOffsetMs)) ? rawOffsetMs : old.subtitleOffsetMs) : 0,
          subtitlePin: same ? old.subtitlePin : null,
          updatedAt: now
        });
      } else {
        if (items.length >= MAX) return { ok: false, text: 'وصلت للحد الأقصى: ١٠ سيرفرات' };
        id = newId();
        items.push({
          id: id, label: p.label, key: p.key, pageUrl: fboxStoreUrl(p.pageUrl), embedUrl: p.embedUrl || '',
          subtitleOffsetMs: 0, subtitlePin: null, createdAt: now, updatedAt: now
        });
      }
      await writeItems(ctx, items);
      st.slotId = id;
      // 322.3.118: an fbox server also records the film's fbox page.
      // 322.3.121: every time — the page saved from last is the film's page, so a
      // wrong page saved earlier is replaced by the right one.
      if (ctx.kind !== 'tv' && isFboxPage(p.pageUrl)) {
        try {
          const stored = fboxStoreUrl(p.pageUrl);
          const d = currentId() === String(ctx.movieId) ? movieDoc() : ownMovieDoc(ctx.movieId);
          if (!d || String(d.fboxUrl || '') !== stored) {
            await db.collection('subtitles').doc(String(ctx.movieId)).set({ fboxUrl: stored }, { merge: true });
            syncDoc(ctx.movieId, function (x) { x.fboxUrl = stored; });
          }
        } catch (_) {}
      }
      return { ok: true, id: id, text: 'تم الحفظ ✓ ' + p.label };
    } catch (_) {
      return { ok: false, text: 'تعذّر الحفظ' };
    }
  }

  async function updateItem(ctx, id, change) {
    const items = ctx.kind === 'tv' ? await episodeItems(ctx, true) : await loadFilm(ctx.movieId, true);
    const i = items.findIndex(function (x) { return x.id === id; });
    if (i < 0) throw new Error('missing');
    items[i] = Object.assign({}, items[i], change(items[i]), { updatedAt: Date.now() });
    await writeItems(ctx, items);
    return items[i];
  }

  async function deleteItem(ctx, id) {
    if (!isOwner()) return;
    try {
      if (typeof checkOwnerAccess !== 'function' || !(await checkOwnerAccess())) return;
      const items = ctx.kind === 'tv' ? await episodeItems(ctx, true) : await loadFilm(ctx.movieId, true);
      const gone = items.find(function (x) { return x.id === id; });
      await writeItems(ctx, items.filter(function (x) { return x.id !== id; }));
      if (ctx.kind !== 'tv') {
        // drop the subtitle pinned to this server too
        try {
          await db.collection('subtitles').doc(String(ctx.movieId)).set({
            playerSubtitleByServer: { ['as_' + id]: firebase.firestore.FieldValue.delete() }
          }, { merge: true });
          if (window._moviePlayerSubtitleByServerV382) delete window._moviePlayerSubtitleByServerV382['as_' + id];
        } catch (_) {}
      }
      notify('تم حذف السيرفر' + (gone ? ' ' + gone.label : '') + '.', 'success');
    } catch (_) {
      notify('تعذّر حذف السيرفر.');
    }
  }

  // 322.3.121: «🗑️ مسح» — this film has no fbox page any more; the next
  // «📦 إضافة» starts from the search. Saved servers keep their own page.
  async function clearFilmPage(ctx) {
    if (!isOwner()) return;
    if (!confirm('مسح صفحة fbox المحفوظة لهذا الفيلم؟\nالإضافة القادمة تبدأ من البحث، والسيرفرات المحفوظة لا تتأثر.')) return;
    try {
      if (typeof checkOwnerAccess !== 'function' || !(await checkOwnerAccess())) return;
      await db.collection('subtitles').doc(String(ctx.movieId)).set({ fboxUrl: '' }, { merge: true });
      syncDoc(ctx.movieId, function (x) { x.fboxUrl = ''; });
      notify('مُسحت صفحة fbox لهذا الفيلم ✓', 'success');
    } catch (_) {
      notify('تعذّر المسح.');
    }
    render(true);
  }

  async function moveItem(ctx, id, dir) {
    if (!isOwner()) return;
    try {
      if (typeof checkOwnerAccess !== 'function' || !(await checkOwnerAccess())) return;
      const items = ctx.kind === 'tv' ? await episodeItems(ctx, true) : await loadFilm(ctx.movieId, true);
      const i = items.findIndex(function (x) { return x.id === id; });
      const j = i + dir;
      if (i < 0 || j < 0 || j >= items.length) return;
      const t = items[i]; items[i] = items[j]; items[j] = t;
      await writeItems(ctx, items);
    } catch (_) {
      notify('تعذّر تغيير الترتيب.');
    }
  }

  // ------------------------------------------------------------ native hooks
  window.__subhubAppListServerSelected = function (session, rawKey, rawLabel, rawPageUrl, rawEmbedUrl) {
    const st = active;
    if (!st || st.session !== session || !st.owner) return;
    const label = cleanLabel(rawLabel);
    if (!validLabel(label)) { notify('اسم السيرفر غير معروف — جرّب سيرفراً آخر.'); return; }
    st.pending = {
      key: String(rawKey || '').trim().slice(0, 80) || keyOf(label),
      label: label,
      pageUrl: normalizePageUrl(rawPageUrl) || normalizePageUrl(st.startPage),
      embedUrl: normalizeEmbedUrl(rawEmbedUrl)
    };
  };

  window.__subhubAppListSaveNow = async function (session, rawOffsetMs) {
    const st = active;
    if (!st || st.session !== session || !st.owner || !st.pending) {
      report(session, false, 'لم يُعرف السيرفر بعد — اضغط سيرفراً في الصفحة');
      return false;
    }
    const r = await savePending(st, rawOffsetMs);
    // saved from the verify page → reopen it in the SubHub player at once
    if (r.ok && !st.auto) st.reopenSlot = r.id;
    report(session, r.ok, r.text);
    return r.ok;
  };

  window.__subhubAppListPinNow = async function (session, index) {
    const st = active;
    const b = window.SubHubAndroidBridge;
    if (!st || st.session !== session) return false;
    const entry = Number.isInteger(index) ? st.catalog[index] : null;
    if (!st.owner || !isOwner() || !entry || (entry.kind !== 'official' && entry.kind !== 'member')) {
      report(session, false, 'تعذّر التثبيت');
      return false;
    }
    if (!st.slotId) { report(session, false, 'احفظ السيرفر أولاً ثم ثبّت الترجمة'); return false; }
    try {
      if (typeof checkOwnerAccess !== 'function' || !(await checkOwnerAccess())) { report(session, false, 'التثبيت للمالك فقط'); return false; }
      if (st.ctx.kind === 'tv') {
        let was = false;
        await updateItem(st.ctx, st.slotId, function (it) {
          was = !!(it.subtitlePin && it.subtitlePin.kind === entry.kind && it.subtitlePin.ref === String(entry.ref));
          return { subtitlePin: was ? null : { kind: entry.kind, ref: String(entry.ref) } };
        });
        st.catalog.forEach(function (x, k) { x.pinned = !was && k === index; });
        report(session, true, was ? 'أُلغي تثبيت الترجمة لهذا السيرفر' : '📌 ثُبّتت الترجمة لهذا السيرفر في هذه الحلقة');
        if (b && typeof b.directStreamPinned === 'function') b.directStreamPinned(token, session, index, !was);
        return true;
      }
      if (typeof pinSubtitleForServerV382 !== 'function') { report(session, false, 'حدّث الموقع أولاً لتفعيل التثبيت'); return false; }
      const r = await pinSubtitleForServerV382(entry, 'as_' + st.slotId);
      report(session, !!(r && r.ok), (r && r.message) || 'تعذّر التثبيت');
      if (r && r.ok && b && typeof b.directStreamPinned === 'function') b.directStreamPinned(token, session, index, !!r.pinned);
      return !!(r && r.ok);
    } catch (_) {
      report(session, false, 'تعذّر حفظ التثبيت');
      return false;
    }
  };

  window.__subhubAppListSyncPinNow = async function (session, rawOffsetMs) {
    const st = active;
    if (!st || st.session !== session) return false;
    const offset = clampOffset(rawOffsetMs);
    function syncReport(ok, text) {
      try {
        const b = window.SubHubAndroidBridge;
        if (b && typeof b.directStreamSyncPinned === 'function') b.directStreamSyncPinned(token, session, !!ok, text, offset);
        else report(session, ok, text);
      } catch (_) {}
    }
    if (!st.owner || !isOwner()) { syncReport(false, 'تعذّر تثبيت التوقيت'); return false; }
    if (!st.slotId) { syncReport(false, 'احفظ السيرفر أولاً ثم ثبّت التوقيت'); return false; }
    try {
      if (typeof checkOwnerAccess !== 'function' || !(await checkOwnerAccess())) { syncReport(false, 'تعذّر تثبيت التوقيت'); return false; }
      await updateItem(st.ctx, st.slotId, function () { return { subtitleOffsetMs: offset }; });
      st.subtitleOffsetMs = offset;
      const secs = (Math.abs(offset) / 1000).toFixed(1);
      const words = Math.abs(offset) < 50 ? 'بلا تعديل' : (offset > 0 ? 'مؤخَّرة ' + secs + ' ث' : 'مقدَّمة ' + secs + ' ث');
      syncReport(true, 'تم تثبيت التوقيت لهذا السيرفر ✓ ' + words);
      return true;
    } catch (_) {
      syncReport(false, 'تعذّر تثبيت التوقيت');
      return false;
    }
  };

  // 322.3.120: the player reports an fbox name — its page did not open (tried last
  // for 10 minutes on this device), or it opened (an old failure of it is forgotten).
  window.__subhubFboxHostV3120 = function (rawHost, ok) {
    const h = cleanHost(rawHost);
    if (h) fboxMark(h, !ok);
  };

  // Native player closed itself after «سيرفر آخر»: choose again for the same slot.
  window.__subhubAppListReopenManual = function (rawPageUrl) {
    const last = lastClosed;
    if (!last || Date.now() - last.at > 5000 || !isOwner()) return;
    lastClosed = null;
    const page = normalizePageUrl(rawPageUrl || '');
    setTimeout(function () { openSession(last.ctx, last.slotId, true, page); }, 120);
  };

  const previousClosed = window.__subhubDirectClosed;
  window.__subhubDirectClosed = function (session) {
    if (active && active.session === session) {
      const done = active;
      active = null;
      lastClosed = { ctx: done.ctx, slotId: done.slotId, at: Date.now() };
      render(true);
      if (done.reopenSlot && isOwner()) {
        setTimeout(function () { openSession(done.ctx, done.reopenSlot, false, ''); }, 450);
      }
    }
    if (typeof previousClosed === 'function') {
      try { return previousClosed(session); } catch (_) {}
    }
  };

  const previousSubtitle = window.__subhubDirectSubtitle;
  window.__subhubDirectSubtitle = async function (session, index) {
    const state = active;
    if (!state || state.session !== session) {
      if (typeof previousSubtitle === 'function') {
        try { return previousSubtitle(session, index); } catch (_) {}
      }
      return;
    }
    if (!Number.isInteger(index) || !state.catalog[index]) return;
    let cues = [], error = '';
    try {
      const loaded = await _loadSubtitleCatalogEntry(state.catalog[index]);
      if (!Array.isArray(loaded)) {
        // exclusive/limited subtitles arrive as 4-second pieces
        const pump = window.__subhubSegmentPumpV3292;
        if (loaded && loaded.dynamic && pump
            && pump.start(session, index, loaded.access, function () { return active === state; })) return;
        throw new Error('segmented');
      }
      cues = loaded
        .filter(function (c) { return Number.isFinite(c.start) && Number.isFinite(c.end) && c.end > c.start; })
        .map(function (c) { return { start: c.start, end: c.end, text: String(c.text || '') }; });
      if (!cues.length) throw new Error('empty');
    } catch (_) {
      error = 'تعذّر تحميل الترجمة. اختر ترجمة أخرى من CC.';
    }
    if (active !== state) return;
    try { window.SubHubAndroidBridge.directStreamSubtitles(token, session, index, JSON.stringify(cues), error); } catch (_) {}
  };

  // ------------------------------------------------------------- episodes
  // SubHub Web v399 calls this hook with source 'list' for the episode list.
  const previousSeriesHook = window.SubHubSeriesAppSourceV383;
  window.SubHubSeriesAppSourceV383 = function (raw) {
    if (!(raw && raw.source === 'list')) {
      if (typeof previousSeriesHook === 'function') return previousSeriesHook.apply(this, arguments);
      return;
    }
    const ctx = {
      movieId: String(raw.movieId || ''),
      kind: 'tv',
      season: Number(raw.season),
      episode: Number(raw.episode)
    };
    if (!ctx.movieId || !(ctx.season > 0) || !(ctx.episode > 0)) return;
    const slot = String(raw.slot || '').replace(/[^A-Za-z0-9]/g, '');
    if (!slot) {
      if (isOwner()) openSession(ctx, null, true, '');
      return;
    }
    if (!isOwner()) { openSession(ctx, slot, false, ''); return; }
    episodeItems(ctx, false).then(function (items) {
      const i = items.findIndex(function (x) { return x.id === slot; });
      if (i < 0) { notify('هذا السيرفر لم يعد موجوداً.'); return; }
      ownerSheet(ctx, items[i], i, items.length);
    }).catch(function () {});
  };

  function ownerSheet(ctx, item, index, count) {
    const old = document.getElementById('subhub-applist-sheet');
    if (old) old.remove();
    const bg = document.createElement('div');
    bg.id = 'subhub-applist-sheet';
    bg.style.cssText = 'position:fixed;inset:0;z-index:2147483000;background:rgba(0,0,0,.72);display:flex;align-items:flex-end;justify-content:center;padding:18px;direction:rtl';
    const box = document.createElement('div');
    box.style.cssText = 'width:min(520px,96vw);border:1px solid ' + ACCENT + ';border-radius:18px;background:#0b1625;color:#fff;padding:16px;box-shadow:0 16px 60px rgba(0,0,0,.55)';
    const title = document.createElement('div');
    title.style.cssText = 'font-weight:900;font-size:1rem;line-height:1.7;margin-bottom:4px';
    title.textContent = '📺 سيرفر التطبيق · الموسم ' + arNum(ctx.season) + ' الحلقة ' + arNum(ctx.episode);
    const msg = document.createElement('div');
    msg.style.cssText = 'color:#b8c5d6;font-size:.86rem;line-height:1.7;margin-bottom:12px';
    msg.textContent = 'السيرفر: ' + item.label;
    const row = document.createElement('div');
    row.style.cssText = 'display:grid;grid-template-columns:1fr 1fr 1fr;gap:8px';
    function btn(text, css, disabled, fn) {
      const b = document.createElement('button');
      b.type = 'button';
      b.textContent = text;
      b.disabled = !!disabled;
      b.style.cssText = 'min-height:44px;border-radius:11px;font:inherit;font-weight:900;color:#fff;opacity:' + (disabled ? '.4' : '1') + ';' + css;
      b.addEventListener('click', function () { if (b.disabled) return; bg.remove(); fn(); });
      return b;
    }
    row.appendChild(btn('▶ تشغيل', 'border:1px solid #166534;background:#12301d', false, function () { openSession(ctx, item.id, false, ''); }));
    row.appendChild(btn('✏️ سيرفر آخر', 'border:1px solid #41536b;background:#122033', false, function () { openSession(ctx, item.id, true, ''); }));
    row.appendChild(btn('🗑️ حذف', 'border:1px solid #783b48;background:#24151d', false, function () {
      if (confirm('حذف السيرفر «' + item.label + '» من هذه الحلقة؟')) deleteItem(ctx, item.id);
    }));
    row.appendChild(btn('⬆️ تقديم', 'border:1px solid #36506b;background:#0c1d2d', index <= 0, function () { moveItem(ctx, item.id, -1); }));
    row.appendChild(btn('⬇️ تأخير', 'border:1px solid #36506b;background:#0c1d2d', index >= count - 1, function () { moveItem(ctx, item.id, 1); }));
    row.appendChild(btn('إلغاء', 'border:1px solid #475569;background:#1e293b', false, function () {}));
    box.appendChild(title);
    box.appendChild(msg);
    box.appendChild(row);
    bg.appendChild(box);
    bg.addEventListener('click', function (e) { if (e.target === bg) bg.remove(); });
    document.body.appendChild(bg);
  }

  // ------------------------------------------------------------- film UI
  function viewerNumber(key) {
    try {
      const order = typeof window.__subhubViewerAppOrderV3107 === 'function' ? window.__subhubViewerAppOrderV3107() : [];
      const k = order.indexOf(key);
      return k >= 0 ? k + 1 : 0;
    } catch (_) { return 0; }
  }

  function exposeItems() {
    window.__subhubAppListItemsV3107 = film.loaded
      ? { movieId: film.movieId, items: film.items.map(function (x) { return { id: x.id, label: x.label }; }) }
      : null;
  }

  function removeFilmUi() {
    const s = document.getElementById('subhub-applist-section');
    if (s) s.remove();
    [].slice.call(document.querySelectorAll('.subhub-applist-btn[data-applist="app"]')).forEach(function (b) { b.remove(); });
  }

  function smallBtn(text, kind, disabled, fn) {
    const b = document.createElement('button');
    b.type = 'button';
    b.textContent = text;
    b.disabled = !!disabled;
    b.style.cssText =
      'min-height:34px;border-radius:9px;font:inherit;font-size:.68rem;font-weight:850;cursor:pointer;padding:4px 2px;' +
      'color:#fff;border:1px solid ' + (kind === 'danger' ? '#7f1d1d' : kind === 'play' ? '#166534' : '#36506b') + ';' +
      'background:' + (kind === 'danger' ? '#2b1115' : kind === 'play' ? '#10271a' : '#0c1d2d') + ';' +
      'opacity:' + (disabled ? '.38' : '1');
    b.addEventListener('click', function (ev) {
      ev.preventDefault();
      ev.stopPropagation();
      if (!b.disabled) fn();
    });
    return b;
  }

  // 322.3.108: the old app cards (Moviesmod, yellow, open 1…4, Vidnest) sit in
  // this block as compact rows (moved here by app_cards_order.js). The block is
  // kept across redraws of the section so the cards are never destroyed.
  function legacyBlock() {
    let b = document.getElementById('subhub-applist-legacy-block');
    if (b) return b;
    b = document.createElement('div');
    b.id = 'subhub-applist-legacy-block';
    b.style.cssText = 'display:flex;flex-direction:column;gap:6px;margin-top:4px;border-top:1px solid #1f3b4d;padding-top:10px';
    const head = document.createElement('div');
    head.setAttribute('data-legacy', 'saved-head');
    head.style.cssText = 'font-weight:900;font-size:.8rem;color:#cbd5e1';
    head.textContent = 'الأزرار السابقة';
    const saved = document.createElement('div');
    saved.setAttribute('data-legacy', 'saved');
    saved.style.cssText = 'display:flex;flex-direction:column;gap:6px';
    const details = document.createElement('details');
    details.setAttribute('data-legacy', 'details');
    details.style.cssText = 'border:1px dashed #2b4a5e;border-radius:12px;padding:6px 8px';
    const summary = document.createElement('summary');
    summary.setAttribute('data-legacy', 'count');
    summary.style.cssText = 'cursor:pointer;font-size:.74rem;font-weight:800;color:#a9b7cb;padding:4px 2px';
    summary.textContent = 'أزرار غير مستعملة';
    const unused = document.createElement('div');
    unused.setAttribute('data-legacy', 'unused');
    unused.style.cssText = 'display:flex;flex-direction:column;gap:6px;margin-top:6px';
    details.appendChild(summary);
    details.appendChild(unused);
    b.appendChild(head);
    b.appendChild(saved);
    b.appendChild(details);
    return b;
  }

  // 322.3.119: «📦 أسماء fbox» — the chosen name (✓, first in subtitles/_appSettings.fboxDomains)
  // is tried first; the others are fallbacks in their order. Add / edit / delete /
  // choose / reorder — each one saved at once.
  let namesOpen = false;
  let namesDraft = '';
  let namesEditing = -1;
  let namesEditDraft = '';
  let namesBusy = false;
  function ltrText(text, color) {
    const s = document.createElement('bdi');
    s.dir = 'ltr';
    s.textContent = String(text || '');
    if (color) s.style.color = color;
    return s;
  }
  function namesInput(value, placeholder, onText, onEnter) {
    const i = document.createElement('input');
    i.type = 'text';
    i.dir = 'ltr';
    i.value = value;
    i.placeholder = placeholder;
    i.autocomplete = 'off';
    i.spellcheck = false;
    i.setAttribute('autocapitalize', 'off');
    i.setAttribute('inputmode', 'url');
    i.style.cssText = 'min-width:0;width:100%;box-sizing:border-box;min-height:36px;border-radius:9px;border:1px solid #36506b;' +
      'background:#06131c;color:#fff;font:inherit;font-size:.78rem;padding:4px 10px;text-align:left';
    i.addEventListener('input', function () { onText(i.value); });
    i.addEventListener('keydown', function (ev) { if (ev.key === 'Enter') { ev.preventDefault(); onEnter(); } });
    return i;
  }
  function nameProblem(h, skipIndex) {
    if (!h) return 'اكتب اسم موقع صحيح، مثل tinyzone.sx — أو الصق رابطاً من الموقع';
    if (/(^|\.)moviesmod\.gd$/.test(h)) return 'هذا اسم Moviesmod — له زرّه الخاص';
    const at = fboxDomainList.indexOf(h);
    if (at >= 0 && at !== skipIndex) return '«' + h + '» موجود في القائمة';
    return '';
  }
  // 322.3.121: a change counts AT ONCE on this device (the chosen name is the one
  // that opens from this moment); the site copy follows, and on failure the list
  // goes back to what it was.
  async function saveFboxNames(next, okText) {
    if (namesBusy || !isOwner()) return false;
    const arr = next.map(cleanHost).filter(Boolean).filter(function (h, i, a) { return a.indexOf(h) === i; }).slice(0, 12);
    if (!arr.length) { notify('خلّ اسماً واحداً على الأقل في القائمة.'); return false; }
    const before = fboxDomainList.slice();
    fboxDomainList = arr; fboxDomainsLoaded = true;
    namesBusy = true;
    render(true);
    let ok = false;
    try {
      if (typeof checkOwnerAccess !== 'function' || !(await checkOwnerAccess())) throw new Error('owner');
      await db.collection('subtitles').doc('_appSettings').set({ fboxDomains: arr }, { merge: true });
      ok = true;
      if (okText) notify(okText, 'success');
    } catch (_) {
      fboxDomainList = before;
      notify('تعذّر حفظ الأسماء — رجعت القائمة كما كانت.');
    } finally {
      namesBusy = false;
      render(true);
    }
    return ok;
  }
  async function addName() {
    const h = cleanHost(namesDraft);
    const bad = nameProblem(h, -1);
    if (bad) { notify(bad); return; }
    if (fboxDomainList.length >= 12) { notify('القائمة وصلت ١٢ اسماً — احذف اسماً أولاً.'); return; }
    if (await saveFboxNames(fboxDomainList.concat([h]), 'أُضيف «' + h + '» ✓')) { namesDraft = ''; render(true); }
  }
  async function saveEditedName(i) {
    const h = cleanHost(namesEditDraft);
    const bad = nameProblem(h, i);
    if (bad) { notify(bad); return; }
    const next = fboxDomainList.slice();
    next[i] = h;
    if (await saveFboxNames(next, 'تم تعديل الاسم ✓')) { namesEditing = -1; namesEditDraft = ''; render(true); }
  }
  function chooseName(i) {
    const next = fboxDomainList.slice();
    const h = next.splice(i, 1)[0];
    next.unshift(h);
    fboxMark(h, false);   // 322.3.121: the owner says it works — no old failure kept for it
    saveFboxNames(next, 'صار «' + h + '» هو المختار ✓');
  }
  function moveName(i, dir) {
    const j = i + dir;
    if (i < 1 || j < 1 || j >= fboxDomainList.length) return;   // the chosen one moves by «اختيار» only
    const next = fboxDomainList.slice();
    const t = next[i]; next[i] = next[j]; next[j] = t;
    saveFboxNames(next, '');
  }
  function deleteName(i) {
    const h = fboxDomainList[i];
    if (fboxDomainList.length < 2) { notify('خلّ اسماً واحداً على الأقل في القائمة.'); return; }
    const after = i === 0 ? '\nويصير «' + fboxDomainList[1] + '» هو المختار.' : '';
    if (!confirm('حذف «' + h + '» من أسماء fbox؟' + after + '\nالسيرفرات المحفوظة ما تتأثر.')) return;
    const next = fboxDomainList.slice();
    next.splice(i, 1);
    if (namesEditing === i) namesEditing = -1;
    saveFboxNames(next, 'حُذف «' + h + '» ✓');
  }
  function namesBox() {
    const box = document.createElement('div');
    box.id = 'subhub-fbox-names-box';
    box.style.cssText = 'border:1px solid #6b5520;border-radius:12px;background:#0b1a26;padding:8px;display:flex;flex-direction:column;gap:6px';
    const head = document.createElement('div');
    head.style.cssText = 'color:#fcd34d;font-weight:900;font-size:.76rem;line-height:1.6';
    head.textContent = '📦 أسماء fbox';
    const note = document.createElement('div');
    note.style.cssText = 'color:#a9b7cb;font-size:.64rem;line-height:1.7';
    note.textContent = 'الاسم المختار ✓ هو الذي يُفتح دائماً. وعند المشترك: إن لم تعمل صفحته ينتقل التطبيق إلى الاسم التالي بالترتيب. السيرفرات المحفوظة تعمل على كل الأسماء.';
    box.appendChild(head);
    box.appendChild(note);
    const list = fboxDomainList.slice();
    list.forEach(function (h, i) {
      const chosen = i === 0;
      const row = document.createElement('div');
      row.style.cssText = 'display:flex;flex-direction:column;gap:5px;border-radius:10px;padding:6px 8px;' +
        'border:1px solid ' + (chosen ? '#16a34a' : '#1f3b4d') + ';background:' + (chosen ? '#0d2a1b' : '#0b2230');
      const top = document.createElement('div');
      top.style.cssText = 'display:flex;align-items:center;gap:6px;min-width:0';
      const num = document.createElement('span');
      num.style.cssText = 'flex:none;min-width:22px;text-align:center;font-weight:900;font-size:.74rem;color:' + (chosen ? '#86efac' : '#94a3b8');
      num.textContent = chosen ? '✓' : arNum(i + 1);
      top.appendChild(num);
      if (namesEditing === i) {
        const ei = namesInput(namesEditDraft, h, function (v) { namesEditDraft = v; }, function () { saveEditedName(i); });
        top.appendChild(ei);
        row.appendChild(top);
        const acts = document.createElement('div');
        acts.style.cssText = 'display:grid;grid-template-columns:1fr 1fr;gap:5px';
        acts.appendChild(smallBtn('💾 حفظ', 'play', namesBusy, function () { saveEditedName(i); }));
        acts.appendChild(smallBtn('✕ إلغاء', 'edit', namesBusy, function () { namesEditing = -1; namesEditDraft = ''; render(true); }));
        row.appendChild(acts);
        box.appendChild(row);
        return;
      }
      const nm = document.createElement('b');
      nm.dir = 'ltr';
      nm.style.cssText = 'flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:.8rem;text-align:right';
      nm.textContent = h;
      top.appendChild(nm);
      if (chosen) {
        const tag = document.createElement('small');
        tag.style.cssText = 'flex:none;color:#86efac;font-size:.62rem;font-weight:900';
        tag.textContent = 'المستعمل';
        top.appendChild(tag);
      }
      row.appendChild(top);
      const acts = document.createElement('div');
      acts.style.cssText = 'display:grid;gap:5px;grid-template-columns:' + (chosen ? '1fr 1fr' : '1.35fr .75fr .75fr 1fr 1fr');
      if (!chosen) {
        acts.appendChild(smallBtn('✓ اختيار', 'play', namesBusy, function () { chooseName(i); }));
        acts.appendChild(smallBtn('⬆️', 'edit', namesBusy || i === 1, function () { moveName(i, -1); }));
        acts.appendChild(smallBtn('⬇️', 'edit', namesBusy || i === list.length - 1, function () { moveName(i, 1); }));
      }
      acts.appendChild(smallBtn('✏️ تعديل', 'edit', namesBusy, function () { namesEditing = i; namesEditDraft = h; render(true); }));
      acts.appendChild(smallBtn('🗑️ حذف', 'danger', namesBusy || list.length < 2, function () { deleteName(i); }));
      row.appendChild(acts);
      box.appendChild(row);
    });
    const addLabel = document.createElement('div');
    addLabel.style.cssText = 'color:#cbd5e1;font-size:.66rem;font-weight:800;margin-top:2px';
    addLabel.textContent = '➕ اسم جديد — اكتبه أو الصق رابطاً من الموقع';
    const addRow = document.createElement('div');
    addRow.style.cssText = 'display:grid;grid-template-columns:1fr auto;gap:6px;align-items:center';
    const ai = namesInput(namesDraft, 'tinyzone.sx', function (v) { namesDraft = v; }, function () { addName(); });
    const ab = smallBtn('➕ إضافة', 'play', namesBusy || list.length >= 12, function () { addName(); });
    ab.style.padding = '4px 14px';
    addRow.appendChild(ai);
    addRow.appendChild(ab);
    box.appendChild(addLabel);
    box.appendChild(addRow);
    return box;
  }

  function buildSection(movieId) {
    const ctx = { movieId: movieId, kind: 'movie' };
    const sec = document.createElement('section');
    sec.id = 'subhub-applist-section';
    sec.style.cssText =
      'margin:12px 0 6px;border:1px solid ' + ACCENT + '66;border-radius:16px;background:#071a24;' +
      'padding:12px;direction:rtl;color:#fff;display:flex;flex-direction:column;gap:8px';
    const head = document.createElement('div');
    head.style.cssText = 'font-weight:900;font-size:.92rem;line-height:1.6';
    head.textContent = '📺 سيرفرات التطبيق — تحقّق';
    const count = document.createElement('small');
    count.style.cssText = 'color:#7dd3fc;font-weight:800;margin-inline-start:6px';
    count.textContent = '(' + arNum(film.items.length) + '/' + arNum(MAX) + ')';
    head.appendChild(count);
    const hint = document.createElement('div');
    hint.style.cssText = 'color:#a9b7cb;font-size:.68rem;line-height:1.7';
    hint.textContent = film.loaded
      ? 'تضغط «➕ إضافة سيرفر»، تجرّب السيرفر في الصفحة، ثم تحفظه. الرقم على 👁 هو رقمه عند المشترك («مشاهدة بالتطبيق ٢»).'
      : 'جارٍ تحميل سيرفرات التطبيق…';
    sec.appendChild(head);
    sec.appendChild(hint);

    film.items.forEach(function (it, i) {
      const row = document.createElement('div');
      row.style.cssText = 'border:1px solid #1f3b4d;border-radius:12px;background:#0b2230;padding:8px;display:flex;flex-direction:column;gap:6px';
      const top = document.createElement('div');
      top.style.cssText = 'display:flex;align-items:center;gap:8px;min-width:0';
      const n = viewerNumber('list:' + it.id);
      const badge = document.createElement('span');
      badge.style.cssText = 'flex:none;font-size:.7rem;font-weight:900;padding:2px 8px;border-radius:9px;background:#0b1625;border:1px solid ' + ACCENT + ';color:#fff';
      badge.textContent = n ? '👁 ' + arNum(n) : '👁 —';
      badge.title = n ? 'يظهر للمشترك: مشاهدة بالتطبيق ' + arNum(n) : 'لا يظهر للمشترك';
      const name = document.createElement('b');
      name.style.cssText = 'min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:.84rem';
      name.textContent = (isFboxPage(it.pageUrl) ? '📦 ' : '') + it.label;
      if (isFboxPage(it.pageUrl)) name.title = 'من fbox';
      const meta = document.createElement('small');
      meta.style.cssText = 'margin-inline-start:auto;flex:none;color:#86efac;font-size:.62rem;font-weight:800';
      const off = clampOffset(it.subtitleOffsetMs);
      meta.textContent = Math.abs(off) < 50 ? '' : ('⏱ ' + (off > 0 ? '+' : '−') + (Math.abs(off) / 1000).toFixed(1) + ' ث');
      top.appendChild(badge);
      top.appendChild(name);
      top.appendChild(meta);
      const acts = document.createElement('div');
      acts.style.cssText = 'display:grid;grid-template-columns:1.3fr 1fr .7fr .7fr .8fr;gap:5px';
      acts.appendChild(smallBtn('▶ تشغيل', 'play', false, function () { openSession(ctx, it.id, false, ''); }));
      acts.appendChild(smallBtn('✏️ تعديل', 'edit', false, function () { openSession(ctx, it.id, true, ''); }));
      acts.appendChild(smallBtn('⬆️', 'edit', i === 0, function () { moveItem(ctx, it.id, -1); }));
      acts.appendChild(smallBtn('⬇️', 'edit', i === film.items.length - 1, function () { moveItem(ctx, it.id, 1); }));
      acts.appendChild(smallBtn('🗑️', 'danger', false, function () {
        if (confirm('حذف السيرفر «' + it.label + '» من سيرفرات التطبيق لهذا الفيلم؟')) deleteItem(ctx, it.id);
      }));
      row.appendChild(top);
      row.appendChild(acts);
      sec.appendChild(row);
    });

    const full = film.items.length >= MAX;
    const add = document.createElement('button');
    add.type = 'button';
    add.textContent = full ? 'وصلت للحد الأقصى (١٠ سيرفرات)' : '➕ إضافة سيرفر';
    add.disabled = full || !film.loaded;
    add.style.cssText =
      'min-height:44px;border-radius:12px;font:inherit;font-weight:900;font-size:.82rem;cursor:pointer;color:#fff;' +
      'border:1px dashed ' + ACCENT + ';background:#0a2a36;opacity:' + (add.disabled ? '.45' : '1');
    add.addEventListener('click', function (ev) {
      ev.preventDefault();
      if (!add.disabled) openSession(ctx, null, true, '');
    });
    // 322.3.117: servers can be added from fbox too.
    // 322.3.118: always — without a saved page it starts from fbox search.
    // 322.3.121: on the chosen name only. The film's saved page opens when it is
    // this film's page; a page of another film is not used (the search opens), and
    // the line below says so with «🗑️ مسح».
    const fbox = filmFboxUrl();
    const fboxFits = !!fbox && samePageFilm(fbox, filmTitle());
    add.textContent = full ? add.textContent : '➕ إضافة سيرفر من Moviesmod';
    const addF = document.createElement('button');
    addF.type = 'button';
    addF.textContent = full ? 'وصلت للحد الأقصى (١٠ سيرفرات)' : (fboxFits ? '📦 إضافة سيرفر من fbox' : '📦 إضافة من fbox (بحث)');
    addF.disabled = full || !film.loaded || namesBusy;
    addF.style.cssText = add.style.cssText.replace(ACCENT, '#f6b93b');
    addF.style.opacity = addF.disabled ? '.45' : '1';
    addF.addEventListener('click', function (ev) {
      ev.preventDefault();
      if (addF.disabled || namesBusy) return;
      openSession(ctx, null, true, fboxFits ? fbox : 'https://' + fboxChosen() + '/');
    });
    const pair = document.createElement('div');
    pair.style.cssText = 'display:grid;grid-template-columns:1fr 1fr;gap:6px';
    pair.appendChild(add);
    pair.appendChild(addF);
    sec.appendChild(pair);
    const dom = document.createElement('div');
    dom.style.cssText = 'display:flex;align-items:center;gap:6px;color:#7f93ab;font-size:.62rem;line-height:1.6';
    // 322.3.119: «📦 fbox — المختار: tinyzone.sx · ٦ احتياط · movie-…» + the names box.
    const domTx = document.createElement('div');
    domTx.style.cssText = 'flex:1;min-width:0;display:flex;flex-direction:column;gap:1px';
    const domLine = document.createElement('span');
    domLine.appendChild(document.createTextNode('📦 fbox — المختار: '));
    domLine.appendChild(ltrText(fboxDomainList[0] || FBOX_DEFAULT_DOMAINS[0], '#cbd5e1'));
    if (fboxDomainList.length > 1) domLine.appendChild(document.createTextNode(' · ' + arNum(fboxDomainList.length - 1) + ' احتياط'));
    domTx.appendChild(domLine);
    if (fbox) {
      // this film's page on fbox — 322.3.121: «⚠️ صفحة فيلم آخر» when it is not this
      // film's page, and «🗑️ مسح» so the next «📦 إضافة» starts from the search.
      const pageRow = document.createElement('div');
      pageRow.style.cssText = 'display:flex;align-items:center;gap:6px;flex-wrap:wrap';
      const pageId = ltrText(fboxIdOf(fbox), '');
      pageId.style.cssText = 'direction:ltr;overflow-wrap:anywhere;opacity:.8';
      pageRow.appendChild(pageId);
      if (!fboxFits) {
        const warn = document.createElement('span');
        warn.style.cssText = 'color:#fbbf24;font-weight:900';
        warn.textContent = '⚠️ صفحة فيلم آخر';
        pageRow.appendChild(warn);
      }
      const clr = smallBtn('🗑️ مسح', 'danger', namesBusy, function () { clearFilmPage(ctx); });
      clr.style.flex = 'none';
      clr.style.padding = '2px 10px';
      pageRow.appendChild(clr);
      domTx.appendChild(pageRow);
    }
    const domBtn = smallBtn(namesOpen ? '✕ إغلاق' : '✏️ الأسماء', 'edit', false, async function () {
      if (namesOpen) { namesOpen = false; namesEditing = -1; render(true); return; }
      if (!isOwner() || typeof checkOwnerAccess !== 'function' || !(await checkOwnerAccess())) return;
      namesOpen = true; namesEditing = -1;
      render(true);
      loadFboxDomains(true).then(function () { if (namesOpen) render(true); }).catch(function () {});
    });
    domBtn.style.flex = 'none';
    domBtn.style.padding = '4px 10px';
    dom.appendChild(domTx);
    dom.appendChild(domBtn);
    sec.appendChild(dom);
    if (namesOpen) sec.appendChild(namesBox());
    sec.appendChild(legacyBlock());   // moves the existing block (with its cards) here
    return sec;
  }

  function viewerButton(it) {
    const b = document.createElement('button');
    b.type = 'button';
    b.id = 'subhub-applist-btn-' + it.id;
    b.className = 'watch-pill subhub-applist-btn';
    b.setAttribute('data-applist', 'app');
    b.style.cssText = 'border-color:' + ACCENT + ';cursor:pointer';
    // SubHub Web numbers app buttons in page order (مشاهدة بالتطبيق ١ / ٢ …).
    b.textContent = '🎬 مشاهدة بالتطبيق';
    b.title = 'مشاهدة بالتطبيق';
    b.addEventListener('click', function () {
      openSession({ movieId: film.movieId, kind: 'movie' }, it.id, false, '');
    });
    return b;
  }

  let lastSig = '';
  function render(force) {
    const sel = current();
    const doc = movieDoc();
    const id = sel ? String(sel.id || '') : '';
    if (!sel || !doc || doc.contentType === 'series' || film.movieId !== id) {
      removeFilmUi();
      lastSig = '';
      return;
    }
    if (isOwner()) {
      [].slice.call(document.querySelectorAll('.subhub-applist-btn[data-applist="app"]')).forEach(function (b) { b.remove(); });
      const grid = document.querySelector('.owner-watch-pills');
      const existing = document.getElementById('subhub-applist-section');
      if (!grid) { if (existing) existing.remove(); lastSig = ''; return; }
      const sig = JSON.stringify([id, film.loaded, filmFboxUrl(), film.items.map(function (x) { return [x.id, x.label, x.subtitleOffsetMs, viewerNumber('list:' + x.id)]; })]);
      if (existing && existing.isConnected && !force && sig === lastSig && existing.previousElementSibling === grid) return;
      const sec = buildSection(id);
      if (existing) existing.remove();
      grid.insertAdjacentElement('afterend', sec);
      lastSig = sig;
      return;
    }
    const s = document.getElementById('subhub-applist-section');
    if (s) s.remove();
    lastSig = '';
    const grid = document.querySelector('.watch-pills:not(.owner-watch-pills)');
    const want = film.loaded ? film.items : [];
    const wantIds = want.map(function (x) { return 'subhub-applist-btn-' + x.id; });
    [].slice.call(document.querySelectorAll('.subhub-applist-btn[data-applist="app"]')).forEach(function (b) {
      if (!grid || b.parentElement !== grid || wantIds.indexOf(b.id) < 0) b.remove();
    });
    if (!grid) return;
    want.forEach(function (it, i) {
      const order = Number((0.1 + i * 0.01).toFixed(2));
      let b = document.getElementById('subhub-applist-btn-' + it.id);
      if (b && b.parentElement === grid && b.getAttribute('data-app-order') === String(order)) return;
      if (!b) b = viewerButton(it);
      if (typeof window.__subhubPlaceAppCardV3106 === 'function') {
        window.__subhubPlaceAppCardV3106(grid, b, 'list', order);
      } else {
        grid.appendChild(b);
      }
    });
  }

  function install() {
    if (!fboxDomainsLoaded && !fboxDomainsTried && isOwner()) {
      fboxDomainsTried = true;
      loadFboxDomains(false).then(function () { render(true); }).catch(function () {});
    }
    const sel = current();
    const doc = movieDoc();
    if (!sel || !doc || doc.contentType === 'series') {
      if (!sel) film = { movieId: '', loaded: false, loading: false, failedAt: 0, items: [] };
      removeFilmUi();
      exposeItems();
      return;
    }
    const id = String(sel.id || '');
    if (film.movieId !== id) {
      film = { movieId: id, loaded: false, loading: false, failedAt: 0, items: [] };
      namesOpen = false; namesEditing = -1;   // 322.3.119: the names box closes on another film
      removeFilmUi();
      lastSig = '';
    }
    if (!film.loaded && !film.loading && (!film.failedAt || Date.now() - film.failedAt > 15000)) {
      loadFilm(id, false).then(function () { exposeItems(); render(true); }).catch(function () {});
    }
    exposeItems();
    render(false);
  }

  install();
  setInterval(install, 900);
})();
