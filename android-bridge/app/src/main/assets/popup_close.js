// 322.3.123: the site's own pop-ups that float over the server buttons («The site may
// close tomorrow — Join our Telegram channel», app / notification banners) are closed
// with their own «Later» / «✕». Only a pop-up that floats over the page (fixed or
// sticky) and talks about such things is touched; never «Join», never a link that
// leaves the page, never the site's check page («Just a quick check»).
// window.__subhubPopupCloseV3123() returns how many pop-ups it closed.
(function () {
  if (window.__subhubPopupCloseV3123) return;
  var DISMISS = /^(later|maybe later|not now|no thanks|no,? thanks|close|dismiss|skip|×|✕|✖|x)$/i;
  var PROMO = /telegram|may close|join (us|now|our)|our channel|new link|notifications?|subscribe|install (the |our )?app|add to home/i;
  var CHECK = /just a quick check|real person|not a (bot|robot)|captcha|verify (you|that)|checking your browser/i;
  function visible(e) {
    try {
      var r = e.getBoundingClientRect(), s = getComputedStyle(e);
      return r.width > 4 && r.height > 4 && s.display !== 'none' && s.visibility !== 'hidden' && Number(s.opacity) > 0.05;
    } catch (_) { return false; }
  }
  // The floating box this control belongs to (fixed / sticky, a few levels up).
  function floatingBox(e) {
    for (var p = e, k = 0; p && p !== document.body && p !== document.documentElement && k < 9; p = p.parentElement, k++) {
      try {
        var pos = getComputedStyle(p).position;
        if (pos === 'fixed' || pos === 'sticky') return p;
      } catch (_) { return null; }
    }
    return null;
  }
  window.__subhubPopupCloseV3123 = function () {
    var closed = 0;
    try {
      var els = [].slice.call(document.querySelectorAll('button,[role=button],a,span,div,i'));
      for (var i = 0; i < els.length; i++) {
        var e = els[i];
        if (e.children && e.children.length > 2) continue;   // a container, not a control
        var t = String(e.innerText || e.textContent || '').replace(/\s+/g, ' ').trim();
        var label = String(e.getAttribute('aria-label') || e.getAttribute('title') || '').trim();
        if (!(DISMISS.test(t) || (!t && /^(close|dismiss)$/i.test(label)))) continue;
        if (!visible(e)) continue;
        if (String(e.tagName).toUpperCase() === 'A') {
          var href = String(e.getAttribute('href') || '').trim();
          if (href && href !== '#' && !/^javascript:/i.test(href)) continue;   // it would leave the page
        }
        var box = floatingBox(e);
        if (!box || box.__subhubPopupClosed) continue;
        var words = String(box.innerText || box.textContent || '').slice(0, 800);
        if (!PROMO.test(words) || CHECK.test(words)) continue;
        box.__subhubPopupClosed = true;
        try { e.click(); closed++; } catch (_) {}
      }
    } catch (_) {}
    return closed;
  };
})();
