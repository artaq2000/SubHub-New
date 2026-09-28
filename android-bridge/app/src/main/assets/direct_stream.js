(function () {
  'use strict';
  if (window.__subhubDirectInstalled) return;
  window.__subhubDirectInstalled = true;
  const token = '__VIDSRC_GUARD_TOKEN__';
  let active = null;
  let opening = false;
  function movie() { return typeof currentMovie !== 'undefined' ? currentMovie : null; }
  function notify(text) {
    if (typeof showToast === 'function') showToast(text, 'error');
  }
  async function open() {
    if (opening || active) return;
    opening = true;
    const selected = movie();
    try {
      if (!selected || typeof isLoggedIn === 'undefined' || !isLoggedIn ||
          typeof checkOwnerAccess !== 'function' || !(await checkOwnerAccess()) || movie() !== selected) return;
      const bridge = window.SubHubAndroidBridge;
      if (!bridge || typeof bridge.openDirectStream !== 'function') throw new Error('bridge unavailable');
      const id = typeof _vidfastMovieIdV302 === 'function' ? String(_vidfastMovieIdV302() || '') : '';
      if (!/^[A-Za-z0-9_-]{1,80}$/.test(id)) { notify('لا يتوفر معرّف مصدر لهذا الفيلم.'); return; }
      if (typeof _buildSubtitleTrackCatalog !== 'function') throw new Error('catalog unavailable');
      const catalog = _buildSubtitleTrackCatalog().slice();
      // Close existing players through their normal lifecycle before creating an independent session.
      if (typeof stopInlinePlayersV265 === 'function') stopInlinePlayersV265();
      if (typeof closeEmbedPlayer === 'function') closeEmbedPlayer();
      const session = 'direct_' + Date.now() + '_' + Math.random().toString(36).slice(2);
      active = { session, movieId: selected.id, catalog };
      bridge.openDirectStream(token, JSON.stringify({
        session, movieId: id,
        catalog: catalog.map(x => ({name: String(x.name || 'ترجمة SubHub')})),
        defaultIndex: catalog.findIndex(x => x.isDefault === true)
      }));
    } catch (_) { active = null; notify('تعذّر فتح تجربة البث المباشر.'); }
    finally { opening = false; }
  }
  window.__subhubDirectClosed = function (session) {
    if (active && active.session === session) active = null;
  };
  window.__subhubDirectSubtitle = async function (session, index) {
    const state = active;
    if (!state || state.session !== session || !Number.isInteger(index) || !state.catalog[index]) return;
    let cues = [], error = '';
    try {
      // Retains SubHub's existing access check/signing; never reads protected URLs directly.
      const loaded = await _loadSubtitleCatalogEntry(state.catalog[index]);
      if (!Array.isArray(loaded)) throw new Error('segmented');
      cues = loaded.filter(c => Number.isFinite(c.start) && Number.isFinite(c.end) && c.end > c.start)
        .map(c => ({ start: c.start, end: c.end, text: String(c.text || '') }));
      if (!cues.length) throw new Error('empty');
    } catch (_) { error = 'تعذّر تحميل الترجمة لهذه التجربة. اختر ترجمة أخرى من CC.'; }
    if (active !== state || !movie() || movie().id !== state.movieId) return;
    window.SubHubAndroidBridge.directStreamSubtitles(token, session, index, JSON.stringify(cues), error);
  };
  function install() {
    const old = document.getElementById('subhub-direct-stream-button');
    if (typeof isLoggedIn === 'undefined' || !isLoggedIn || !movie()) { if (old) old.remove(); return; }
    if (old) return;
    const reference = document.getElementById('vidsrcOwnerTrialV355');
    if (!reference || !reference.parentElement) return;
    const button = document.createElement('button');
    button.id = 'subhub-direct-stream-button'; button.type = 'button';
    button.className = reference.className;
    button.textContent = '▶ مشاهدة مباشرة — تجريبي';
    button.style.cssText = 'min-height:68px;border:1px solid #e8b544;border-radius:16px;background:#132536;color:#fff;padding:12px;font:inherit;cursor:pointer';
    button.addEventListener('click', open);
    reference.insertAdjacentElement('afterend', button);
  }
  // This asset neither wraps nor replaces any existing R2/VidSrc opener.
  install(); setInterval(install, 1000);
})();