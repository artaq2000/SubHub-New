(function () {
  'use strict';
  // 322.3.83: Moviesmod (purple) and the yellow «مباشر تجريبي» card for SERIES
  // episodes. Films keep moviesmod_stream.js / vsm_stream.js untouched; this file
  // only serves the series page of SubHub Web v383, which calls
  // window.SubHubSeriesAppSourceV383({source, movieId, season, episode, ...}).
  // Each episode has its own saved server:
  //   subtitles/{id}.seriesAppSources['s1e2'].moviesmod | .vsm
  if (window.__subhubSeriesStreamV383) return;
  window.__subhubSeriesStreamV383 = true;

  const token = '__VIDSRC_GUARD_TOKEN__';
  const VSM_SERVERS_TEXT = 'vidsrc.mov / VidSrc.fyi';
  let active = null;
  let opening = false;
  let lastClosed = null;

  function current() {
    try { return typeof currentMovie !== 'undefined' ? currentMovie : null; }
    catch (_) { return null; }
  }
  function movieDoc() {
    try { return window._lastRenderedMovieDoc || {}; }
    catch (_) { return {}; }
  }
  function isOwner() {
    try { return typeof isLoggedIn !== 'undefined' && !!isLoggedIn; }
    catch (_) { return false; }
  }
  function isSubscriber() {
    if (isOwner()) return false;
    try { return typeof isSubscribed === 'function' && !!isSubscribed(); }
    catch (_) { return false; }
  }
  function notify(text, type) {
    try { if (typeof showToast === 'function') showToast(text, type || 'error'); } catch (_) {}
  }
  function report(session, ok, text) {
    try {
      const b = window.SubHubAndroidBridge;
      if (b && typeof b.directStreamSaveResult === 'function') b.directStreamSaveResult(token, session, !!ok, text);
    } catch (_) {}
  }
  function epKey(season, episode) {
    return 's' + Number(season) + 'e' + Number(episode);
  }
  function modeName(mode) {
    return mode === 'vsm' ? 'المباشر التجريبي' : 'Moviesmod';
  }

  function cleanServerLabel(raw) {
    return String(raw || '').replace(/[⭐★☆]/g, '').replace(/\s+/g, ' ').trim().slice(0, 80);
  }
  function validServerLabel(mode, raw) {
    const v = cleanServerLabel(raw).toLowerCase();
    if (!v || v.length > 48) return false;
    if (/^(watch now|play|play now|home|movies|select server|trailer|download|settings)$/.test(v)) return false;
    const compact = v.replace(/[^a-z0-9]/g, '');
    if (mode === 'vsm') return compact === 'vidsrcmov' || compact === 'vidsrcfyi';
    return compact.length >= 2;
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
  function normalizeSaved(mode, raw) {
    const d = raw || {};
    const label = cleanServerLabel(d.label);
    const valid = d.enabled === true && validServerLabel(mode, label);
    return {
      enabled: valid,
      key: valid ? String(d.key || '').trim().slice(0, 80) : '',
      label: valid ? label : '',
      pageUrl: normalizePageUrl(d.pageUrl),
      embedUrl: valid ? normalizeEmbedUrl(d.embedUrl) : '',
      subtitleOffsetMs: clampOffset(d.subtitleOffsetMs)
    };
  }
  function savedReady(mode, s) {
    return !!(s && s.enabled && s.label && (mode === 'vsm' ? s.embedUrl : s.pageUrl));
  }
  function savedFromDoc(mode, season, episode) {
    const map = movieDoc().seriesAppSources;
    const entry = map && typeof map === 'object' ? (map[epKey(season, episode)] || {}) : {};
    return normalizeSaved(mode, entry[mode]);
  }

  function safeYear(v) {
    const m = String(v || '').match(/(?:19|20)\d{2}/);
    return m ? m[0] : '';
  }
  async function resolveTmdbTv(info) {
    const sel = current() || {};
    const rawId = String(sel.id || info.movieId || '').trim();
    const stored = rawId.match(/^tmdb_tv_(\d{1,12})$/i);
    if (stored) return stored[1];
    if (typeof TMDB_API_KEY === 'undefined' || typeof TMDB_BASE === 'undefined'
        || !TMDB_API_KEY || String(TMDB_API_KEY).includes('ضع_مفتاحك')) {
      throw new Error('tmdb unavailable');
    }
    let imdb = '';
    try { if (typeof effectiveImdbIdV376 === 'function') imdb = effectiveImdbIdV376() || ''; } catch (_) {}
    if (!imdb && /^tt\d{5,12}$/i.test(rawId)) imdb = rawId;
    if (imdb) {
      const r = await fetch(`${TMDB_BASE}/find/${encodeURIComponent(imdb)}?api_key=${encodeURIComponent(TMDB_API_KEY)}&external_source=imdb_id&language=en-US`);
      const d = await r.json();
      const first = (d.tv_results || [])[0];
      if (first && first.id) return String(first.id);
    }
    const title = String(sel.title || '').trim();
    if (!title) throw new Error('no title');
    const year = safeYear(sel.year);
    const r = await fetch(`${TMDB_BASE}/search/tv?api_key=${encodeURIComponent(TMDB_API_KEY)}&language=en-US&query=${encodeURIComponent(title)}${year ? '&first_air_date_year=' + encodeURIComponent(year) : ''}`);
    const d = await r.json();
    const first = d && Array.isArray(d.results) ? d.results[0] : null;
    if (!first || !first.id) throw new Error('tmdb not found');
    return String(first.id);
  }
  function episodePageUrl(tmdbId, season, episode) {
    return 'https://moviesmod.gd/watch/tv/' + encodeURIComponent(tmdbId) + '/' + Number(season) + '/' + Number(episode);
  }

  // Only the episode's own subtitle (the film catalog does not apply to episodes).
  function episodeCatalog(season, episode) {
    try {
      const ep = (typeof _seriesEpisode === 'function' && typeof _seriesDoc === 'function')
        ? _seriesEpisode(_seriesDoc(), season, episode) : null;
      const url = String((ep && ep.subtitleUrl) || '').trim();
      if (!url) return [];
      const lang = String((ep && ep.subtitleLabel) || 'AR');
      return [{
        id: 'series::' + epKey(season, episode) + '::' + url,
        url: url,
        name: 'ترجمة الحلقة ' + Number(episode) + ' (' + lang + ')',
        lang: lang,
        kind: 'official',
        approved: true,
        isDefault: true,
        pinned: false,
        ref: 'series_' + epKey(season, episode),
        sign: true,
        key: '',
        cacheKey: 'official:' + url
      }];
    } catch (_) { return []; }
  }

  async function open(info, mode, forceManual, overridePageUrl) {
    if (opening || active) return;
    const selected = current();
    if (!selected || String(selected.id || '') !== String(info.movieId || '')) return;
    const season = Number(info.season), episode = Number(info.episode);
    if (!(season > 0) || !(episode > 0)) return;
    opening = true;
    try {
      const owner = isOwner();
      const subscriber = isSubscriber();
      if (!owner && !subscriber) { notify('هذا المصدر للمشتركين.', 'warn'); return; }
      if (owner) {
        if (typeof checkOwnerAccess !== 'function' || !(await checkOwnerAccess()) || current() !== selected) return;
      }
      const bridge = window.SubHubAndroidBridge;
      if (!bridge || typeof bridge.openDirectStream !== 'function') throw new Error('bridge unavailable');

      const saved = savedFromDoc(mode, season, episode);
      const complete = savedReady(mode, saved);
      if (subscriber && !complete) { notify('هذا المصدر غير محفوظ بعد لهذه الحلقة.'); return; }

      const tmdbId = await resolveTmdbTv(info);
      if (!/^\d{1,12}$/.test(tmdbId)) { notify('تعذّر تحديد صفحة المسلسل في Moviesmod.'); return; }
      if (current() !== selected) return;

      try { if (typeof setSubServerKeyV382 === 'function') setSubServerKeyV382('series_' + mode); } catch (_) {}
      const catalog = episodeCatalog(season, episode);
      if (typeof stopInlinePlayersV265 === 'function') stopInlinePlayersV265();
      if (typeof closeEmbedPlayer === 'function') closeEmbedPlayer();

      const useSaved = subscriber && complete;
      const ownerAuto = owner && !forceManual && complete;
      const auto = useSaved || ownerAuto;
      const manualPage = normalizePageUrl(overridePageUrl) || saved.pageUrl || episodePageUrl(tmdbId, season, episode);
      const startPage = auto ? saved.pageUrl : manualPage;
      const stableKey = (String(selected.id || 'series').replace(/[^A-Za-z0-9_.-]/g, '_').slice(0, 80) + '_' + epKey(season, episode)).slice(0, 100);
      const session = 'series_' + mode + '_' + Date.now() + '_' + Math.random().toString(36).slice(2);

      active = {
        session, mode, owner, catalog, season, episode,
        info: { source: mode, movieId: String(selected.id || ''), season, episode },
        movieId: String(selected.id || ''),
        serverKey: auto ? saved.key : '',
        serverLabel: auto ? saved.label : '',
        serverPageUrl: startPage,
        embedUrl: '',
        pending: null,
        subtitleOffsetMs: Number(saved.subtitleOffsetMs || 0)
      };

      bridge.openDirectStream(token, JSON.stringify({
        mode: mode,
        session: session,
        movieId: stableKey,
        resumeKey: stableKey,
        tmdbId: tmdbId,
        kind: 'tv',
        interactiveSource: owner && !ownerAuto,
        ownerMode: owner,
        openChooser: owner && !ownerAuto && !!startPage,
        serverKey: auto ? saved.key : '',
        serverLabel: auto ? saved.label : '',
        serverPageUrl: startPage,
        embedUrl: (mode === 'vsm' && auto) ? saved.embedUrl : '',
        subtitleOffsetMs: Number(saved.subtitleOffsetMs || 0),
        catalog: catalog.map(x => ({ name: String(x.name || 'ترجمة SubHub'), pinned: false })),
        defaultIndex: catalog.length ? 0 : -1
      }));
    } catch (_) {
      active = null;
      notify('تعذّر فتح ' + modeName(mode) + ' داخل المشغّل.');
    } finally {
      opening = false;
    }
  }

  function syncLocal(movieId, key, mode, value) {
    try {
      const doc = Object.assign({}, movieDoc());
      const map = Object.assign({}, doc.seriesAppSources || {});
      const entry = Object.assign({}, map[key] || {});
      if (value) entry[mode] = value; else delete entry[mode];
      map[key] = entry;
      doc.seriesAppSources = map;
      window._lastRenderedMovieDoc = doc;
      if (typeof _mdocPut === 'function') _mdocPut(movieId, doc);
    } catch (_) {}
    try {
      const c = current();
      if (c && String(c.id || '') === movieId && typeof refreshSeriesSelectionV265 === 'function') {
        setTimeout(function () { try { refreshSeriesSelectionV265(); } catch (_) {} }, 50);
      }
    } catch (_) {}
  }

  async function savePending(state, rawOffsetMs) {
    const choice = state && state.pending;
    if (!choice || !isOwner()) return { ok: false, text: 'لم يُعرف السيرفر بعد — اختر سيرفراً من القائمة' };
    try {
      if (typeof checkOwnerAccess !== 'function' || !(await checkOwnerAccess())) return { ok: false, text: 'تعذّر الحفظ' };
      const mode = state.mode;
      const prev = savedFromDoc(mode, state.season, state.episode);
      const next = {
        enabled: true,
        key: choice.key,
        label: choice.label,
        pageUrl: normalizePageUrl(choice.pageUrl),
        embedUrl: normalizeEmbedUrl(choice.embedUrl) || (prev.label === choice.label ? prev.embedUrl : ''),
        subtitleOffsetMs: clampOffset(Number.isFinite(Number(rawOffsetMs)) ? rawOffsetMs : choice.subtitleOffsetMs),
        updatedAt: Date.now()
      };
      if (mode === 'vsm' && !next.embedUrl) return { ok: false, text: 'لم يُلتقط رابط مشغّل السيرفر — جرّب «سيرفر آخر»' };
      if (mode !== 'vsm' && !next.pageUrl) return { ok: false, text: 'لم تُعرف صفحة السيرفر — جرّب مرة أخرى' };
      const key = epKey(state.season, state.episode);
      await db.collection('subtitles').doc(state.movieId).set({
        seriesAppSources: { [key]: { [mode]: next } }
      }, { merge: true });
      syncLocal(state.movieId, key, mode, next);
      state.pending = null;
      notify('تم حفظ السيرفر للحلقة ' + state.episode + ': ' + next.label, 'success');
      return { ok: true, text: 'تم الحفظ ✓ ' + next.label };
    } catch (_) {
      return { ok: false, text: 'تعذّر الحفظ' };
    }
  }

  async function deleteSaved(info, mode) {
    if (!isOwner()) return;
    try {
      if (typeof checkOwnerAccess !== 'function' || !(await checkOwnerAccess())) return;
      const key = epKey(info.season, info.episode);
      await db.collection('subtitles').doc(String(info.movieId)).update({
        ['seriesAppSources.' + key + '.' + mode]: firebase.firestore.FieldValue.delete()
      });
      syncLocal(String(info.movieId), key, mode, null);
      notify('تم حذف سيرفر ' + modeName(mode) + ' لهذه الحلقة.', 'success');
    } catch (_) {
      notify('تعذّر حذف السيرفر.');
    }
  }

  // Owner choices for an episode that already has a saved server.
  function ownerSheet(info, mode, saved) {
    const old = document.getElementById('subhub-series-stream-sheet');
    if (old) old.remove();
    const bg = document.createElement('div');
    bg.id = 'subhub-series-stream-sheet';
    bg.style.cssText = 'position:fixed;inset:0;z-index:2147483000;background:rgba(0,0,0,.72);display:flex;align-items:flex-end;justify-content:center;padding:18px;direction:rtl';
    const box = document.createElement('div');
    box.style.cssText = 'width:min(520px,96vw);border:1px solid ' + (mode === 'vsm' ? '#e8b544' : '#7b68ee') + ';border-radius:18px;background:#0b1625;color:#fff;padding:16px;box-shadow:0 16px 60px rgba(0,0,0,.55)';
    const title = document.createElement('div');
    title.style.cssText = 'font-weight:900;font-size:1rem;line-height:1.7;margin-bottom:4px';
    title.textContent = (mode === 'vsm' ? '▶ تجريبي — ' + VSM_SERVERS_TEXT : '🎬 Moviesmod') + ' · الموسم ' + info.season + ' الحلقة ' + info.episode;
    const msg = document.createElement('div');
    msg.style.cssText = 'color:#b8c5d6;font-size:.86rem;line-height:1.7;margin-bottom:12px';
    msg.textContent = 'السيرفر المحفوظ: ' + saved.label;
    const row = document.createElement('div');
    row.style.cssText = 'display:grid;grid-template-columns:1fr 1fr 1fr;gap:8px';
    function btn(text, css, fn) {
      const b = document.createElement('button');
      b.type = 'button';
      b.textContent = text;
      b.style.cssText = 'min-height:44px;border-radius:11px;font:inherit;font-weight:900;color:#fff;' + css;
      b.addEventListener('click', function () { bg.remove(); fn(); });
      return b;
    }
    row.appendChild(btn('▶ تشغيل', 'border:1px solid #166534;background:#12301d', function () { open(info, mode, false, ''); }));
    row.appendChild(btn('تعديل', 'border:1px solid #41536b;background:#122033', function () { open(info, mode, true, ''); }));
    row.appendChild(btn('حذف', 'border:1px solid #783b48;background:#24151d', function () {
      if (confirm('حذف السيرفر المحفوظ لهذه الحلقة؟')) deleteSaved(info, mode);
    }));
    box.appendChild(title);
    box.appendChild(msg);
    box.appendChild(row);
    bg.appendChild(box);
    bg.addEventListener('click', function (e) { if (e.target === bg) bg.remove(); });
    document.body.appendChild(bg);
  }

  // Entry point used by SubHub Web v383 (seriesPlayExtraV383).
  window.SubHubSeriesAppSourceV383 = function (raw) {
    const info = {
      source: raw && raw.source === 'vsm' ? 'vsm' : 'moviesmod',
      movieId: String((raw && raw.movieId) || ''),
      season: Number(raw && raw.season),
      episode: Number(raw && raw.episode)
    };
    const mode = info.source;
    if (isOwner()) {
      const saved = savedFromDoc(mode, info.season, info.episode);
      if (savedReady(mode, saved)) { ownerSheet(info, mode, saved); return; }
      open(info, mode, true, '');
      return;
    }
    open(info, mode, false, '');
  };

  // Native callbacks: handle our own sessions, delegate everything else to the
  // film handlers installed earlier by moviesmod_stream.js / vsm_stream.js.
  [['Moviesmod', 'moviesmod'], ['Vsm', 'vsm']].forEach(function (pair) {
    const prefix = '__subhub' + pair[0];
    const mode = pair[1];

    const prevSelected = window[prefix + 'ServerSelected'];
    window[prefix + 'ServerSelected'] = function (session, rawKey, rawLabel, rawPageUrl, rawEmbedUrl) {
      const state = active;
      if (!state || state.session !== session) {
        if (typeof prevSelected === 'function') return prevSelected.apply(this, arguments);
        return;
      }
      const key = String(rawKey || '').trim().slice(0, 80);
      const label = cleanServerLabel(rawLabel);
      if (!key || !label) return;
      if (!validServerLabel(mode, label)) {
        notify(mode === 'vsm' ? ('هذا الزر لـ ' + VSM_SERVERS_TEXT + ' فقط — اختر أحدهما.') : 'اسم السيرفر غير معروف — جرّب سيرفراً آخر.');
        return;
      }
      state.serverKey = key;
      state.serverLabel = label;
      state.serverPageUrl = normalizePageUrl(rawPageUrl || state.serverPageUrl || '');
      state.embedUrl = normalizeEmbedUrl(rawEmbedUrl || '');
      if (state.owner) {
        state.pending = {
          key: key,
          label: label,
          pageUrl: state.serverPageUrl,
          embedUrl: state.embedUrl,
          subtitleOffsetMs: Number(state.subtitleOffsetMs || 0)
        };
      }
    };

    const prevSave = window[prefix + 'SaveNow'];
    window[prefix + 'SaveNow'] = async function (session, rawOffsetMs) {
      const state = active;
      if (!state || state.session !== session) {
        if (typeof prevSave === 'function') return prevSave.apply(this, arguments);
        return false;
      }
      if (!state.owner) { report(session, false, 'تعذّر الحفظ'); return false; }
      const result = await savePending(state, rawOffsetMs);
      report(session, result.ok, result.text);
      return result.ok;
    };

    const prevPin = window[prefix + 'PinNow'];
    window[prefix + 'PinNow'] = async function (session) {
      const state = active;
      if (!state || state.session !== session) {
        if (typeof prevPin === 'function') return prevPin.apply(this, arguments);
        return false;
      }
      // Each episode has a single subtitle, so pinning does not apply here.
      report(session, false, 'ترجمة الحلقة هي الافتراضية تلقائياً');
      return false;
    };

    const prevReopen = window[prefix + 'ReopenManual'];
    window[prefix + 'ReopenManual'] = function (rawPageUrl) {
      const last = lastClosed;
      if (last && last.mode === mode && Date.now() - last.at < 5000) {
        lastClosed = null;
        if (!isOwner()) return;
        const page = normalizePageUrl(rawPageUrl || '');
        setTimeout(function () { open(last.info, mode, true, page); }, 120);
        return;
      }
      if (typeof prevReopen === 'function') return prevReopen.apply(this, arguments);
    };
  });

  const previousClosed = window.__subhubDirectClosed;
  window.__subhubDirectClosed = function (session) {
    if (active && active.session === session) {
      lastClosed = { info: active.info, mode: active.mode, at: Date.now() };
      active = null;
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
        // 322.3.92: exclusive/limited subtitles arrive as 4-second pieces.
        const pump = window.__subhubSegmentPumpV3292;
        if (loaded && loaded.dynamic && pump
            && pump.start(session, index, loaded.access, function () { return active === state; })) return;
        throw new Error('segmented');
      }
      cues = loaded
        .filter(c => Number.isFinite(c.start) && Number.isFinite(c.end) && c.end > c.start)
        .map(c => ({ start: c.start, end: c.end, text: String(c.text || '') }));
      if (!cues.length) throw new Error('empty');
    } catch (_) {
      error = 'تعذّر تحميل ترجمة الحلقة.';
    }
    if (active !== state) return;
    try { window.SubHubAndroidBridge.directStreamSubtitles(token, session, index, JSON.stringify(cues), error); } catch (_) {}
  };
})();
