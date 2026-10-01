(function () {
  'use strict';
  if (window.__subhubVsmInstalledV3279) return;
  window.__subhubVsmInstalledV3279 = true;
  // 322.3.79: this yellow card replaces the old «مشاهدة مباشرة — تجريبي»
  // (direct_stream.js) and serves only vidsrc.mov / VidSrc.fyi. The owner picks
  // the server on Moviesmod like the purple card; playback then opens the saved
  // server player URL directly, skipping the Moviesmod server list.
  window.__subhubVsmReplacesDirect = true;

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
    pageUrl: '',
    embedUrl: '',
    subtitleOffsetMs: 0
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

  // 322.3.79: this card is reserved for the two servers that do not work
  // through the purple card's automatic server click.
  const VSM_SERVERS_TEXT = 'vidsrc.mov / VidSrc.fyi';
  function supportedServerLabel(raw) {
    const compact = cleanServerLabel(raw).toLowerCase().replace(/[^a-z0-9]/g, '');
    return compact === 'vidsrcmov' || compact === 'vidsrcfyi';
  }

  function validServerLabel(raw) {
    const v = cleanServerLabel(raw).toLowerCase();
    if (!v || v.length > 48) return false;
    if (/^(watch now|play|play now|home|movies|select server|trailer|download|settings)$/.test(v)) return false;
    return supportedServerLabel(v);
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

  // 322.3.78: the server's own player URL (not the temporary m3u8). The
  // website plays it for everyone; it must not point back to Moviesmod.
  function normalizeEmbedUrl(raw) {
    const v = String(raw || '').trim();
    if (!v || v.length > 2200) return '';
    try {
      const u = new URL(v);
      const h = String(u.hostname || '').toLowerCase();
      if (u.protocol !== 'https:' || h === 'moviesmod.gd' || h.endsWith('.moviesmod.gd')) return '';
      return u.href;
    } catch (_) {
      return '';
    }
  }

  function normalizeSaved(raw) {
    const d = raw || {};
    const label = cleanServerLabel(d.vsmServerLabel || '');
    const key = String(d.vsmServerKey || '').trim().slice(0, 80);
    const pageUrl = normalizeServerPageUrl(d.vsmServerPageUrl || '');
    const valid = d.vsmEnabled === true && validServerLabel(label);
    const rawOffset = Number(d.vsmSubtitleOffsetMs || 0);
    const subtitleOffsetMs = Number.isFinite(rawOffset)
      ? Math.max(-600000, Math.min(600000, Math.round(rawOffset)))
      : 0;
    return {
      enabled: valid,
      invalid: d.vsmEnabled === true && !!label && !valid,
      key: valid ? key : '',
      label: valid ? label : '',
      pageUrl: pageUrl,
      embedUrl: valid ? normalizeEmbedUrl(d.vsmEmbedUrl || '') : '',
      subtitleOffsetMs
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
        pageUrl: serverState.pageUrl,
        embedUrl: serverState.embedUrl,
        subtitleOffsetMs: serverState.subtitleOffsetMs
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
        vsmEnabled: !!next.enabled,
        vsmServerKey: next.key || '',
        vsmServerLabel: next.label || '',
        vsmServerPageUrl: next.pageUrl || '',
        vsmEmbedUrl: next.embedUrl || '',
        vsmSubtitleOffsetMs: Number(next.subtitleOffsetMs || 0),
        vsmUpdatedAt: Date.now()
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
      pageUrl: normalizeServerPageUrl(saved.pageUrl || ''),
      embedUrl: normalizeEmbedUrl(saved.embedUrl || ''),
      subtitleOffsetMs: Number.isFinite(Number(saved.subtitleOffsetMs))
        ? Math.max(-600000, Math.min(600000, Math.round(Number(saved.subtitleOffsetMs))))
        : 0
    };
    syncMovieDocCache(movieId, saved);
  }

  async function ensureServerConfig(selected, force) {
    const movieId = String(selected && selected.id || '').trim();
    if (!movieId) return { enabled: false, key: '', label: '', pageUrl: '', embedUrl: '', subtitleOffsetMs: 0 };

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
      pageUrl: '',
      embedUrl: '',
      subtitleOffsetMs: 0
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

  async function openVsm(forceManual, overridePageUrl) {
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
      if (subscriber && (!saved.enabled || !saved.label || !saved.embedUrl)) {
        notify('هذا المصدر غير محفوظ بعد لهذا الفيلم.');
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
      // 322.3.82: the subtitle pinned for this server (if any) becomes the default.
      try { if (typeof setSubServerKeyV382 === 'function') setSubServerKeyV382('vsm'); } catch (_) {}
      const catalog = _buildSubtitleTrackCatalog().slice();

      if (typeof stopInlinePlayersV265 === 'function') stopInlinePlayersV265();
      if (typeof closeEmbedPlayer === 'function') closeEmbedPlayer();

      const stableRaw = String(selected.id || ('tmdb_' + resolved.kind + '_' + resolved.id));
      const stableKey = stableRaw.replace(/[^A-Za-z0-9_.-]/g, '_').slice(0, 100)
        || ('tmdb_' + resolved.kind + '_' + resolved.id);
      const session = 'vsm_' + Date.now() + '_' + Math.random().toString(36).slice(2);
      // Owner setup is deliberately manual: no automatic Watch Now, server
      // selection, or retry clicking. Subscribers use the saved server page
      // and saved server identity automatically in a hidden capture WebView.
      // 322.3.79: playback needs the saved server player URL (embedUrl); the
      // Moviesmod page URL is only kept as the Referer for that player.
      const savedComplete = !!saved.enabled && !!saved.label && !!saved.embedUrl;
      const useSaved = subscriber && !forceManual
        && savedComplete;
      // 322.3.77: after the owner saves, tapping the card plays the saved
      // server automatically (the same path subscribers use) so the owner can
      // test it. «تعديل» / «سيرفر آخر» go back to the manual server list.
      const ownerAuto = owner && !forceManual && savedComplete;
      const manualPageUrl = normalizeServerPageUrl(overridePageUrl || '') || saved.pageUrl || '';
      const ownerPageUrl = owner ? (ownerAuto ? saved.pageUrl : manualPageUrl) : '';
      const startPageUrl = (useSaved || ownerAuto) ? saved.pageUrl : ownerPageUrl;
      const autoKey = (useSaved || ownerAuto) ? saved.key : '';
      const autoLabel = (useSaved || ownerAuto) ? saved.label : '';

      active = {
        session,
        movieId: String(selected.id || ''),
        catalog,
        owner,
        manualChoice: owner,
        ownerAuto,
        usedSavedServer: useSaved || ownerAuto,
        serverKey: autoKey,
        serverLabel: autoLabel,
        serverPageUrl: startPageUrl,
        subtitleOffsetMs: Number(saved.subtitleOffsetMs || 0)
      };

      const explicitDefaultIndex = catalog.findIndex(x => x.isDefault === true);
      const startupSubtitleIndex = explicitDefaultIndex >= 0
        ? explicitDefaultIndex
        : (catalog.length ? 0 : -1);

      bridge.openDirectStream(token, JSON.stringify({
        mode: 'vsm',
        session,
        movieId: stableKey,
        resumeKey: stableKey,
        tmdbId: resolved.id,
        kind: resolved.kind,
        interactiveSource: owner && !ownerAuto,
        ownerMode: owner,
        openChooser: owner && !ownerAuto && !!startPageUrl,
        serverKey: useSaved ? saved.key : autoKey,
        serverLabel: useSaved ? saved.label : autoLabel,
        serverPageUrl: startPageUrl,
        embedUrl: (useSaved || ownerAuto) ? saved.embedUrl : '',
        subtitleOffsetMs: Number(saved.subtitleOffsetMs || 0),
        catalog: catalog.map(x => ({ name: String(x.name || 'ترجمة SubHub'), pinned: x.pinned === true })),
        defaultIndex: startupSubtitleIndex
      }));
    } catch (_) {
      active = null;
      notify('تعذّر فتح المشاهدة المباشرة داخل المشغّل.');
    } finally {
      opening = false;
    }
  }

  async function savePendingServer(rawOffsetMs) {
    const selected = current();
    if (!selected || !isOwner() || !pendingChoice
        || pendingChoice.movieId !== String(selected.id || '')) {
      notify('اختر سيرفراً ناجحاً أولاً، ثم اضغط حفظ.');
      return;
    }

    try {
      if (typeof checkOwnerAccess !== 'function' || !(await checkOwnerAccess())) return;
      const movieId = String(selected.id || '');
      const requestedOffset = Number(rawOffsetMs);
      const fallbackOffset = Number(pendingChoice.subtitleOffsetMs || 0);
      const subtitleOffsetMs = Number.isFinite(requestedOffset)
        ? Math.max(-600000, Math.min(600000, Math.round(requestedOffset)))
        : (Number.isFinite(fallbackOffset)
          ? Math.max(-600000, Math.min(600000, Math.round(fallbackOffset)))
          : 0);
      // Keep the previously saved player URL when the same server is saved
      // again but this session could not see its URL.
      const previous = savedServer();
      const freshEmbed = normalizeEmbedUrl(pendingChoice.embedUrl || '');
      const next = {
        enabled: true,
        key: pendingChoice.key,
        label: pendingChoice.label,
        pageUrl: normalizeServerPageUrl(pendingChoice.pageUrl || ''),
        embedUrl: freshEmbed || (previous.label === pendingChoice.label ? (previous.embedUrl || '') : ''),
        subtitleOffsetMs
      };
      // Without the server player URL this card has nothing to play later.
      if (!next.embedUrl) {
        notify('لم يُلتقط رابط مشغّل السيرفر — اضغط «سيرفر آخر» وجرّب مرة أخرى.');
        return false;
      }

      await db.collection('subtitles').doc(movieId).set({
        vsmEnabled: true,
        vsmServerKey: next.key,
        vsmServerLabel: next.label,
        vsmServerPageUrl: next.pageUrl,
        vsmEmbedUrl: next.embedUrl,
        vsmSubtitleOffsetMs: next.subtitleOffsetMs,
        vsmUpdatedAt: Date.now()
      }, { merge: true });

      applyLoadedState(movieId, next);
      pendingChoice = null;
      refreshCard();
      notify('تم حفظ السيرفر للمشتركين: ' + next.label, 'success');
      return true;
    } catch (_) {
      notify('تعذّر حفظ السيرفر. لم يتم تغيير الإعداد السابق.');
      return false;
    }
  }

  function removeSavePrompt() {
    const old = document.getElementById('subhub-vsm-save-prompt');
    if (old) old.remove();
  }

  function showSavePrompt(choice) {
    if (!choice || !isOwner() || !validServerLabel(choice.label)) return;
    removeSavePrompt();

    const overlay = document.createElement('div');
    overlay.id = 'subhub-vsm-save-prompt';
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
        vsmEnabled: false,
        vsmServerKey: '',
        vsmServerLabel: '',
        vsmServerPageUrl: '',
        vsmEmbedUrl: '',
        vsmSubtitleOffsetMs: 0,
        vsmUpdatedAt: Date.now()
      }, { merge: true });

      pendingChoice = null;
      removeSavePrompt();
      applyLoadedState(movieId, { enabled: false, key: '', label: '', pageUrl: '', embedUrl: '', subtitleOffsetMs: 0 });
      refreshCard();
      notify('تم حذف سيرفر الزر الأصفر لهذا الفيلم.', 'success');
    } catch (_) {
      notify('تعذّر حذف السيرفر.');
    }
  }

  window.__subhubVsmServerSelected = function (session, rawKey, rawLabel, rawPageUrl, rawEmbedUrl) {
    const state = active;
    if (!state || state.session !== session) return;

    const key = String(rawKey || '').trim().slice(0, 80);
    const label = cleanServerLabel(rawLabel);
    if (!key || !label) return;
    if (!validServerLabel(label)) {
      notify('هذا الزر لـ ' + VSM_SERVERS_TEXT + ' فقط — اختر أحدهما.');
      return;
    }

    const pageUrl = normalizeServerPageUrl(rawPageUrl || state.serverPageUrl || '');
    state.serverKey = key;
    state.serverLabel = label;
    state.serverPageUrl = pageUrl;
    state.embedUrl = normalizeEmbedUrl(rawEmbedUrl || '');

    if (state.owner && current()
        && String(current().id || '') === state.movieId) {
      pendingChoice = {
        movieId: state.movieId,
        key,
        label,
        pageUrl,
        embedUrl: state.embedUrl,
        subtitleOffsetMs: Number(state.subtitleOffsetMs || 0)
      };
    }
  };

  window.__subhubVsmSaveNow = async function (session, rawOffsetMs) {
    const state = active;
    const bridge = window.SubHubAndroidBridge;
    if (!state || state.session !== session || !state.owner
        || !pendingChoice || pendingChoice.movieId !== state.movieId) {
      if (bridge && typeof bridge.directStreamSaveResult === 'function') {
        bridge.directStreamSaveResult(token, session, false, 'لم يُعرف السيرفر بعد — اختر سيرفراً من القائمة');
      }
      return false;
    }

    const offset = Number(rawOffsetMs);
    if (Number.isFinite(offset)) {
      pendingChoice.subtitleOffsetMs = Math.max(-600000, Math.min(600000, Math.round(offset)));
      state.subtitleOffsetMs = pendingChoice.subtitleOffsetMs;
    }
    const ok = await savePendingServer(pendingChoice.subtitleOffsetMs);
    if (bridge && typeof bridge.directStreamSaveResult === 'function') {
      bridge.directStreamSaveResult(
        token,
        session,
        !!ok,
        ok ? 'تم الحفظ ✓ ' + (state.serverLabel || '') : 'تعذّر الحفظ'
      );
    }
    return !!ok;
  };

  // 322.3.82: owner pins the selected subtitle to this server (toggle).
  window.__subhubVsmPinNow = async function (session, index) {
    const state = active;
    const bridge = window.SubHubAndroidBridge;
    function report(ok, text) {
      if (bridge && typeof bridge.directStreamSaveResult === 'function') {
        bridge.directStreamSaveResult(token, session, !!ok, text);
      }
    }
    try {
      if (!state || state.session !== session || !state.owner
          || !Number.isInteger(index) || !state.catalog[index]) {
        report(false, 'تعذّر التثبيت');
        return false;
      }
      if (typeof pinSubtitleForServerV382 !== 'function') {
        report(false, 'حدّث الموقع أولاً لتفعيل التثبيت');
        return false;
      }
      const result = await pinSubtitleForServerV382(state.catalog[index], 'vsm');
      report(!!(result && result.ok), (result && result.message) || 'تعذّر التثبيت');
      if (result && result.ok && bridge && typeof bridge.directStreamPinned === 'function') {
        bridge.directStreamPinned(token, session, index, !!result.pinned);
      }
      return !!(result && result.ok);
    } catch (_) {
      report(false, 'تعذّر التثبيت');
      return false;
    }
  };

  // Called by the native player after it closes itself when the owner taps
  // «سيرفر آخر»: reopen the manual server list on the same server page.
  window.__subhubVsmReopenManual = function (rawPageUrl) {
    if (!isOwner()) return;
    const pageUrl = normalizeServerPageUrl(rawPageUrl || '');
    setTimeout(function () { openVsm(true, pageUrl); }, 120);
  };

  const previousClosed = window.__subhubDirectClosed;
  window.__subhubDirectClosed = function (session) {
    const closing = active && active.session === session ? active : null;
    if (closing) active = null;
    refreshCard();
    // 322.3.76: do not interrupt the owner with a save prompt on exit.
    // Saving is explicit from the player while the video and subtitle are visible.
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
      'subhub-vsm-stream-card',
      'subhub-vsm-stream-button'
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

  function savedReady(saved) {
    return !!(saved.enabled && saved.label && saved.embedUrl);
  }

  function ownerCard(selected, saved) {
    const movieId = String(selected.id || '');
    const pending = pendingChoice && pendingChoice.movieId === movieId ? pendingChoice : null;

    const card = document.createElement('div');
    card.id = 'subhub-vsm-stream-card';
    card.className = 'watch-pill owner-quick-pill';
    card.style.cssText =
      'min-height:104px;border:1px solid #e8b544;border-radius:16px;' +
      'background:#132536;color:#fff;padding:10px;display:flex;flex-direction:column;' +
      'gap:8px;align-items:stretch;justify-content:center;position:relative';

    const title = document.createElement('button');
    title.type = 'button';
    title.style.cssText =
      'border:0;background:transparent;color:#fff;font:inherit;font-weight:800;' +
      'cursor:pointer;line-height:1.5;padding:0 4px';
    title.textContent = '▶ تجريبي — ' + VSM_SERVERS_TEXT;

    const detail = document.createElement('small');
    detail.style.cssText = 'display:block;color:#a9b7cb;font-size:.62rem;font-weight:700;margin-top:2px';
    if (pending) {
      detail.innerHTML = 'تم اختباره: <b style="color:#7dd3fc">' + escapeHtmlLite(pending.label) +
        '</b><br>اضغط «حفظ» ليُحفظ للمشتركين';
    } else if (savedReady(saved)) {
      detail.innerHTML = 'المحفوظ: <b style="color:#86efac">' + escapeHtmlLite(saved.label) +
        '</b><br>اضغط للتجربة — «تعديل» لاختيار سيرفر آخر';
    } else if (saved.invalid) {
      detail.textContent = 'الإعداد المحفوظ السابق غير صالح — عدّله أو احذفه';
    } else if (serverState.loading) {
      detail.textContent = 'جارٍ تحميل إعداد السيرفر…';
    } else {
      detail.textContent = 'لا يوجد سيرفر محفوظ — اضغط «تعديل»';
    }
    title.appendChild(detail);
    title.addEventListener('click', function () {
      openVsm(!savedReady(saved));
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
    row.appendChild(control('تعديل', 'edit', false, function () { openVsm(true); }));
    row.appendChild(control('حذف', 'danger', !(saved.enabled && saved.label) && !saved.invalid, deleteSavedServer));
    card.appendChild(row);

    return card;
  }

  function subscriberButton() {
    const button = document.createElement('button');
    button.id = 'subhub-vsm-stream-card';
    button.type = 'button';
    button.className = 'watch-pill';
    // 322.3.81: same size as the site's small watch pills; only the gold border stays.
    button.style.cssText = 'border-color:#e8b544;cursor:pointer';
    // 322.3.82: the site numbers app-type buttons (مشاهدة بالتطبيق ١ / ٢ ...).
    button.textContent = '▶ مشاهدة بالتطبيق';
    button.title = 'مشاهدة بالتطبيق';
    button.addEventListener('click', function () { openVsm(false); });
    return button;
  }

  function removeOldDirectButton() {
    const old = document.getElementById('subhub-direct-stream-button');
    if (old) old.remove();
  }

  function install() {
    removeOldDirectButton();
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
      if (document.getElementById('subhub-vsm-stream-card')) return;

      const grid = document.querySelector('.owner-watch-pills');
      if (!grid) return;

      const card = ownerCard(selected, saved);
      // Same place the old yellow button used: right after the VidSrc card.
      const anchor = document.getElementById('vidsrcOwnerTrialV355');
      if (anchor && anchor.parentElement === grid) anchor.insertAdjacentElement('afterend', card);
      else grid.appendChild(card);
      return;
    }

    if (!subscriber || !serverState.loaded || !savedReady(saved)) {
      removeCard();
      return;
    }
    if (document.getElementById('subhub-vsm-stream-card')) return;

    const grid = document.querySelector('.watch-pills:not(.owner-watch-pills)');
    if (!grid) return;
    grid.appendChild(subscriberButton());
  }

  install();
  setInterval(install, 900);
})();