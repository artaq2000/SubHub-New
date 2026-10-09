(function () {
  'use strict';
  if (window.__subhubPlaceAppCardV3106) return;
  // 322.3.106: every app card has a FIXED place in its grid, for the owner and
  // for viewers. Before, each card script added itself whenever its 900 ms timer
  // noticed it was missing, so after any redraw (a save, a delete, reopening a
  // film) the order depended on which timer fired first and the card the owner
  // had just worked on jumped to another spot.
  // Order (owner-approved): purple, yellow, open 1, Vidnest, open 2, open 3, open 4.
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

  function orderOf(el) {
    const v = Number(el && el.getAttribute && el.getAttribute('data-app-order'));
    return Number.isFinite(v) && v > 0 ? v : 0;
  }

  // Inserts (or moves) `el` into `grid` at the slot of `mode`.
  window.__subhubPlaceAppCardV3106 = function (grid, el, mode) {
    if (!grid || !el) return;
    const order = ORDER[mode] || 99;
    el.setAttribute('data-app-order', String(order));
    const kids = [].slice.call(grid.children).filter(function (c) { return c !== el; });
    const later = kids.find(function (c) { return orderOf(c) > order; });
    if (later) { grid.insertBefore(el, later); return; }
    const earlier = kids.filter(function (c) { return orderOf(c) > 0; });
    if (earlier.length) { earlier[earlier.length - 1].insertAdjacentElement('afterend', el); return; }
    const m3u = document.getElementById('m3uOwnerTrialV360');
    if (m3u && m3u.parentElement === grid) { grid.insertBefore(el, m3u); return; }
    grid.appendChild(el);
  };
})();
