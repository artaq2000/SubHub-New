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
  // 322.3.123: Moviesmod moved too — StreamBoppo, StreamDude, StreamZippy… are the same site
  // (the same pages by TMDB number, /movie/27205, and the same servers). Same names system
  // as fbox, in its own box: the chosen name (✓) opens, viewers move on to the next names,
  // and a saved page keeps moviesmod.gd (every app version knows it) and opens on the
  // chosen name — so the servers saved before the move work again on the new names.
  const MM_DEFAULT_DOMAINS = ['streamboppo.cyou', 'streamdude.cyou', 'streamzippy.cyou', 'moviesmod.gd'];
  const MM_STORE_HOST = 'moviesmod.gd';
  let mmDomainList = MM_DEFAULT_DOMAINS.slice();
  const FAMS = {
    fbox: { id: 'fbox', icon: '📦', word: 'fbox', field: 'fboxDomains', defaults: FBOX_DEFAULT_DOMAINS,
      badKey: FBOX_BAD_KEY, checkKey: 'subhub_fbox_check_v3122', boxId: 'subhub-fbox-names-box', sample: 'tinyzone.sx' },
    mm: { id: 'mm', icon: '🎬', word: 'Moviesmod', field: 'moviesmodDomains', defaults: MM_DEFAULT_DOMAINS,
      badKey: 'subhub_mm_bad_v3123', checkKey: 'subhub_mm_check_v3123', boxId: 'subhub-mm-names-box', sample: 'streamboppo.cyou' }
  };
  function famOf(raw) { return raw === 'mm' ? 'mm' : raw === 'fbox' ? 'fbox' : ''; }
  function famList(f) { return f === 'mm' ? mmDomainList : fboxDomainList; }
  function setFamList(f, arr) { if (f === 'mm') mmDomainList = arr; else fboxDomainList = arr; }
  const badMem = { fbox: null, mm: null };
  function siteBadMap(f) {
    if (!badMem[f]) {
      badMem[f] = {};
      try {
        const m = JSON.parse(localStorage.getItem(FAMS[f].badKey) || '{}');
        if (m && typeof m === 'object' && !Array.isArray(m)) badMem[f] = m;
      } catch (_) {}
    }
    const now = Date.now(), m = badMem[f];
    Object.keys(m).forEach(function (h) {
      const t = Number(m[h]);
      if (!cleanHost(h) || !(t > now - FBOX_BAD_MS && t <= now + 60000)) delete m[h];
    });
    return m;
  }
  function siteMark(f, h, bad) {
    const v = cleanHost(h);
    if (!v || !FAMS[f]) return;
    const m = siteBadMap(f);
    if (bad) m[v] = Date.now(); else delete m[v];
    try { localStorage.setItem(FAMS[f].badKey, JSON.stringify(m)); } catch (_) {}
  }
  function fboxBadMap() { return siteBadMap('fbox'); }
  function fboxMark(h, bad) { siteMark('fbox', h, bad); }
  // 322.3.123: what this device learned by itself — the name that worked last (used first
  // while the owner's chosen name is still the one it was then), and the new home a site
  // said it moved to (it takes the old name's place). Kept 3 days.
  const GOOD_KEY = 'subhub_site_good_v3123';
  const LEARNED_KEY = 'subhub_site_learned_v3123';
  const MEMORY_MS = 3 * 24 * 3600 * 1000;
  function memRead(key) {
    try {
      const v = JSON.parse(localStorage.getItem(key) || '{}');
      return v && typeof v === 'object' && !Array.isArray(v) ? v : {};
    } catch (_) { return {}; }
  }
  function memWrite(key, v) {
    try { localStorage.setItem(key, JSON.stringify(v)); } catch (_) {}
  }
  function fresh(at) {
    const t = Number(at), now = Date.now();
    return t > now - MEMORY_MS && t <= now + 60000;
  }
  function goodHost(f) {
    const g = memRead(GOOD_KEY)[f];
    if (!g || !fresh(g.at) || g.chosen !== (famList(f)[0] || '')) return '';
    return cleanHost(g.host);
  }
  function setGood(f, h) {
    const m = memRead(GOOD_KEY);
    m[f] = { host: h, chosen: famList(f)[0] || '', at: Date.now() };
    memWrite(GOOD_KEY, m);
  }
  function learnedMap(f) {
    const all = memRead(LEARNED_KEY);
    const m = all[f] && typeof all[f] === 'object' ? all[f] : {};
    const out = {};
    Object.keys(m).forEach(function (from) {
      const e = m[from], to = e ? cleanHost(e.to) : '';
      if (cleanHost(from) === from && to && to !== from && fresh(e.at)) out[from] = to;
    });
    return out;
  }
  function setLearned(f, from, to) {
    const all = memRead(LEARNED_KEY);
    const m = all[f] && typeof all[f] === 'object' ? all[f] : {};
    m[from] = { to: to, at: Date.now() };
    const keys = Object.keys(m);
    if (keys.length > 20) delete m[keys[0]];
    all[f] = m;
    memWrite(LEARNED_KEY, all);
  }
  // The owner's order as this device should try it: a name a site said it left is replaced
  // by its new home, the name that worked last comes first, names that failed lately last.
  function siteOrder(f) {
    const bad = siteBadMap(f), learned = learnedMap(f);
    const base = famList(f).length ? famList(f) : FAMS[f].defaults;
    const list = [];
    base.forEach(function (h) { const v = learned[h] || h; if (list.indexOf(v) < 0) list.push(v); });
    let good = goodHost(f);
    if (good && learned[good]) good = learned[good];   // it worked, then said it moved
    if (good) {
      const k = list.indexOf(good);
      if (k > 0) list.splice(k, 1);
      if (k !== 0) list.unshift(good);
    }
    return list.filter(function (h) { return !bad[h]; }).concat(list.filter(function (h) { return !!bad[h]; }));
  }
  function fboxOrder() {
    return siteOrder('fbox');
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
  // 322.3.123: a Moviesmod name (the owner's list, the known ones, moviesmod.gd itself).
  function isMmHost(h) {
    const v = cleanHost(h);
    if (!v) return false;
    if (v === MM_STORE_HOST || /\.moviesmod\.gd$/.test(v)) return true;
    return mmDomainList.concat(MM_DEFAULT_DOMAINS).indexOf(v) >= 0;
  }
  // A Moviesmod page as it is saved: on moviesmod.gd (it opens on the chosen name).
  function mmStoreUrl(raw) {
    try {
      const u = new URL(String(raw || ''));
      if (u.protocol !== 'https:' || !isMmHost(u.hostname)) return String(raw || '');
      u.hostname = MM_STORE_HOST; u.hash = '';
      return u.href;
    } catch (_) { return String(raw || ''); }
  }
  function isMmPage(raw) {
    try { const u = new URL(String(raw || '')); return u.protocol === 'https:' && isMmHost(u.hostname); }
    catch (_) { return false; }
  }
  // The Moviesmod film / episode page path (the 🩺 check of the Moviesmod names opens it).
  const MM_PATH = /^\/(movie|tv)\/\d{1,12}$|^\/watch\/(movie|tv)\/\d{1,12}(\/\d{1,3}\/\d{1,4})?$/;
  function onName(raw, host) {
    try { const u = new URL(String(raw || '')); u.hostname = host; u.hash = ''; return u.href; }
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
      const data = d.exists ? (d.data() || {}) : {};
      const arr = Array.isArray(data.fboxDomains) ? data.fboxDomains.map(cleanHost).filter(Boolean) : [];
      if (arr.length) fboxDomainList = arr.slice(0, 12);
      // 322.3.123: the Moviesmod names and the servers' «⚙️» taps live in the same document.
      const mm = Array.isArray(data.moviesmodDomains) ? data.moviesmodDomains.map(cleanHost).filter(Boolean) : [];
      if (mm.length) mmDomainList = mm.slice(0, 12);
      serverRules = cleanRules(data.serverRules);
      fboxDomainsLoaded = true;
      pushSiteNames();
    } catch (_) {}
    return fboxDomainList;
  }
  // 322.3.123: the app keeps the names too, so the old cards (that send no names) also open
  // Moviesmod on the chosen name.
  function pushSiteNames() {
    try {
      const b = window.SubHubAndroidBridge;
      if (b && typeof b.siteNamesV3123 === 'function') {
        b.siteNamesV3123(token, JSON.stringify({ mm: mmDomainList.slice(0, 12), fbox: fboxDomainList.slice(0, 12) }));
      }
    } catch (_) {}
  }
  // 322.3.123: the owner's «⚙️» per server name (all films): {taps: 0–6, gap: 1–5 s}.
  let serverRules = {};
  function cleanRules(raw) {
    const out = {};
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return out;
    Object.keys(raw).slice(0, 200).forEach(function (k) {
      const key = String(k).toLowerCase().replace(/[^a-z0-9._-]+/g, '-').slice(0, 60);
      const r = raw[k];
      const taps = r ? Number(r.taps) : NaN, gap = r ? Number(r.gap) : NaN;
      if (key && Number.isInteger(taps) && taps >= 0 && taps <= 6) {
        out[key] = { taps: taps, gap: Number.isInteger(gap) && gap >= 1 && gap <= 5 ? gap : 2 };
      }
    });
    return out;
  }
  function ruleFor(label) {
    return serverRules[keyOf(label)] || null;
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
      if (u.protocol === 'https:' && isMmHost(h)) return mmStoreUrl(u.href);   // 322.3.123: kept as moviesmod.gd
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
      if (u.protocol !== 'https:' || h === 'moviesmod.gd' || h.endsWith('.moviesmod.gd') || fboxHost(h) || isMmHost(h)) return '';
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

  // 322.3.127: ONE watch place per film, shared with the site page: its own servers
  // (VidLink and the others, site v405) keep it in «subhub_site_resume_v405» —
  // {movieId: {t, d, at}} in seconds — and read it when they open. Our players
  // read it too (the latest stop wins, against the app's own saved place) and
  // write it as they play, so every server — site or app — goes on from there.
  const SITE_RESUME_KEY = 'subhub_site_resume_v405';
  function siteResumeMap() {
    try { return JSON.parse(localStorage.getItem(SITE_RESUME_KEY) || '{}') || {}; }
    catch (_) { return {}; }
  }
  function filmProgress(mid) {
    const id = String(mid || '');
    const out = { movieId: id, resumeMs: 0, at: 0 };
    try {
      const m = siteResumeMap()[id];
      const t = m ? Number(m.t) : 0, d = m ? Number(m.d) : 0;
      if (t >= 5 && (!(d > 0) || t < d * 0.95)) {
        out.resumeMs = Math.round(t * 1000);
        out.at = Math.max(0, Number(m.at) || 0);
      }
    } catch (_) {}
    return out;
  }
  // Called by the app player every few seconds (and at its end) for a film.
  window.__subhubFilmProgressV3127 = function (mid, t, d) {
    try {
      const id = String(mid || '');
      t = Number(t) || 0; d = Number(d) || 0;
      if (!id || !(t >= 0)) return;
      const map = siteResumeMap();
      if (d > 0 && t >= d * 0.95) delete map[id];          // watched: the next time starts over
      else if (t >= 5) map[id] = { t: Math.floor(t), d: Math.floor(d), at: Date.now() };
      else return;
      const ks = Object.keys(map);
      if (ks.length > 80) {
        ks.sort(function (a, b) { return ((map[a] || {}).at || 0) - ((map[b] || {}).at || 0); })
          .slice(0, ks.length - 80).forEach(function (k) { delete map[k]; });
      }
      localStorage.setItem(SITE_RESUME_KEY, JSON.stringify(map));
    } catch (_) {}
  };

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
      const hadPage = !!startPage;
      // 322.3.121: fbox — the chosen name (✓) opens.
      //   owner adding / changing a server: exactly the chosen name. A film page
      //   opens as it is; any other fbox page (their home) starts the search for
      //   this film's title there.
      //   playing a saved server: the chosen name first; the player moves to the
      //   next names by itself (names that failed lately on this device last).
      // 322.3.123: Moviesmod the same way (its own names): the owner gets exactly the
      // chosen name, viewers this device's order (siteOrder) — the same page path.
      let searchText = '', fboxHosts = [], mmHosts = [], site = 'moviesmod';
      if (isFboxAny(startPage) || isFboxPage(startPage)) {
        site = 'fbox';
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
      } else {
        await loadFboxDomains(false);
        if (current() !== selected) return;
        const page = startPage || ('https://' + MM_STORE_HOST + '/' + kind + '/' + encodeURIComponent(tmdbId));
        if (isMmPage(page)) {
          if (!auto) {
            startPage = onName(page, mmDomainList[0] || MM_DEFAULT_DOMAINS[0]);
          } else {
            const order = siteOrder('mm');
            startPage = onName(page, order[0] || mmDomainList[0] || MM_DEFAULT_DOMAINS[0]);
            mmHosts = order.slice(1, 12);
          }
        }
      }
      // 322.3.123: the owner's «⚙️» taps for this server (all films) — saved servers only.
      const rule = auto && item ? ruleFor(item.label) : null;
      const base = String(selected.id || ('tmdb_' + kind + '_' + tmdbId)).replace(/[^A-Za-z0-9_.-]/g, '_').slice(0, 60);
      const stableKey = (base + (ctx.kind === 'tv' ? '_' + ekOf(ctx) : '') + '_' + (item ? item.id : 'new')).slice(0, 100);
      // 322.3.127: one watch place per film / episode for every server (the key above
      // holds the server: each server used to keep its own place).
      const titleKey = (base + (ctx.kind === 'tv' ? '_' + ekOf(ctx) : '')).slice(0, 100);
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
        site: site,
        searchText: searchText,
        fboxHosts: fboxHosts,
        // 322.3.123: the next Moviesmod names (viewers), and the owner's «⚙️» taps.
        mmHosts: mmHosts,
        tapRule: rule ? { taps: rule.taps, gapMs: rule.gap * 1000 } : null,
        session: session,
        movieId: stableKey,
        resumeKey: stableKey,
        titleKey: titleKey,
        // 322.3.127: films — the place the site page keeps for this film (its own servers
        // and ours), and where ours report it.
        filmProgress: ctx.kind === 'tv' ? null : filmProgress(ctx.movieId),
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
        openChooser: owner && !auto && hadPage,
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
  // 322.3.123: the same for both families (fbox / Moviesmod); a name that opened is used
  // first on this device while the owner's chosen name stays the same.
  window.__subhubSiteHostV3123 = function (fam, rawHost, ok) {
    const f = famOf(fam), h = cleanHost(rawHost);
    if (!f || !h) return;
    siteMark(f, h, !ok);
    if (ok) setGood(f, h);
  };
  // 322.3.123: a site's own page said it moved (from → to): this device opens «to» in its place.
  window.__subhubSiteMovedV3123 = function (fam, rawFrom, rawTo) {
    const f = famOf(fam), from = cleanHost(rawFrom), to = cleanHost(rawTo);
    if (!f || !from || !to || from === to) return;
    setLearned(f, from, to);
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
    // 322.3.123: the server's taps on play (all films and episodes)
    row.appendChild(btn('⚙️ ضغطات' + (ruleFor(item.label) ? ' ' + ruleText(ruleFor(item.label)) : ''),
      'border:1px solid #36506b;background:#0c1d2d', false, function () { ruleSheet(item.label); }));
    row.appendChild(btn('إلغاء', 'border:1px solid #475569;background:#1e293b', false, function () {}));
    box.appendChild(title);
    box.appendChild(msg);
    box.appendChild(row);
    bg.appendChild(box);
    bg.addEventListener('click', function (e) { if (e.target === bg) bg.remove(); });
    document.body.appendChild(bg);
  }

  // 322.3.123: «⚙️» — the owner's taps for a server NAME (all films and episodes): how
  // many times the app presses play in that server's own player, and how long between the
  // presses. «تلقائي» = the usual way (a press now and then for about 8 seconds). 0 = never
  // press (the video starts by itself, and a press would pause it). Viewers get it at once.
  function ruleText(rule) {
    if (!rule) return '';
    return '👆' + arNum(rule.taps) + (rule.taps ? ' · ' + arNum(rule.gap) + 'ث' : '');
  }
  async function saveRule(label, rule) {
    const key = keyOf(label);
    if (!key || !isOwner()) return false;
    try {
      if (typeof checkOwnerAccess !== 'function' || !(await checkOwnerAccess())) return false;
      await db.collection('subtitles').doc('_appSettings').set({
        serverRules: { [key]: rule ? { taps: rule.taps, gap: rule.gap } : firebase.firestore.FieldValue.delete() }
      }, { merge: true });
      if (rule) serverRules[key] = { taps: rule.taps, gap: rule.gap }; else delete serverRules[key];
      notify(rule ? 'حُفظ لـ «' + label + '»: ' + arNum(rule.taps) + ' ضغطات' + (rule.taps ? '، كل ' + arNum(rule.gap) + ' ث' : '') + ' ✓'
        : 'رجع «' + label + '» للطريقة المعتادة ✓', 'success');
      lastSig = '';
      render(true);
      return true;
    } catch (_) {
      notify('تعذّر حفظ الإعداد.');
      return false;
    }
  }
  function ruleSheet(label) {
    const old = document.getElementById('subhub-applist-rule');
    if (old) old.remove();
    const cur = ruleFor(label);
    let taps = cur ? cur.taps : -1;
    let gap = cur ? cur.gap : 2;
    const bg = document.createElement('div');
    bg.id = 'subhub-applist-rule';
    bg.style.cssText = 'position:fixed;inset:0;z-index:2147483000;background:rgba(0,0,0,.72);display:flex;align-items:flex-end;justify-content:center;padding:18px;direction:rtl';
    const box = document.createElement('div');
    box.style.cssText = 'width:min(520px,96vw);border:1px solid ' + ACCENT + ';border-radius:18px;background:#0b1625;color:#fff;padding:16px;box-shadow:0 16px 60px rgba(0,0,0,.55);display:flex;flex-direction:column;gap:10px';
    const title = document.createElement('div');
    title.style.cssText = 'font-weight:900;font-size:.98rem;line-height:1.7';
    title.appendChild(document.createTextNode('⚙️ ضغطات التشغيل — '));
    title.appendChild(ltrText(label, ''));
    const msg = document.createElement('div');
    msg.style.cssText = 'color:#b8c5d6;font-size:.8rem;line-height:1.7';
    msg.textContent = 'كم مرة يضغط التطبيق على زر التشغيل في مشغّل هذا السيرفر، وكم ثانية بين كل ضغطة. ينطبق على هذا السيرفر في كل الأفلام والحلقات، ويصل للمشتركين فوراً. «تلقائي» = الطريقة المعتادة، و٠ = لا يضغط أبداً (لسيرفر يبدأ وحده).';
    function group(name, items, get, set) {
      const wrap = document.createElement('div');
      const h = document.createElement('div');
      h.style.cssText = 'font-weight:800;font-size:.78rem;margin-bottom:6px;color:#e2e8f0';
      h.textContent = name;
      const row = document.createElement('div');
      row.style.cssText = 'display:flex;flex-wrap:wrap;gap:6px';
      function paint() {
        [].slice.call(row.children).forEach(function (b) {
          const on = Number(b.getAttribute('data-v')) === get();
          b.style.background = on ? '#0e7490' : '#0c1d2d';
          b.style.borderColor = on ? ACCENT : '#36506b';
        });
      }
      items.forEach(function (it) {
        const b = document.createElement('button');
        b.type = 'button';
        b.textContent = it[1];
        b.setAttribute('data-v', String(it[0]));
        b.style.cssText = 'min-width:44px;min-height:38px;border-radius:10px;border:1px solid #36506b;color:#fff;font:inherit;font-weight:900;font-size:.8rem;padding:2px 10px;background:#0c1d2d';
        b.addEventListener('click', function () { set(it[0]); paint(); gapWrap.style.opacity = taps > 0 ? '1' : '.4'; });
        row.appendChild(b);
      });
      wrap.appendChild(h);
      wrap.appendChild(row);
      paint();
      return wrap;
    }
    const tapsWrap = group('عدد الضغطات', [[-1, 'تلقائي'], [0, '٠'], [1, '١'], [2, '٢'], [3, '٣'], [4, '٤'], [5, '٥'], [6, '٦']],
      function () { return taps; }, function (v) { taps = v; });
    tapsWrap.setAttribute('data-rule', 'taps');
    const gapWrap = group('الوقت بين الضغطات', [[1, '١ ث'], [2, '٢ ث'], [3, '٣ ث'], [4, '٤ ث'], [5, '٥ ث']],
      function () { return gap; }, function (v) { gap = v; });
    gapWrap.setAttribute('data-rule', 'gap');
    gapWrap.style.opacity = taps > 0 ? '1' : '.4';
    const acts = document.createElement('div');
    acts.style.cssText = 'display:grid;grid-template-columns:1fr 1fr;gap:8px';
    const save = document.createElement('button');
    save.type = 'button';
    save.textContent = '💾 حفظ';
    save.style.cssText = 'min-height:44px;border-radius:11px;font:inherit;font-weight:900;color:#fff;border:1px solid #166534;background:#12301d';
    save.addEventListener('click', async function () {
      save.disabled = true;
      if (await saveRule(label, taps < 0 ? null : { taps: taps, gap: gap })) bg.remove();
      else save.disabled = false;
    });
    const cancel = document.createElement('button');
    cancel.type = 'button';
    cancel.textContent = 'إلغاء';
    cancel.style.cssText = 'min-height:44px;border-radius:11px;font:inherit;font-weight:900;color:#fff;border:1px solid #475569;background:#1e293b';
    cancel.addEventListener('click', function () { bg.remove(); });
    acts.appendChild(save);
    acts.appendChild(cancel);
    box.appendChild(title);
    box.appendChild(msg);
    box.appendChild(tapsWrap);
    box.appendChild(gapWrap);
    box.appendChild(acts);
    bg.appendChild(box);
    bg.addEventListener('click', function (e) { if (e.target === bg) bg.remove(); });
    document.body.appendChild(bg);
    loadFboxDomains(true).catch(function () {});   // the latest rules
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
    removeAutoUi();   // 322.3.127
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
  // 322.3.123: two boxes of the same kind — «📦 أسماء fbox» and «🎬 أسماء Moviesmod»
  // (subtitles/_appSettings.moviesmodDomains). One is open at a time: namesOpen = its family.
  let namesOpen = '';
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
  function nameProblem(f, h, skipIndex) {
    if (!h) return 'اكتب اسم موقع صحيح، مثل ' + FAMS[f].sample + ' — أو الصق رابطاً من الموقع';
    if (f === 'fbox' && /(^|\.)moviesmod\.gd$/.test(h)) return 'هذا اسم Moviesmod — له صندوقه «🎬 أسماء Moviesmod»';
    // 322.3.123: a name belongs to one family only (fbox pages ≠ Moviesmod pages).
    const other = f === 'fbox' ? 'mm' : 'fbox';
    if (famList(other).indexOf(h) >= 0) return '«' + h + '» في أسماء ' + FAMS[other].word + ' — احذفه من هناك أولاً';
    const at = famList(f).indexOf(h);
    if (at >= 0 && at !== skipIndex) return '«' + h + '» موجود في القائمة';
    return '';
  }
  // 322.3.121: a change counts AT ONCE on this device (the chosen name is the one
  // that opens from this moment); the site copy follows, and on failure the list
  // goes back to what it was.
  async function saveSiteNames(f, next, okText) {
    if (namesBusy || !isOwner() || !FAMS[f]) return false;
    const arr = next.map(cleanHost).filter(Boolean).filter(function (h, i, a) { return a.indexOf(h) === i; }).slice(0, 12);
    if (!arr.length) { notify('خلّ اسماً واحداً على الأقل في القائمة.'); return false; }
    const before = famList(f).slice();
    setFamList(f, arr); fboxDomainsLoaded = true;
    namesBusy = true;
    render(true);
    let ok = false;
    try {
      if (typeof checkOwnerAccess !== 'function' || !(await checkOwnerAccess())) throw new Error('owner');
      await db.collection('subtitles').doc('_appSettings').set({ [FAMS[f].field]: arr }, { merge: true });
      ok = true;
      if (okText) notify(okText, 'success');
      pushSiteNames();
    } catch (_) {
      setFamList(f, before);
      notify('تعذّر حفظ الأسماء — رجعت القائمة كما كانت.');
    } finally {
      namesBusy = false;
      render(true);
    }
    return ok;
  }
  async function addName(f) {
    const h = cleanHost(namesDraft);
    const bad = nameProblem(f, h, -1);
    if (bad) { notify(bad); return; }
    if (famList(f).length >= 12) { notify('القائمة وصلت ١٢ اسماً — احذف اسماً أولاً، أو استبدل اسماً انتقل («🔁 استبدله» بعد «🩺 فحص»).'); return; }
    if (await saveSiteNames(f, famList(f).concat([h]), 'أُضيف «' + h + '» ✓')) {
      namesDraft = '';
      render(true);
      if (!checkRun) startCheck(f, [h]);   // 322.3.122: a new name is checked at once
    }
  }
  // 322.3.123: «🔁 استبدله» on a name whose site moved (it sends to Y, or its page says «a
  // new home: Y»): Y takes its place, so the list never grows (it stays within 12). When Y
  // is already in the list the old name just goes — and if the old one was the chosen ✓,
  // Y becomes the chosen one.
  async function replaceName(f, from, raw) {
    const to = cleanHost(raw);
    const list = famList(f).slice();
    const i = list.indexOf(from);
    if (!to || i < 0 || to === from) return;
    const there = list.indexOf(to) >= 0;
    if (there) {
      if (list.length < 2) return;
      list.splice(i, 1);
      if (i === 0) { list.splice(list.indexOf(to), 1); list.unshift(to); }
    } else {
      const bad = nameProblem(f, to, i);
      if (bad) { notify(bad); return; }
      list[i] = to;
    }
    siteMark(f, to, false);
    const text = there
      ? 'حُذف «' + from + '» — «' + to + '» موجود في القائمة' + (i === 0 ? ' وصار هو المختار ✓' : ' ✓')
      : 'صار «' + to + '» مكان «' + from + '» ✓';
    if (await saveSiteNames(f, list, text)) {
      if (!there && !checkRun) startCheck(f, [to]);   // the new name is checked at once
    }
  }

  // 322.3.122: «🩺 فحص» — the names are opened one after the other in a hidden page of
  // the app (this device, this connection: SiteNameChecker), on this film's fbox page
  // when it has one (its servers are counted) or on their front page. Each row says
  // what its page answered; «🚫 محجوب على اتصالك (451)» is kept apart from a dead
  // name, because it belongs to the connection the check ran on (a UK court order,
  // applied by Cloudflare to connections it sees in the UK — some Saudi home lines
  // reach it through London), not to the name. The last answers stay on this device.
  // 322.3.123: each box has its own answers (the fbox ones keep their key); one run at a
  // time (checkFam = the box of the run). A site's own «new home: Y» page counts as moved.
  const CHECK_KEY = 'subhub_fbox_check_v3122';
  const CHECK_KEEP_MS = 7 * 24 * 3600 * 1000;
  let checkRun = '';          // the run in progress ('' = none)
  let checkFam = '';          // its box (fbox / mm)
  let checkQueue = [];        // its names
  let checkDone = {};         // its names already answered
  let checkCurrent = '';      // the name being opened now
  const checkStores = { fbox: null, mm: null };   // {meta:{at,path,film,net,total,done,running,stopped}, results:{host:{…}}}
  function checkKeyOf(f) {
    return f === 'mm' ? FAMS.mm.checkKey : CHECK_KEY;
  }
  function checkData(raw) {
    const f = famOf(raw) || 'fbox';
    if (!checkStores[f]) {
      const st = { meta: null, results: {} };
      try {
        const v = JSON.parse(localStorage.getItem(checkKeyOf(f)) || 'null');
        if (v && typeof v === 'object' && v.results && typeof v.results === 'object') {
          const now = Date.now();
          Object.keys(v.results).forEach(function (h) {
            const r = v.results[h];
            if (cleanHost(h) === h && r && typeof r === 'object' && typeof r.state === 'string'
              && Number(r.at) > now - CHECK_KEEP_MS && Number(r.at) <= now + 60000) st.results[h] = r;
          });
          if (v.meta && typeof v.meta === 'object') { st.meta = v.meta; st.meta.running = false; }
        }
      } catch (_) {}
      checkStores[f] = st;
    }
    return checkStores[f];
  }
  function saveCheck(f) {
    const d = checkData(f);
    const out = { meta: d.meta, results: {} };
    Object.keys(d.results)
      .sort(function (a, b) { return (Number(d.results[b].at) || 0) - (Number(d.results[a].at) || 0); })
      .slice(0, 40)
      .forEach(function (h) { out.results[h] = d.results[h]; });
    try { localStorage.setItem(checkKeyOf(f), JSON.stringify(out)); } catch (_) {}
  }
  // This film's page of the family (its servers are counted), or their front page.
  function checkPathNow(f) {
    if (f === 'mm') {
      // 322.3.123: the film's saved Moviesmod page (/movie/27205), on every Moviesmod name.
      const it = (film.items || []).find(function (x) { return isMmPage(x.pageUrl); });
      if (it) {
        try {
          const p = new URL(it.pageUrl).pathname.toLowerCase();
          if (MM_PATH.test(p)) return p;
        } catch (_) {}
      }
      return '/';
    }
    const fbox = filmFboxUrl();
    if (fbox && samePageFilm(fbox, filmTitle())) {
      try {
        const p = new URL(fbox).pathname.toLowerCase();
        if (/^\/watch\/(movie|tv)-[a-z0-9-]{1,120}$/.test(p)) return p;
      } catch (_) {}
    }
    return '/';
  }
  function nameBridge() {
    const b = window.SubHubAndroidBridge;
    return b && typeof b.nameCheckStart === 'function' ? b : null;
  }
  function startCheck(f, hosts) {
    if (!isOwner() || checkRun || !FAMS[f]) return;
    const b = nameBridge();
    if (!b) { notify('الفحص يحتاج تطبيق SubHub بنسخة 322.3.122 أو أحدث.'); return; }
    const list = (hosts || []).map(cleanHost).filter(Boolean)
      .filter(function (h, i, a) { return a.indexOf(h) === i; }).slice(0, 12);
    if (!list.length) return;
    const run = 'nc' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
    const path = checkPathNow(f);
    const d = checkData(f);
    d.meta = { at: Date.now(), path: path, film: path === '/' ? '' : filmTitle(), net: '',
      total: list.length, done: 0, running: true, stopped: false };
    checkRun = run; checkFam = f; checkQueue = list; checkDone = {}; checkCurrent = '';
    try {
      b.nameCheckStart(token, JSON.stringify({ run: run, names: list, path: path }));
    } catch (_) {
      checkRun = ''; checkFam = ''; checkQueue = [];
      notify('تعذّر بدء الفحص.');
    }
    paintCheck();
  }
  function stopCheck() {
    const b = window.SubHubAndroidBridge;
    try { if (b && typeof b.nameCheckStop === 'function') b.nameCheckStop(token); } catch (_) {}
    endCheck(true);
  }
  function endCheck(stopped) {
    const f = checkFam || 'fbox';
    const d = checkData(f);
    if (d.meta) { d.meta.running = false; d.meta.stopped = !!stopped; }
    checkRun = ''; checkFam = ''; checkQueue = []; checkDone = {}; checkCurrent = '';
    saveCheck(f);
    paintCheck();
  }
  function checkSummary() {
    const d = checkData(checkFam);
    let ok = 0, blocked = 0, dead = 0, moved = 0, other = 0;
    Object.keys(checkDone).forEach(function (h) {
      const r = d.results[h];
      const s = r ? r.state : '';
      if (s === 'ok') ok++;
      else if (s === 'blocked') blocked++;
      else if (s === 'redirect') moved++;
      else if (s === 'notFound' || s === 'down' || s === 'noAnswer' || s === 'notSite') dead++;
      else other++;
    });
    const parts = [];
    if (ok) parts.push(arNum(ok) + ' يعمل');
    if (blocked) parts.push(arNum(blocked) + ' محجوب عندك');
    if (moved) parts.push(arNum(moved) + ' انتقل');
    if (dead) parts.push(arNum(dead) + ' لا يعمل');
    if (other) parts.push(arNum(other) + ' يحتاج نظرة');
    return 'انتهى الفحص' + (parts.length ? ': ' + parts.join(' · ') : '');
  }
  window.__subhubNameCheckV3122 = function (raw) {
    let e = null;
    try { e = typeof raw === 'string' ? JSON.parse(raw) : raw; } catch (_) { return; }
    if (!e || !checkRun || e.run !== checkRun) return;
    const f = checkFam;
    const d = checkData(f);
    if (e.type === 'start') {
      if (d.meta) d.meta.net = String(e.net || '').slice(0, 8);
    } else if (e.type === 'name') {
      checkCurrent = cleanHost(e.host);
    } else if (e.type === 'result') {
      const h = cleanHost(e.host);
      if (!h) return;
      d.results[h] = {
        state: String(e.state || '').slice(0, 20), code: Number(e.code) || 0, err: Number(e.err) || 0,
        servers: Number(e.servers) || 0, links: Number(e.links) || 0, to: cleanHost(e.to),
        colo: String(e.colo || '').replace(/[^A-Za-z]/g, '').slice(0, 4), loc: String(e.loc || '').replace(/[^A-Za-z]/g, '').slice(0, 3),
        uk: !!e.uk, ipv6: !!e.ipv6, path: d.meta ? d.meta.path : '', net: d.meta ? d.meta.net : '', at: Date.now()
      };
      checkDone[h] = 1;
      if (d.meta) d.meta.done = Object.keys(checkDone).length;
      if (checkCurrent === h) checkCurrent = '';
      saveCheck(f);
    } else if (e.type === 'done') {
      const stopped = !!e.stopped;
      const summary = checkSummary();
      endCheck(stopped);
      if (!stopped) notify(summary, 'success');
      return;
    }
    paintCheck();
  };
  function agoText(at) {
    const s = Math.max(0, Math.round((Date.now() - Number(at || 0)) / 1000));
    if (s < 60) return 'الآن';
    const m = Math.round(s / 60);
    if (m < 60) return 'قبل ' + arNum(m) + ' د';
    const hh = Math.round(m / 60);
    if (hh < 24) return 'قبل ' + arNum(hh) + ' س';
    return 'قبل ' + arNum(Math.round(hh / 24)) + ' يوم';
  }
  function netText(n) {
    return n === 'wifi' ? 'واي فاي' : n === 'cell' ? 'بيانات الجوال' : n === 'vpn' ? 'VPN' : n === 'eth' ? 'سلكي' : '';
  }
  function checkWhere(m) {
    const page = !m || m.path === '/' ? 'على الصفحة الرئيسية' : (m.film ? 'على صفحة «' + m.film + '»' : 'على صفحة الفيلم');
    const net = netText(m && m.net);
    return page + (net ? ' · ' + net : '');
  }
  function runningHere(f) {
    return !!checkRun && checkFam === f;
  }
  function checkHead(f) {
    const m = checkData(f).meta;
    if (runningHere(f) && m) {
      return '🔄 جارٍ الفحص ' + arNum(Math.min((m.done || 0) + 1, m.total || 1)) + ' من ' + arNum(m.total || 1) + ' — ' + checkWhere(m);
    }
    if (m && m.at) return 'آخر فحص: ' + agoText(m.at) + ' — ' + checkWhere(m) + (m.stopped ? ' (أُوقف)' : '');
    return 'افحص الأسماء لتعرف أيّها يعمل على اتصالك الآن.';
  }
  function errText(err) {
    if (err === -2) return ' — الاسم غير معروف (DNS)';
    if (err === -6) return ' — رُفض الاتصال';
    if (err === -8) return ' — انتهت المهلة';
    if (err === -11) return ' — فشل الاتصال الآمن';
    if (!err) return ' — لم يصل رد خلال ٢٥ ثانية';
    return '';
  }
  // What one row says: {t: text, c: colour, ltr: Latin part, to: the other name}.
  function checkLine(f, h) {
    if (runningHere(f) && checkCurrent === h) return { t: '🔄 جارٍ الفحص…', c: '#7dd3fc' };
    if (runningHere(f) && checkQueue.indexOf(h) >= 0 && !checkDone[h]) return { t: '⏳ في الانتظار', c: '#94a3b8' };
    const r = checkData(f).results[h];
    if (!r) return null;
    const film = !!r.path && r.path !== '/';
    switch (r.state) {
      case 'ok':
        return { t: film ? '✅ يعمل — السيرفرات: ' + arNum(r.servers) : '✅ يعمل — الموقع يفتح', c: '#86efac' };
      case 'noServers':
        return { t: '⚠️ الموقع فتح، لكن بلا قائمة سيرفرات لهذا الفيلم', c: '#fbbf24' };
      case 'blocked':
        if (r.uk) return { t: '🚫 محجوب على اتصالك (451) — يمرّ عبر بريطانيا', c: '#fb923c', ltr: r.colo + (r.ipv6 ? ' · IPv6' : '') };
        return { t: '🚫 محجوب على اتصالك (451)' + (r.loc === 'GB' ? ' — اتصالك يظهر بريطانياً' : ''), c: '#fb923c' };
      case 'notFound':
        return { t: (film ? '⛔ صفحة الفيلم غير موجودة على هذا الاسم' : '⛔ غير موجود'), c: '#f87171', ltr: String(r.code) };
      case 'down':
        return { t: '⛔ خادم الموقع متوقف', c: '#f87171', ltr: String(r.code) };
      case 'noAnswer':
        return { t: '⛔ لا يرد' + errText(r.err), c: '#f87171' };
      case 'redirect':
        return { t: '↪️ انتقل إلى', c: '#93c5fd', to: r.to };
      case 'checkPage':
        return { t: '🛡️ صفحة تحقّق — افتحه بالإضافة للتأكد', c: '#fbbf24' };
      case 'notSite':
        return { t: '⚠️ فتح، لكنه ليس الموقع', c: '#f87171' };
      case 'httpError':
        return { t: '⚠️ خطأ', c: '#fbbf24', ltr: String(r.code) };
    }
    return null;
  }
  function anyBlocked(f) {
    const d = checkData(f);
    return famList(f).some(function (h) { return !!d.results[h] && d.results[h].state === 'blocked'; });
  }
  function paintRow(f, el, h) {
    while (el.firstChild) el.removeChild(el.firstChild);
    const L = checkLine(f, h);
    if (!L) { el.style.display = 'none'; return; }
    el.style.display = 'flex';
    const s = document.createElement('span');
    s.style.color = L.c;
    s.appendChild(document.createTextNode(L.t));
    if (L.ltr) { s.appendChild(document.createTextNode(' ')); s.appendChild(ltrText('(' + L.ltr + ')', '')); }
    const list = famList(f);
    if (L.to) {
      s.appendChild(document.createTextNode(' '));
      s.appendChild(ltrText(L.to, '#e0f2fe'));
      if (list.indexOf(L.to) >= 0) s.appendChild(document.createTextNode(' (موجود في القائمة)'));
    }
    el.appendChild(s);
    // 322.3.123: the moved name is replaced in place (the list never grows past 12).
    if (L.to && list.indexOf(h) >= 0 && L.to !== h) {
      const there = list.indexOf(L.to) >= 0;
      const rep = smallBtn(there ? '🗑️ احذفه' : '🔁 استبدله', there ? 'danger' : 'play', namesBusy || (there && list.length < 2),
        function () { replaceName(f, h, L.to); });
      rep.style.flex = 'none';
      rep.style.minHeight = '26px';
      rep.style.padding = '2px 10px';
      el.appendChild(rep);
    }
  }
  function paintAllBtn(b, f) {
    b.textContent = runningHere(f) ? '⏹ إيقاف الفحص' : '🩺 فحص الكل';
    b.style.borderColor = runningHere(f) ? '#7f1d1d' : '#166534';
    b.style.background = runningHere(f) ? '#2b1115' : '#10271a';
  }
  // Only the check's own parts change (no full redraw: the owner may be typing a name).
  function paintCheck() {
    const f = namesOpen;
    const box = f && FAMS[f] ? document.getElementById(FAMS[f].boxId) : null;
    if (!box) return;
    const head = box.querySelector('[data-ncheck-head]');
    if (head) head.textContent = checkHead(f);
    const all = box.querySelector('[data-ncheck-all]');
    if (all) paintAllBtn(all, f);
    [].slice.call(box.querySelectorAll('[data-ncheck-one]')).forEach(function (b) {
      b.disabled = !!checkRun;
      b.style.opacity = checkRun ? '.38' : '1';
    });
    [].slice.call(box.querySelectorAll('[data-ncheck-host]')).forEach(function (el) {
      paintRow(f, el, el.getAttribute('data-ncheck-host'));
    });
    const note = box.querySelector('[data-ncheck-note]');
    if (note) note.style.display = anyBlocked(f) ? '' : 'none';
  }
  async function saveEditedName(f, i) {
    const h = cleanHost(namesEditDraft);
    const bad = nameProblem(f, h, i);
    if (bad) { notify(bad); return; }
    const next = famList(f).slice();
    next[i] = h;
    if (await saveSiteNames(f, next, 'تم تعديل الاسم ✓')) { namesEditing = -1; namesEditDraft = ''; render(true); }
  }
  function chooseName(f, i) {
    const next = famList(f).slice();
    const h = next.splice(i, 1)[0];
    next.unshift(h);
    siteMark(f, h, false);   // 322.3.121: the owner says it works — no old failure kept for it
    saveSiteNames(f, next, 'صار «' + h + '» هو المختار ✓');
  }
  function moveName(f, i, dir) {
    const list = famList(f);
    const j = i + dir;
    if (i < 1 || j < 1 || j >= list.length) return;   // the chosen one moves by «اختيار» only
    const next = list.slice();
    const t = next[i]; next[i] = next[j]; next[j] = t;
    saveSiteNames(f, next, '');
  }
  function deleteName(f, i) {
    const list = famList(f);
    const h = list[i];
    if (list.length < 2) { notify('خلّ اسماً واحداً على الأقل في القائمة.'); return; }
    const after = i === 0 ? '\nويصير «' + list[1] + '» هو المختار.' : '';
    if (!confirm('حذف «' + h + '» من أسماء ' + FAMS[f].word + '؟' + after + '\nالسيرفرات المحفوظة ما تتأثر.')) return;
    const next = list.slice();
    next.splice(i, 1);
    if (namesEditing === i) namesEditing = -1;
    saveSiteNames(f, next, 'حُذف «' + h + '» ✓');
  }
  function namesBox(f) {
    const box = document.createElement('div');
    box.id = FAMS[f].boxId;
    box.setAttribute('data-names-box', f);
    box.style.cssText = 'border:1px solid #6b5520;border-radius:12px;background:#0b1a26;padding:8px;display:flex;flex-direction:column;gap:6px';
    const head = document.createElement('div');
    head.style.cssText = 'color:#fcd34d;font-weight:900;font-size:.76rem;line-height:1.6';
    head.textContent = FAMS[f].icon + ' أسماء ' + FAMS[f].word;
    const note = document.createElement('div');
    note.style.cssText = 'color:#a9b7cb;font-size:.64rem;line-height:1.7';
    note.textContent = 'الاسم المختار ✓ هو الذي يُفتح دائماً. وعند المشترك: إن لم تعمل صفحته ينتقل التطبيق إلى الاسم التالي بالترتيب. السيرفرات المحفوظة تعمل على كل الأسماء.';
    box.appendChild(head);
    box.appendChild(note);
    // 322.3.122: «🩺 فحص الكل» + where / when the last check ran.
    const tools = document.createElement('div');
    tools.style.cssText = 'display:flex;flex-direction:column;gap:5px;border:1px solid #1f3b4d;border-radius:10px;padding:6px 8px;background:#08161f';
    const allBtn = smallBtn('🩺 فحص الكل', 'play', namesBusy, function () {
      if (runningHere(f)) stopCheck();
      else if (checkRun) notify('فحص آخر يعمل الآن — انتظر حتى ينتهي.');
      else startCheck(f, famList(f).slice());
    });
    allBtn.setAttribute('data-ncheck-all', '1');
    paintAllBtn(allBtn, f);
    const checkInfo = document.createElement('div');
    checkInfo.setAttribute('data-ncheck-head', '1');
    checkInfo.style.cssText = 'color:#a9b7cb;font-size:.62rem;line-height:1.6';
    checkInfo.textContent = checkHead(f);
    const blockedNote = document.createElement('div');
    blockedNote.setAttribute('data-ncheck-note', '1');
    blockedNote.style.cssText = 'color:#fdba74;font-size:.6rem;line-height:1.7';
    blockedNote.textContent = '🚫 «محجوب على اتصالك» يخصّ اتصالك أنت الآن، وقد يعمل عند المشتركين — جرّبه على البيانات قبل أن تحذفه.';
    blockedNote.style.display = anyBlocked(f) ? '' : 'none';
    tools.appendChild(allBtn);
    tools.appendChild(checkInfo);
    tools.appendChild(blockedNote);
    box.appendChild(tools);
    const list = famList(f).slice();
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
        const ei = namesInput(namesEditDraft, h, function (v) { namesEditDraft = v; }, function () { saveEditedName(f, i); });
        top.appendChild(ei);
        row.appendChild(top);
        const acts = document.createElement('div');
        acts.style.cssText = 'display:grid;grid-template-columns:1fr 1fr;gap:5px';
        acts.appendChild(smallBtn('💾 حفظ', 'play', namesBusy, function () { saveEditedName(f, i); }));
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
      // 322.3.122: check this one name (a name just added is checked by itself).
      const one = smallBtn('🩺', 'edit', !!checkRun, function () { if (!checkRun) startCheck(f, [h]); });
      one.title = 'فحص هذا الاسم';
      one.setAttribute('data-ncheck-one', '1');
      one.style.flex = 'none';
      one.style.minHeight = '28px';
      one.style.padding = '2px 9px';
      top.appendChild(one);
      row.appendChild(top);
      const st = document.createElement('div');
      st.setAttribute('data-ncheck-host', h);
      st.style.cssText = 'align-items:center;gap:6px;flex-wrap:wrap;font-size:.62rem;line-height:1.6;font-weight:800';
      paintRow(f, st, h);
      row.appendChild(st);
      const acts = document.createElement('div');
      acts.style.cssText = 'display:grid;gap:5px;grid-template-columns:' + (chosen ? '1fr 1fr' : '1.35fr .75fr .75fr 1fr 1fr');
      if (!chosen) {
        acts.appendChild(smallBtn('✓ اختيار', 'play', namesBusy, function () { chooseName(f, i); }));
        acts.appendChild(smallBtn('⬆️', 'edit', namesBusy || i === 1, function () { moveName(f, i, -1); }));
        acts.appendChild(smallBtn('⬇️', 'edit', namesBusy || i === list.length - 1, function () { moveName(f, i, 1); }));
      }
      acts.appendChild(smallBtn('✏️ تعديل', 'edit', namesBusy, function () { namesEditing = i; namesEditDraft = h; render(true); }));
      acts.appendChild(smallBtn('🗑️ حذف', 'danger', namesBusy || list.length < 2, function () { deleteName(f, i); }));
      row.appendChild(acts);
      box.appendChild(row);
    });
    const addLabel = document.createElement('div');
    addLabel.style.cssText = 'color:#cbd5e1;font-size:.66rem;font-weight:800;margin-top:2px';
    addLabel.textContent = '➕ اسم جديد — اكتبه أو الصق رابطاً من الموقع';
    const addRow = document.createElement('div');
    addRow.style.cssText = 'display:grid;grid-template-columns:1fr auto;gap:6px;align-items:center';
    const ai = namesInput(namesDraft, FAMS[f].sample, function (v) { namesDraft = v; }, function () { addName(f); });
    const ab = smallBtn('➕ إضافة', 'play', namesBusy || list.length >= 12, function () { addName(f); });
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
    hint.textContent = !film.loaded
      ? 'جارٍ تحميل سيرفرات التطبيق…'
      : film.items.length > 1
        // 322.3.127: viewers get one automatic button for the list.
        ? 'تضغط «➕ إضافة سيرفر»، تجرّب السيرفر في الصفحة، ثم تحفظه. المشترك يرى «📱 مشاهدة بالتطبيق» يجرّب السيرفرات بالترتيب (اللي اشتغل معه قبل أولاً، ثم ترتيبك هنا)، والرقم على 👁 هو رقمه في «اختيار السيرفر» («مشاهدة بالتطبيق ٢»).'
        : 'تضغط «➕ إضافة سيرفر»، تجرّب السيرفر في الصفحة، ثم تحفظه. الرقم على 👁 هو رقمه عند المشترك («مشاهدة بالتطبيق ٢»).';
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
      // 322.3.123: the server's «⚙️» taps, when the owner set them
      const rt = ruleText(ruleFor(it.label));
      if (rt) meta.textContent = (meta.textContent ? meta.textContent + '  ' : '') + rt;
      top.appendChild(badge);
      top.appendChild(name);
      top.appendChild(meta);
      const acts = document.createElement('div');
      acts.style.cssText = 'display:grid;grid-template-columns:1.25fr 1fr .62fr .62fr .62fr .7fr;gap:5px';
      acts.appendChild(smallBtn('▶ تشغيل', 'play', false, function () { openSession(ctx, it.id, false, ''); }));
      acts.appendChild(smallBtn('✏️ تعديل', 'edit', false, function () { openSession(ctx, it.id, true, ''); }));
      // 322.3.123: the server's taps on play (all films)
      const gear = smallBtn('⚙️', 'edit', false, function () { ruleSheet(it.label); });
      gear.title = 'ضغطات التشغيل لهذا السيرفر';
      gear.setAttribute('data-rule-btn', it.id);
      acts.appendChild(gear);
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
    // 322.3.123: one line per family of names — «🎬 Moviesmod — المختار: …» and «📦 fbox —
    // المختار: …» — each with its own «✏️ الأسماء» box (one box open at a time).
    function namesButton(f) {
      const b = smallBtn(namesOpen === f ? '✕ إغلاق' : '✏️ الأسماء', 'edit', false, async function () {
        if (namesOpen === f) { namesOpen = ''; namesEditing = -1; render(true); return; }
        if (!isOwner() || typeof checkOwnerAccess !== 'function' || !(await checkOwnerAccess())) return;
        namesOpen = f; namesEditing = -1; namesDraft = ''; namesEditDraft = '';
        render(true);
        loadFboxDomains(true).then(function () { if (namesOpen === f) render(true); }).catch(function () {});
      });
      b.setAttribute('data-names-fam', f);
      b.style.flex = 'none';
      b.style.padding = '4px 10px';
      return b;
    }
    function chosenLine(f) {
      const line = document.createElement('span');
      const list = famList(f).length ? famList(f) : FAMS[f].defaults;
      line.appendChild(document.createTextNode(FAMS[f].icon + ' ' + FAMS[f].word + ' — المختار: '));
      line.appendChild(ltrText(list[0], '#cbd5e1'));
      if (list.length > 1) line.appendChild(document.createTextNode(' · ' + arNum(list.length - 1) + ' احتياط'));
      return line;
    }
    const mmDom = document.createElement('div');
    mmDom.style.cssText = 'display:flex;align-items:center;gap:6px;color:#7f93ab;font-size:.62rem;line-height:1.6';
    const mmTx = document.createElement('div');
    mmTx.style.cssText = 'flex:1;min-width:0;display:flex;flex-direction:column;gap:1px';
    mmTx.appendChild(chosenLine('mm'));
    mmDom.appendChild(mmTx);
    mmDom.appendChild(namesButton('mm'));
    sec.appendChild(mmDom);
    if (namesOpen === 'mm') sec.appendChild(namesBox('mm'));
    const dom = document.createElement('div');
    dom.style.cssText = 'display:flex;align-items:center;gap:6px;color:#7f93ab;font-size:.62rem;line-height:1.6';
    // 322.3.119: «📦 fbox — المختار: tinyzone.sx · ٦ احتياط · movie-…» + the names box.
    const domTx = document.createElement('div');
    domTx.style.cssText = 'flex:1;min-width:0;display:flex;flex-direction:column;gap:1px';
    domTx.appendChild(chosenLine('fbox'));
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
    dom.appendChild(domTx);
    dom.appendChild(namesButton('fbox'));
    sec.appendChild(dom);
    if (namesOpen === 'fbox') sec.appendChild(namesBox('fbox'));
    sec.appendChild(legacyBlock());   // moves the existing block (with its cards) here
    return sec;
  }

  // ------------------------------------------------------- 322.3.127 automatic
  // A film with two or more app servers: viewers get ONE button,
  // «📱 مشاهدة بالتطبيق», that tries them one after the other behind the black
  // screen (auto_chain.js + the app player's per-step limits: page 10 s, server
  // 12 s, link 20 s, start 15 s; a server known to be slow here gets longer), and
  // «🔀 اختيار السيرفر» that shows the numbered buttons (the owner's 👁 numbers).
  // Order: what each server did for this viewer on THIS film (worked → fastest
  // first; untried; worked once then failed; only failed), then the owner's order.
  // Results are kept by server name in the site's «shSrvStatsV401» (same as episodes).
  const STATS_KEY = 'shSrvStatsV401';
  let chooserOpen = false;
  let chooserFilm = '';
  function normName(n) { return String(n || '').replace(/\s+/g, ' ').trim().toLowerCase().slice(0, 60); }
  function statsAll() {
    try { return JSON.parse(localStorage.getItem(STATS_KEY) || '{}') || {}; }
    catch (_) { return {}; }
  }
  function recordStat(mid, name, ok, ms) {
    try {
      if (typeof window.__subhubServerStatV401 === 'function') { window.__subhubServerStatV401(mid, name, ok, ms); return; }
    } catch (_) {}
    try {
      const k = normName(name);
      if (!k) return;
      const a = statsAll(), m = a[String(mid)] || (a[String(mid)] = {});
      const r = m[k] || (m[k] = { ok: 0, fail: 0, ms: 0, okAt: 0, failAt: 0 });
      if (ok) {
        const t = Math.max(0, Math.min(120000, Number(ms) || 0));
        r.ms = r.ok ? Math.round((r.ms * r.ok + t) / (r.ok + 1)) : t;
        r.ok++; r.okAt = Date.now();
      } else { r.fail++; r.failAt = Date.now(); }
      m.__at = Date.now();
      const ids = Object.keys(a).sort(function (x, y) { return ((a[y] || {}).__at || 0) - ((a[x] || {}).__at || 0); });
      ids.slice(100).forEach(function (x) { delete a[x]; });
      localStorage.setItem(STATS_KEY, JSON.stringify(a));
    } catch (_) {}
  }
  function rankFilm(items, mid) {
    const st = statsAll()[String(mid)] || {};
    const rows = items.map(function (x, i) {
      const r = st[normName(x.label)] || null;
      let tier = 2;
      if (r && r.ok > 0 && r.okAt > (r.failAt || 0)) tier = 1;
      else if (r && r.ok > 0) tier = 3;
      else if (r && r.fail > 0) tier = 4;
      return { x: x, i: i, r: r, tier: tier };
    });
    rows.sort(function (a, b) {
      return a.tier - b.tier
        || ((a.tier === 1 || a.tier === 3) ? (a.r.ms - b.r.ms) : 0)
        || (a.tier === 4 ? (a.r.fail - b.r.fail) : 0)
        || a.i - b.i;
    });
    return rows;
  }
  function autoReady(items) {
    return items.length > 1 && typeof window.__subhubAutoChainV3110 === 'function';
  }
  function startAuto() {
    if (opening || active) return;   // a second tap while the first search opens a player
    const items = film.loaded ? film.items.slice() : [];
    if (!items.length || film.movieId !== currentId()) return;
    if (!autoReady(items)) { openSession({ movieId: film.movieId, kind: 'movie' }, items[0].id, false, ''); return; }
    const mid = film.movieId;
    const rows = rankFilm(items, mid);
    window.__subhubAutoChainV3110({
      movieId: mid,
      kind: 'movie',
      keys: rows.map(function (r) { return r.x.id; }),
      names: rows.map(function (r) { return r.x.label; }),
      ms: rows.map(function (r) { return (r.r && r.r.ok > 0 && r.r.ms) || 0; }),
      fallback: '',
      launch: function (key) { openSession({ movieId: mid, kind: 'movie' }, key, false, ''); },
      onResult: function (name, ok, ms) { recordStat(mid, name, ok, ms); }
    });
  }
  function autoButton() {
    const b = document.createElement('button');
    b.type = 'button';
    b.id = 'subhub-appauto-btn';
    b.className = 'watch-pill subhub-appauto-btn';
    b.style.cssText = 'border-color:' + ACCENT + ';background:rgba(34,211,238,.14);font-weight:800;cursor:pointer';
    b.textContent = '📱 مشاهدة بالتطبيق';
    b.title = 'يجرّب سيرفرات التطبيق بالترتيب حتى يشتغل واحد';
    b.addEventListener('click', startAuto);
    return b;
  }
  function chooserButton() {
    const b = document.createElement('button');
    b.type = 'button';
    b.id = 'subhub-appchoose-btn';
    b.className = 'watch-pill subhub-appchoose-btn';
    b.style.cssText = 'border-color:#3b5470;cursor:pointer';
    b.addEventListener('click', function () {
      chooserOpen = !chooserOpen;
      chooserFilm = film.movieId;
      render(true);
    });
    return b;
  }
  function syncAutoUi(grid, items) {
    if (chooserFilm !== film.movieId) { chooserOpen = false; chooserFilm = film.movieId; }
    const on = !!grid && autoReady(items);
    let a = document.getElementById('subhub-appauto-btn');
    let c = document.getElementById('subhub-appchoose-btn');
    if (!on) {
      if (a) a.remove();
      if (c) c.remove();
      showListButtons(true);
      return;
    }
    if (!a) a = autoButton();
    if (!c) c = chooserButton();
    const place = window.__subhubPlaceAppCardV3106;
    [[a, 0.05], [c, 0.06]].forEach(function (p) {
      if (p[0].parentElement === grid && p[0].getAttribute('data-app-order') === String(p[1])) return;
      if (typeof place === 'function') place(grid, p[0], 'list', p[1]); else grid.insertBefore(p[0], grid.firstChild);
    });
    const label = chooserOpen ? '🔀 اختيار السيرفر ▴' : '🔀 اختيار السيرفر ▾';
    if (c.textContent !== label) c.textContent = label;
    c.setAttribute('aria-expanded', chooserOpen ? 'true' : 'false');
    showListButtons(chooserOpen);
  }
  function showListButtons(show) {
    [].slice.call(document.querySelectorAll('.subhub-applist-btn[data-applist="app"]')).forEach(function (b) {
      const want = show ? '' : 'none';
      if (b.style.display !== want) b.style.display = want;
    });
  }
  function removeAutoUi() {
    ['subhub-appauto-btn', 'subhub-appchoose-btn'].forEach(function (id) {
      const b = document.getElementById(id);
      if (b) b.remove();
    });
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
      removeAutoUi();   // 322.3.127: viewers' buttons only
      const grid = document.querySelector('.owner-watch-pills');
      const existing = document.getElementById('subhub-applist-section');
      if (!grid) { if (existing) existing.remove(); lastSig = ''; return; }
      const sig = JSON.stringify([id, film.loaded, filmFboxUrl(), film.items.map(function (x) { return [x.id, x.label, x.subtitleOffsetMs, viewerNumber('list:' + x.id), ruleText(ruleFor(x.label))]; })]);
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
    if (!grid) { removeAutoUi(); return; }
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
    syncAutoUi(grid, want);   // 322.3.127
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
      namesOpen = ''; namesEditing = -1;   // 322.3.119: the names box closes on another film
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
