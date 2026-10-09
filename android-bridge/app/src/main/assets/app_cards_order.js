(function () {
  'use strict';
  if (window.__subhubPlaceAppCardV3106) return;
  // 322.3.106: every app card has a FIXED place in its grid, for the owner and
  // for viewers. Before, each card script added itself whenever its 900 ms timer
  // noticed it was missing, so after any redraw (a save, a delete, reopening a
  // film) the order depended on which timer fired first and the card the owner
  // had just worked on jumped to another spot.
  // Order (owner-approved): purple, yellow, open 1, Vidnest, open 2, open 3, open 4.
  // 322.3.107: the «سيرفرات التطبيق» list comes first (orders 0.10…0.19), and
  // app buttons always sit before the site's own «مشاهدة بالتطبيق» pills
  // (OnlyFlix/VidSrc), so viewers' numbers start with the app servers.
  const ORDER = {
    moviesmod: 1,
    vsm: 2,
    open1: 3,
    vidnest: 4,
    open2: 5,
    open3: 6,
    open4: 7
  };
  window.__subhubAppCardOrderV3106 = ORDER;
  const AR = '٠١٢٣٤٥٦٧٨٩';
  function arNum(n) { return String(n).replace(/\d/g, function (d) { return AR.charAt(+d); }); }

  function orderOf(el) {
    const v = Number(el && el.getAttribute && el.getAttribute('data-app-order'));
    return Number.isFinite(v) && v > 0 ? v : 0;
  }

  // The site's own app pills (OnlyFlix / VidSrc «مشاهدة بالتطبيق»).
  function firstSiteAppPill(grid) {
    try {
      const srcs = window._watchSources || [];
      return [].slice.call(grid.querySelectorAll('.watch-pill[data-src-idx]')).find(function (b) {
        const s = srcs[Number(b.getAttribute('data-src-idx'))];
        return !!(s && s.visitorKind === 'app');
      }) || null;
    } catch (_) { return null; }
  }

  // 322.3.108: for the owner the old cards live as compact rows inside the
  // «📺 سيرفرات التطبيق» section (block built by app_list_stream.js): cards
  // with a saved server under «الأزرار السابقة», the others folded under
  // «أزرار غير مستعملة».
  function ownerHosts() {
    const block = document.getElementById('subhub-applist-legacy-block');
    if (!block || !block.isConnected) return null;
    const saved = block.querySelector('[data-legacy="saved"]');
    const unused = block.querySelector('[data-legacy="unused"]');
    if (!saved || !unused) return null;
    return {
      block: block,
      saved: saved,
      unused: unused,
      details: block.querySelector('[data-legacy="details"]'),
      count: block.querySelector('[data-legacy="count"]'),
      savedHead: block.querySelector('[data-legacy="saved-head"]')
    };
  }
  function viewerReady(mode) {
    const r = (window.__subhubAppReadyV3107 || {})[mode];
    return !!(r && r.movieId === currentId() && r.ready);
  }
  function compact(card) {
    if (!card || card.getAttribute('data-compact') === '1') return;
    card.setAttribute('data-compact', '1');
    card.style.minHeight = '0';
    card.style.padding = '8px 10px';
    card.style.borderRadius = '12px';
    card.style.gap = '6px';
    card.style.width = '100%';
    card.style.boxSizing = 'border-box';
    card.style.margin = '0';
    if (!card.style.position) card.style.position = 'relative';
    for (let i = 0; i < card.children.length; i++) {
      const c = card.children[i];
      if (c.tagName === 'BUTTON') {
        c.style.fontSize = '.8rem';
        c.style.textAlign = 'start';
        c.style.lineHeight = '1.45';
        c.style.paddingInlineEnd = '50px';   // room for the 👁 badge
        const sm = c.querySelector('small');
        if (sm) sm.style.fontSize = '.6rem';
      } else if (c.tagName === 'DIV' && !c.classList.contains('subhub-viewer-badge')) {
        [].slice.call(c.querySelectorAll('button')).forEach(function (b) {
          b.style.minHeight = '30px';
          b.style.fontSize = '.62rem';
        });
      }
    }
  }
  function insertOrdered(host, el, order) {
    el.setAttribute('data-app-order', String(order));
    const kids = [].slice.call(host.children).filter(function (c) { return c !== el; });
    const later = kids.find(function (c) { return orderOf(c) > order; });
    if (later) host.insertBefore(el, later); else host.appendChild(el);
  }
  function updateLegacyCounts(h) {
    const nUnused = h.unused.children.length;
    const nSaved = h.saved.children.length;
    if (h.count) {
      const t = 'أزرار غير مستعملة (' + arNum(nUnused) + ')';
      if (h.count.textContent !== t) h.count.textContent = t;
    }
    if (h.details) h.details.style.display = nUnused ? '' : 'none';
    if (h.savedHead) h.savedHead.style.display = (nSaved || nUnused) ? '' : 'none';
  }
  function placeLegacy(h, el, mode) {
    compact(el);
    insertOrdered(viewerReady(mode) ? h.saved : h.unused, el, ORDER[mode]);
    updateLegacyCounts(h);
  }

  // Inserts (or moves) `el` into `grid` at the slot of `mode` (or `orderOverride`).
  window.__subhubPlaceAppCardV3106 = function (grid, el, mode, orderOverride) {
    if (!grid || !el) return;
    if (ORDER[mode] && !(Number(orderOverride) > 0)
        && grid.classList && grid.classList.contains('owner-watch-pills')) {
      const h = ownerHosts();
      if (h) { placeLegacy(h, el, mode); return; }
    }
    const order = Number(orderOverride) > 0 ? Number(orderOverride) : (ORDER[mode] || 99);
    el.setAttribute('data-app-order', String(order));
    const kids = [].slice.call(grid.children).filter(function (c) { return c !== el; });
    const later = kids.find(function (c) { return orderOf(c) > order; });
    if (later) { grid.insertBefore(el, later); return; }
    const earlier = kids.filter(function (c) { return orderOf(c) > 0; });
    if (earlier.length) { earlier[earlier.length - 1].insertAdjacentElement('afterend', el); return; }
    const sitePill = firstSiteAppPill(grid);
    if (sitePill) { grid.insertBefore(el, sitePill); return; }
    const m3u = document.getElementById('m3uOwnerTrialV360');
    if (m3u && m3u.parentElement === grid) { grid.insertBefore(el, m3u); return; }
    grid.appendChild(el);
  };

  function currentId() {
    try { return typeof currentMovie !== 'undefined' && currentMovie ? String(currentMovie.id || '') : ''; }
    catch (_) { return ''; }
  }

  // 322.3.107: the app buttons a viewer sees on this film, in viewer order:
  // the list servers first, then the old cards that have a saved server.
  // Index + 1 = the viewer's «مشاهدة بالتطبيق N» (the site numbers app buttons
  // in page order, and app buttons always come before the site's own pills).
  window.__subhubViewerAppOrderV3107 = function () {
    const id = currentId();
    const out = [];
    if (!id) return out;
    const list = window.__subhubAppListItemsV3107;
    if (list && list.movieId === id) list.items.forEach(function (x) { out.push('list:' + x.id); });
    const ready = window.__subhubAppReadyV3107 || {};
    Object.keys(ORDER).sort(function (a, b) { return ORDER[a] - ORDER[b]; }).forEach(function (m) {
      const r = ready[m];
      if (r && r.movieId === id && r.ready) out.push(m);
    });
    return out;
  };

  // 322.3.107: a 👁 badge on each old owner card = its number for viewers.
  // 322.3.108: also keeps each old card in the right part of the section.
  function decorate() {
    try {
      const grid = document.querySelector('.owner-watch-pills');
      if (!grid) return;
      const order = window.__subhubViewerAppOrderV3107();
      const h = ownerHosts();
      Object.keys(ORDER).forEach(function (m) {
        const card = document.getElementById('subhub-' + m + '-stream-card');
        if (!card) return;
        if (h) {
          const target = viewerReady(m) ? h.saved : h.unused;
          if (card.parentElement !== target) placeLegacy(h, card, m);
        } else if (card.parentElement !== grid) {
          return;
        }
        const k = order.indexOf(m);
        const txt = k >= 0 ? '👁 ' + arNum(k + 1) : '👁 —';
        let b = null;
        for (let i = 0; i < card.children.length; i++) {
          if (card.children[i].classList && card.children[i].classList.contains('subhub-viewer-badge')) { b = card.children[i]; break; }
        }
        if (!b) {
          b = document.createElement('span');
          b.className = 'subhub-viewer-badge';
          b.style.cssText = 'position:absolute;top:6px;left:8px;z-index:2;font-size:.66rem;font-weight:900;' +
            'padding:2px 7px;border-radius:9px;background:#0b1625;border:1px solid #3b5470;color:#fff;' +
            'pointer-events:none;line-height:1.5';
          if (!card.style.position) card.style.position = 'relative';
          card.appendChild(b);
        }
        if (b.textContent !== txt) {
          b.textContent = txt;
          b.title = k >= 0 ? ('يظهر للمشترك: مشاهدة بالتطبيق ' + arNum(k + 1)) : 'لا يظهر للمشترك';
        }
        b.style.opacity = k >= 0 ? '1' : '.55';
      });
      if (h) updateLegacyCounts(h);
    } catch (_) {}
  }
  setInterval(decorate, 900);
})();
