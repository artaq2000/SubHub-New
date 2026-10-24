// 322.3.123: a site that moved says its new address on its own page — read it.
//   «Moviesmod has a new home: streamboppo.cyou» + «Continue to streamboppo.cyou →»
//   the old film page: «Use the New DopeBox? Click here →», «or open the new DopeBox»
// window.__subhubSiteMoveV3123() returns the new site name, or '' when the page is not
// such a notice. It only reads: nothing on the page is pressed or changed. Links to the
// network's directory (FlixNetwork), Telegram and share buttons never count.
(function () {
  if (window.__subhubSiteMoveV3123) return;
  var HUB = /(^|\.)(flixnetwork\.[a-z]{2,12}|t\.me|telegram\.(me|org)|whatsapp\.com|wa\.me|facebook\.com|fb\.com|twitter\.com|x\.com|reddit\.com|pinterest\.com|google\.com|youtube\.com|discord\.(gg|com)|instagram\.com|tiktok\.com|cloudflare\.com)$/;
  var NOTICE = /has a new home|use the new [^?]{1,40}\?|open the new [a-z0-9 ]{1,30}|remember the new address/i;
  var LINK = /continue to|click here|open the new|use the new|new home|go to the new/;
  function clean(raw) {
    var h = String(raw || '').trim().toLowerCase().replace(/^https?:\/\//, '').replace(/[\/?#:].*$/, '').replace(/^www\./, '');
    return /^[a-z0-9-]+(\.[a-z0-9-]+)+$/.test(h) && h.length <= 80 && /\.[a-z]{2,12}$/.test(h) ? h : '';
  }
  function usable(h) {
    return !!h && h !== clean(location.hostname) && !HUB.test(h);
  }
  window.__subhubSiteMoveV3123 = function () {
    try {
      var text = String((document.body && document.body.innerText) || '').slice(0, 6000).replace(/\s+/g, ' ');
      if (!NOTICE.test(text)) return '';
      // 1) the site's own link to its new home
      var links = [].slice.call(document.querySelectorAll('a[href]'));
      for (var i = 0; i < links.length; i++) {
        var a = links[i];
        var t = String(a.innerText || a.textContent || '').replace(/\s+/g, ' ').trim().toLowerCase();
        if (!LINK.test(t)) continue;
        var h = '';
        try { h = clean(new URL(a.getAttribute('href'), location.href).hostname); } catch (_) {}
        if (usable(h)) return h;
      }
      // 2) the address written on the page («a new home: X», «Continue to X»)
      var m = text.match(/new home:?\s*([a-z0-9-]+(?:\.[a-z0-9-]+)+)/i) || text.match(/continue to\s+([a-z0-9-]+(?:\.[a-z0-9-]+)+)/i);
      var w = m ? clean(m[1]) : '';
      return usable(w) ? w : '';
    } catch (_) {
      return '';
    }
  };
})();
