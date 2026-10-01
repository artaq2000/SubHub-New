(function () {
  'use strict';
  if (window.__subhubMoviesmodInstalledV3268) return;
  window.__subhubMoviesmodInstalledV3268 = true;

  const token = '__VIDSRC_GUARD_TOKEN__';
  let active = null;
  let opening = false;
  let pendingChoice = null;
  let loadSerial = 0;
  let loadPromise = null;
  let serverState = {
    movieId: '',
    loaded: false,
    loading: false,
    enabled: false,
    invalid: false,
    key: '',
    label: '',
    pageUrl: ''
  };

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

  function cleanServerLabel(raw) {
    return String(raw || '')
      .replace(/[⭐★☆]/g, '')
      .replace(/\s+/g, ' ')
      .trim()
      .slice(0, 80);
  }

  function validServerLabel(raw) {
    const v = cleanServerLabel(raw).toLowerCase();
    if (!v || v.length > 48) return false;
    return !/^(watch now|play|play now|home|movies|select server|trailer|download|settings)$/.test(v);
  }

  function normalizeServerPageUrl(raw) {
    const v = String(raw || '').trim();
    if (!v || v.length > 2200) return '';
    try {
      const u = new URL(v);
      const h = String(u.hostname || '').toLowerCase();
      if (u.protocol !== 'https:' || !(h === 'moviesmod.gd' || h.endsWith('.moviesmod.gd'))) return '';
      return u.href;
    } catch (_) {
      return '';
    }
  }

  function normalizeSaved(raw) {
    const d = raw || {};
    const label = cleanServerLabel(d.moviesmodServerLabel || '');
    const key = String(d.moviesmodServerKey || '').trim().slice(0, 80);
    const pageUrl = normalizeServerPageUrl(d.moviesmodServerPageUrl || '');
    const valid = d.moviesmodEnabled === true && validServerLabel(label);
    return {
      enabled: valid,
      invalid: d.moviesmodEnabled === true && !!label && !valid,
      key: valid ? key : '',
      label: valid ? label : '',
      pageUrl: pageUrl
    };
  }

  function fallbackSaved() {
    return normalizeSaved(movieDoc());
  }

  function savedServer() {
    const selected = current();
    const movieId = selected ? String(selected.id || '') : '';
    if (movieId && serverState.movieId === movieId && serverState.loaded) {
      return {
        enabled: serverState.enabled,
        invalid: serverState.invalid,
        key: serverState.key,
        label: serverState.label,
        pageUrl: serverState.pageUrl
      };
    }
    return fallbackSaved();
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

  function syncMovieDocCache(movieId, next) {
    try {
      const merged = Object.assign({}, movieDoc(), {
        moviesmodEnabled: !!next.enabled,
        moviesmodServerKey: next.key || '',
        moviesmodServerLabel: next.label || '',
        moviesmodServerPageUrl: next.pageUrl || '',
        moviesmodUpdatedAt: Date.now()
      });
      window._lastRenderedMovieDoc = merged;
      if (typeof _mdocPut === 'function') _mdocPut(String(movieId), merged);
    } catch (_) {}
  }

  function applyLoadedState(movieId, saved) {
    serverState = {
      movieId: String(movieId || ''),
      loaded: true,
      loading: false,
      enabled: !!saved.enabled,
      invalid: !!saved.invalid,
      key: String(saved.key || ''),
      label: String(saved.label || ''),
      pageUrl: normalizeServerPageUrl(saved.pageUrl || '')
    };
    syncMovieDocCache(movieId, saved);
  }

  async function ensureServerConfig(selected, force) {
    const movieId = String(selected && selected.id || '').trim();
    if (!movieId) return { enabled: false, key: '', label: '', pageUrl: '' };

    if (!force && serverState.movieId === movieId && serverState.loaded) {
      return savedServer();
    }
    if (!force && serverState.movieId === movieId && serverState.loading && loadPromise) {
      return loadPromise;
    }

    const serial = ++loadSerial;
    serverState = {
      movieId,
      loaded: false,
      loading: true,
      enabled: false,
      invalid: false,
      key: '',
      label: '',
      pageUrl: ''
    };

    loadPromise = (async function () {
      let saved = fallbackSaved();
      try {
        if (typeof db !== 'undefined' && db && db.collection) {
          const snap = await db.collection('subtitles').doc(movieId).get();
          if (snap && snap.exists) saved = normalizeSaved(snap.data() || {});
        }
      } catch (_) {
        // Keep the page cache as a fallback, but never erase a known saved server
        // merely because the network changed or Firestore was temporarily unavailable.
      }

      if (serial === loadSerial && current() && String(current().id || '') === movieId) {
        applyLoadedState(movieId, saved);
        refreshCard();
      }
      return saved;
    })();

    try { return await loadPromise; }
    finally {
      if (serial === loadSerial) loadPromise = null;
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

      const saved = await ensureServerConfig(selected, true);
      if (subscriber && (!saved.enabled || !saved.label || !saved.pageUrl)) {
        notify('هذا المصدر يحتاج إعادة اعتماد من المالك مرة واحدة.');
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
      // Owner setup is deliberately manual: no automatic Watch Now, server
      // selection, or retry clicking. Subscribers use the saved server page
      // and saved server identity automatically in a hidden capture WebView.
      const useSaved = subscriber && !forceManual
        && !!saved.enabled && !!saved.label && !!saved.pageUrl;
      const ownerPageUrl = owner && !forceManual ? (saved.pageUrl || '') : '';
      const startPageUrl = useSaved ? saved.pageUrl : ownerPageUrl;

      active = {
        session,
        movieId: String(selected.id || ''),
        catalog,
        owner,
        manualChoice: owner,
        usedSavedServer: useSaved,
        serverKey: useSaved ? saved.key : '',
        serverLabel: useSaved ? saved.label : '',
        serverPageUrl: startPageUrl
      };

      bridge.openDirectStream(token, JSON.stringify({
        mode: 'moviesmod',
        session,
        movieId: stableKey,
        resumeKey: stableKey,
        tmdbId: resolved.id,
        kind: resolved.kind,
        interactiveSource: owner,
        serverKey: useSaved ? saved.key : '',
        serverLabel: useSaved ? saved.label : '',
        serverPageUrl: startPageUrl,
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

  async function savePendingServer() {
    const selected = current();
    if (!selected || !isOwner() || !pendingChoice
        || pendingChoice.movieId !== String(selected.id || '')) {
      notify('اختر سيرفراً ناجحاً أولاً، ثم اضغط حفظ.');
      return;
    }

    try {
      if (typeof checkOwnerAccess !== 'function' || !(await checkOwnerAccess())) return;
      const movieId = String(selected.id || '');
      const next = {
        enabled: true,
        key: pendingChoice.key,
        label: pendingChoice.label,
        pageUrl: normalizeServerPageUrl(pendingChoice.pageUrl || '')
      };

      await db.collection('subtitles').doc(movieId).set({
        moviesmodEnabled: true,
        moviesmodServerKey: next.key,
        moviesmodServerLabel: next.label,
        moviesmodServerPageUrl: next.pageUrl,
        moviesmodUpdatedAt: Date.now()
      }, { merge: true });

      applyLoadedState(movieId, next);
      pendingChoice = null;
      refreshCard();
      notify('تم حفظ السيرفر للمشتركين: ' + next.label, 'success');
    } catch (_) {
      notify('تعذّر حفظ السيرفر. لم يتم تغيير الإعداد السابق.');
    }
  }

  function removeSavePrompt() {
    const old = document.getElementById('subhub-moviesmod-save-prompt');
    if (old) old.remove();
  }

  function showSavePrompt(choice) {
    if (!choice || !isOwner() || !validServerLabel(choice.label)) return;
    removeSavePrompt();

    const overlay = document.createElement('div');
    overlay.id = 'subhub-moviesmod-save-prompt';
    overlay.style.cssText =
      'position:fixed;inset:0;z-index:2147483000;background:rgba(0,0,0,.72);' +
      'display:flex;align-items:flex-end;justify-content:center;padding:18px;direction:rtl';

    const box = document.createElement('div');
    box.style.cssText =
      'width:min(520px,96vw);border:1px solid #4b5f7a;border-radius:18px;' +
      'background:#0b1625;color:#fff;padding:16px;box-shadow:0 16px 60px rgba(0,0,0,.55)';

    const title = document.createElement('div');
    title.style.cssText = 'font-weight:900;font-size:1rem;line-height:1.7;margin-bottom:6px';
    title.textContent = 'تم تشغيل السيرفر بنجاح: ' + choice.label;

    const msg = document.createElement('div');
    msg.style.cssText = 'color:#b8c5d6;font-size:.88rem;line-height:1.7;margin-bottom:14px';
    msg.textContent = 'هل تريد حفظ هذا السيرفر لهذا الفيلم؟ يمكنك مشاهدة النسخة أولاً، ثم الحفظ إذا كانت النسخة المناسبة.';

    const row = document.createElement('div');
    row.style.cssText = 'display:grid;grid-template-columns:1fr 1fr;gap:8px';

    const save = document.createElement('button');
    save.type = 'button';
    save.textContent = 'حفظ';
    save.style.cssText =
      'min-height:44px;border-radius:11px;border:1px solid #166534;background:#12301d;' +
      'color:#fff;font:inherit;font-weight:900';
    save.addEventListener('click', async function () {
      save.disabled = true;
      await savePendingServer();
      removeSavePrompt();
    });

    const later = document.createElement('button');
    later.type = 'button';
    later.textContent = 'ليس الآن';
    later.style.cssText =
      'min-height:44px;border-radius:11px;border:1px solid #41536b;background:#122033;' +
      'color:#fff;font:inherit;font-weight:900';
    later.addEventListener('click', function () {
      removeSavePrompt();
      refreshCard();
    });

    row.appendChild(save);
    row.appendChild(later);
    box.appendChild(title);
    box.appendChild(msg);
    box.appendChild(row);
    overlay.appendChild(box);
    overlay.addEventListener('click', function (ev) {
      if (ev.target === overlay) removeSavePrompt();
    });
    document.body.appendChild(overlay);
  }

  async function deleteSavedServer() {
    const selected = current();
    if (!selected || !isOwner()) return;

    try {
      if (typeof checkOwnerAccess !== 'function' || !(await checkOwnerAccess())) return;
      const movieId = String(selected.id || '');

      await db.collection('subtitles').doc(movieId).set({
        moviesmodEnabled: false,
        moviesmodServerKey: '',
        moviesmodServerLabel: '',
        moviesmodServerPageUrl: '',
        moviesmodUpdatedAt: Date.now()
      }, { merge: true });

      pendingChoice = null;
      removeSavePrompt();
      applyLoadedState(movieId, { enabled: false, key: '', label: '', pageUrl: '' });
      refreshCard();
      notify('تم حذف سيرفر Moviesmod لهذا الفيلم.', 'success');
    } catch (_) {
      notify('تعذّر حذف السيرفر.');
    }
  }

  window.__subhubMoviesmodServerSelected = function (session, rawKey, rawLabel, rawPageUrl) {
    const state = active;
    if (!state || state.session !== session) return;

    const key = String(rawKey || '').trim().slice(0, 80);
    const label = cleanServerLabel(rawLabel);
    if (!key || !label || !validServerLabel(label)) return;

    const pageUrl = normalizeServerPageUrl(rawPageUrl || state.serverPageUrl || '');
    state.serverKey = key;
    state.serverLabel = label;
    state.serverPageUrl = pageUrl;

    if (state.owner && state.manualChoice && current()
        && String(current().id || '') === state.movieId) {
      pendingChoice = {
        movieId: state.movieId,
        key,
        label,
        pageUrl
      };
      notify('السيرفر يعمل: ' + label + ' — اضغط حفظ لاعتماده.', 'success');
    }
  };

  const previousClosed = window.__subhubDirectClosed;
  window.__subhubDirectClosed = function (session) {
    const closing = active && active.session === session ? active : null;
    if (closing) active = null;
    refreshCard();
    if (closing && closing.owner && closing.manualChoice && pendingChoice
        && pendingChoice.movieId === closing.movieId) {
      setTimeout(function () { showSavePrompt(pendingChoice); }, 180);
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
      if (!Array.isArray(loaded)) throw new Error('segmented');
      cues = loaded
        .filter(c => Number.isFinite(c.start) && Number.isFinite(c.end) && c.end > c.start)
        .map(c => ({ start: c.start, end: c.end, text: String(c.text || '') }));
      if (!cues.length) throw new Error('empty');
    } catch (_) {
      error = 'تعذّر تحميل الترجمة. اختر ترجمة أخرى من CC.';
    }

    if (active !== state || !current() || String(current().id || '') !== state.movieId) return;
    window.SubHubAndroidBridge.directStreamSubtitles(
      token, session, index, JSON.stringify(cues), error
    );
  };

  function escapeHtmlLite(v) {
    return String(v || '').replace(/[&<>"]/g, function (ch) {
      return ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;' })[ch];
    });
  }

  function removeCard() {
    const ids = [
      'subhub-moviesmod-stream-card',
      'subhub-moviesmod-stream-button'
    ];
    ids.forEach(function (id) {
      const el = document.getElementById(id);
      if (el) el.remove();
    });
  }

  function refreshCard() {
    removeCard();
    install();
  }

  function ownerCard(selected, saved) {
    const movieId = String(selected.id || '');
    const pending = pendingChoice && pendingChoice.movieId === movieId ? pendingChoice : null;

    const card = document.createElement('div');
    card.id = 'subhub-moviesmod-stream-card';
    card.className = 'watch-pill owner-quick-pill';
    card.style.cssText =
      'min-height:104px;border:1px solid #7b68ee;border-radius:16px;' +
      'background:#171d36;color:#fff;padding:10px;display:flex;flex-direction:column;' +
      'gap:8px;align-items:stretch;justify-content:center;position:relative';

    const title = document.createElement('button');
    title.type = 'button';
    title.style.cssText =
      'border:0;background:transparent;color:#fff;font:inherit;font-weight:800;' +
      'cursor:pointer;line-height:1.5;padding:0 4px';
    title.innerHTML = '🎬 Moviesmod — تجريبي';

    const detail = document.createElement('small');
    detail.style.cssText = 'display:block;color:#a9b7cb;font-size:.62rem;font-weight:700;margin-top:2px';
    if (pending) {
      detail.innerHTML = 'تم اختباره: <b style="color:#7dd3fc">' + escapeHtmlLite(pending.label) +
        '</b><br>سيتم حفظ صفحة السيرفرات معه';
    } else if (saved.enabled && saved.label && saved.pageUrl) {
      detail.innerHTML = 'المحفوظ: <b style="color:#86efac">' + escapeHtmlLite(saved.label) +
        '</b><br>الدخول المباشر لصفحة السيرفرات';
    } else if (saved.enabled && saved.label) {
      detail.innerHTML = 'السيرفر محفوظ قديماً — اضغط تعديل ثم احفظه مرة واحدة';
    } else if (saved.invalid) {
      detail.textContent = 'الإعداد المحفوظ السابق غير صالح — عدّله أو احذفه';
    } else if (serverState.loading) {
      detail.textContent = 'جارٍ تحميل إعداد السيرفر…';
    } else {
      detail.textContent = 'لا يوجد سيرفر محفوظ';
    }
    title.appendChild(detail);
    title.addEventListener('click', function () {
      openMoviesmod(!(saved.enabled && saved.label && saved.pageUrl));
    });
    card.appendChild(title);

    const row = document.createElement('div');
    row.style.cssText = 'display:grid;grid-template-columns:1fr 1fr 1fr;gap:6px;width:100%';

    function control(label, kind, disabled, action) {
      const b = document.createElement('button');
      b.type = 'button';
      b.textContent = label;
      b.disabled = !!disabled;
      b.style.cssText =
        'min-height:34px;border-radius:9px;font:inherit;font-size:.64rem;font-weight:850;' +
        'cursor:pointer;padding:5px 3px;border:1px solid ' +
        (kind === 'danger' ? '#7f1d1d' : kind === 'save' ? '#166534' : '#36506b') + ';' +
        'background:' + (kind === 'danger' ? '#2b1115' : kind === 'save' ? '#10271a' : '#0c1d2d') + ';' +
        'color:#fff;opacity:' + (disabled ? '.42' : '1');
      b.addEventListener('click', function (ev) {
        ev.preventDefault();
        ev.stopPropagation();
        if (!b.disabled) action();
      });
      return b;
    }

    row.appendChild(control('حفظ', 'save', !pending, savePendingServer));
    row.appendChild(control('تعديل', 'edit', false, function () { openMoviesmod(true); }));
    row.appendChild(control('حذف', 'danger', !(saved.enabled && saved.label) && !saved.invalid, deleteSavedServer));
    card.appendChild(row);

    return card;
  }

  function subscriberButton() {
    const button = document.createElement('button');
    button.id = 'subhub-moviesmod-stream-card';
    button.type = 'button';
    button.className = 'watch-pill';
    button.style.cssText =
      'min-height:56px;border:1px solid #7b68ee;border-radius:12px;' +
      'background:#171d36;color:#fff;padding:10px;font:inherit;cursor:pointer';
    button.textContent = 'مشاهدة بالتطبيق — تجريبي';
    button.style.fontSize = '1.08rem';
    button.style.fontWeight = '850';
    button.style.lineHeight = '1.45';
    button.title = 'مشاهدة بالتطبيق — تجريبي';
    button.addEventListener('click', function () { openMoviesmod(false); });
    return button;
  }

  function install() {
    const selected = current();
    if (!selected) {
      removeCard();
      return;
    }

    const movieId = String(selected.id || '');
    if (serverState.movieId !== movieId || (!serverState.loaded && !serverState.loading)) {
      ensureServerConfig(selected, false).catch(function () {});
    }

    const owner = isOwner();
    const subscriber = isSubscriber();
    const saved = savedServer();

    if (owner) {
      if (document.getElementById('subhub-moviesmod-stream-card')) return;

      const grid = document.querySelector('.owner-watch-pills');
      if (!grid) return;

      const card = ownerCard(selected, saved);
      const m3u = document.getElementById('m3uOwnerTrialV360');
      if (m3u && m3u.parentElement === grid) grid.insertBefore(card, m3u);
      else grid.appendChild(card);
      return;
    }

    if (!subscriber || !serverState.loaded || !saved.enabled || !saved.label || !saved.pageUrl) {
      removeCard();
      return;
    }
    if (document.getElementById('subhub-moviesmod-stream-card')) return;

    const grid = document.querySelector('.watch-pills:not(.owner-watch-pills)');
    if (!grid) return;
    grid.appendChild(subscriberButton());
  }

  install();
  setInterval(install, 900);
})();