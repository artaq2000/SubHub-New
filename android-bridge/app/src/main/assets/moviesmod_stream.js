(function () {
  'use strict';
  if (window.__subhubMoviesmodInstalledV3262) return;
  window.__subhubMoviesmodInstalledV3262 = true;

  const token = '__VIDSRC_GUARD_TOKEN__';
  let active = null;
  let opening = false;

  function current() {
    return typeof currentMovie !== 'undefined' ? currentMovie : null;
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

  function savedServer() {
    const d = movieDoc();
    const key = String(d.moviesmodServerKey || '').trim();
    const label = String(d.moviesmodServerLabel || '').trim();
    const enabled = d.moviesmodEnabled === true;
    return { key, label, enabled };
  }

  function notify(text, type) {
    try {
      if (typeof showToast === 'function') showToast(text, type || 'error');
    } catch (_) {}
  }

  function safeYear(v) {
    const m = String(v || '').match(/(?:19|20)\d{2}/);
    return m ? m[0] : '';
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

  async function openMoviesmod(forceManual) {
    if (opening || active) return;
    opening = true;
    const selected = current();
    try {
      if (!selected || current() !== selected) return;

      const owner = isOwner();
      const subscriber = isSubscriber();
      if (!owner && !subscriber) return;
      if (owner) {
        if (typeof checkOwnerAccess !== 'function' || !(await checkOwnerAccess()) || current() !== selected) return;
      }

      const bridge = window.SubHubAndroidBridge;
      if (!bridge || typeof bridge.openDirectStream !== 'function') {
        throw new Error('bridge unavailable');
      }

      const saved = savedServer();
      if (subscriber && (!saved.enabled || !saved.label)) {
        notify('هذا المصدر لم يُعتمد بعد لهذا الفيلم.');
        return;
      }

      const resolved = await resolveTmdb(selected);
      if (!resolved || !/^\d{1,12}$/.test(resolved.id)) {
        notify('تعذّر تحديد صفحة الفيلم في Moviesmod.');
        return;
      }

      if (typeof _buildSubtitleTrackCatalog !== 'function') {
        throw new Error('catalog unavailable');
      }
      const catalog = _buildSubtitleTrackCatalog().slice();

      if (typeof stopInlinePlayersV265 === 'function') stopInlinePlayersV265();
      if (typeof closeEmbedPlayer === 'function') closeEmbedPlayer();

      const stableRaw = String(selected.id || ('tmdb_' + resolved.kind + '_' + resolved.id));
      const stableKey = stableRaw.replace(/[^A-Za-z0-9_.-]/g, '_').slice(0, 100)
        || ('tmdb_' + resolved.kind + '_' + resolved.id);
      const session = 'moviesmod_' + Date.now() + '_' + Math.random().toString(36).slice(2);
      const useSaved = !forceManual && !!saved.label;
      active = {
        session,
        movieId: selected.id,
        catalog,
        owner,
        usedSavedServer: useSaved,
        serverKey: useSaved ? saved.key : '',
        serverLabel: useSaved ? saved.label : ''
      };

      bridge.openDirectStream(token, JSON.stringify({
        mode: 'moviesmod',
        session: session,
        movieId: stableKey,
        resumeKey: stableKey,
        tmdbId: resolved.id,
        kind: resolved.kind,
        serverKey: useSaved ? saved.key : '',
        serverLabel: useSaved ? saved.label : '',
        catalog: catalog.map(x => ({ name: String(x.name || 'ترجمة SubHub') })),
        defaultIndex: catalog.findIndex(x => x.isDefault === true)
      }));
    } catch (_) {
      active = null;
      notify('تعذّر فتح Moviesmod داخل المشغّل.');
    } finally {
      opening = false;
    }
  }

  window.__subhubMoviesmodServerSelected = async function (session, rawKey, rawLabel) {
    const state = active;
    if (!state || state.session !== session) return;

    const key = String(rawKey || '').trim().slice(0, 80);
    const label = String(rawLabel || '').replace(/\s+/g, ' ').trim().slice(0, 80);
    if (!key || !label) return;
    state.serverKey = key;
    state.serverLabel = label;

    if (!state.owner || !isOwner() || !current() || current().id !== state.movieId) return;

    const before = savedServer();
    if (before.enabled && before.key === key && before.label === label) return;

    try {
      if (typeof checkOwnerAccess !== 'function' || !(await checkOwnerAccess())) return;
      if (!current() || current().id !== state.movieId) return;

      await db.collection('subtitles').doc(String(state.movieId)).set({
        moviesmodEnabled: true,
        moviesmodServerKey: key,
        moviesmodServerLabel: label,
        moviesmodUpdatedAt: Date.now()
      }, { merge: true });

      try {
        const merged = Object.assign({}, movieDoc(), {
          moviesmodEnabled: true,
          moviesmodServerKey: key,
          moviesmodServerLabel: label,
          moviesmodUpdatedAt: Date.now()
        });
        window._lastRenderedMovieDoc = merged;
        if (typeof _mdocPut === 'function') _mdocPut(String(state.movieId), merged);
      } catch (_) {}

      refreshButton();
      notify('تم اعتماد السيرفر: ' + label, 'success');
    } catch (_) {
      notify('تم تشغيل الفيديو، لكن تعذّر حفظ السيرفر للمشتركين.');
    }
  };

  const previousClosed = window.__subhubDirectClosed;
  window.__subhubDirectClosed = function (session) {
    if (active && active.session === session) active = null;
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
      if (!Array.isArray(loaded)) throw new Error('segmented');
      cues = loaded
        .filter(c => Number.isFinite(c.start) && Number.isFinite(c.end) && c.end > c.start)
        .map(c => ({ start: c.start, end: c.end, text: String(c.text || '') }));
      if (!cues.length) throw new Error('empty');
    } catch (_) {
      error = 'تعذّر تحميل الترجمة. اختر ترجمة أخرى من CC.';
    }

    if (active !== state || !current() || current().id !== state.movieId) return;
    window.SubHubAndroidBridge.directStreamSubtitles(
      token, session, index, JSON.stringify(cues), error
    );
  };

  function refreshButton() {
    const old = document.getElementById('subhub-moviesmod-stream-button');
    if (old) old.remove();
    install();
  }

  function install() {
    const old = document.getElementById('subhub-moviesmod-stream-button');
    const selected = current();
    if (!selected) {
      if (old) old.remove();
      return;
    }

    const owner = isOwner();
    const subscriber = isSubscriber();
    const saved = savedServer();
    const allowed = owner || (subscriber && saved.enabled && !!saved.label);
    if (!allowed) {
      if (old) old.remove();
      return;
    }
    if (old) return;

    const reference =
      document.getElementById('subhub-direct-stream-button') ||
      document.getElementById('vidsrcOwnerTrialV355');
    if (!reference || !reference.parentElement) return;

    const button = document.createElement('button');
    button.id = 'subhub-moviesmod-stream-button';
    button.type = 'button';
    button.className = reference.className;
    button.style.cssText =
      'min-height:68px;border:1px solid #7b68ee;border-radius:16px;' +
      'background:#171d36;color:#fff;padding:12px;font:inherit;cursor:pointer;position:relative';

    if (owner) {
      const detail = saved.label ? ('السيرفر: ' + saved.label) : 'اختر السيرفر واعتمده';
      button.innerHTML = '🎬 Moviesmod — تجريبي<small style="display:block;margin-top:4px;opacity:.8">'
        + detail.replace(/[&<>"]/g, function (ch) {
            return ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'})[ch];
          }) + '</small>';
      if (saved.label) {
        const edit = document.createElement('span');
        edit.textContent = '✎';
        edit.title = 'تغيير السيرفر المعتمد';
        edit.setAttribute('aria-label', 'تغيير السيرفر المعتمد');
        edit.style.cssText =
          'position:absolute;left:8px;top:8px;width:28px;height:28px;display:flex;' +
          'align-items:center;justify-content:center;border:1px solid #64748b;border-radius:9px;' +
          'background:#0b1625;color:#fff;z-index:2';
        edit.addEventListener('click', function (ev) {
          ev.preventDefault();
          ev.stopPropagation();
          openMoviesmod(true);
        });
        button.appendChild(edit);
      }
    } else {
      button.innerHTML = '🎬 مشاهدة Moviesmod<small style="display:block;margin-top:4px;opacity:.8">جاري جلب رابط جديد عند كل تشغيل</small>';
    }

    button.title = owner
      ? 'ضغطة عادية تستخدم السيرفر المعتمد، والقلم يتيح اختيار سيرفر آخر'
      : 'يستخدم السيرفر الذي اعتمده المالك ويجلب بثاً جديداً لهذا الجهاز';
    button.addEventListener('click', function () { openMoviesmod(false); });
    reference.insertAdjacentElement('afterend', button);
  }

  install();
  setInterval(install, 1000);
})();