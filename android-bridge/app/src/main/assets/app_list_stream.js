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
  function normalizePageUrl(raw) {
    const v = String(raw || '').trim();
    if (!v || v.length > 2200) return '';
    try {
      const u = new URL(v);
      const h = String(u.hostname || '').toLowerCase();
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
      if (u.protocol !== 'https:' || h === 'moviesmod.gd' || h.endsWith('.moviesmod.gd')) return '';
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
      const startPage = auto ? item.pageUrl : manualPage;
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
        session: session,
        movieId: stableKey,
        resumeKey: stableKey,
        tmdbId: tmdbId,
        kind: kind,
        interactiveSource: owner && !auto,
        ownerMode: owner,
        openChooser: owner && !auto && !!startPage,
        serverKey: auto ? item.key : '',
        serverLabel: auto ? item.label : '',
        serverPageUrl: startPage,
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
          pageUrl: p.pageUrl,
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
          id: id, label: p.label, key: p.key, pageUrl: p.pageUrl, embedUrl: p.embedUrl || '',
          subtitleOffsetMs: 0, subtitlePin: null, createdAt: now, updatedAt: now
        });
      }
      await writeItems(ctx, items);
      st.slotId = id;
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
      name.textContent = it.label;
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
    sec.appendChild(add);
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
      const sig = JSON.stringify([id, film.loaded, film.items.map(function (x) { return [x.id, x.label, x.subtitleOffsetMs, viewerNumber('list:' + x.id)]; })]);
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
