// 322.3.122: «🩺 فحص الأسماء» — read ONE page of a site name (opened hidden by
// SiteNameChecker) and say what it is, as a JSON string:
//   servers  servers listed on a film page (the same grid the player uses: a known
//            server name marks its box; with no known name only a big group of 10+
//            buttons counts, and real links to other pages never do, so a menu or
//            a row of film cards is never taken for servers)
//   known    how many of them are well-known server names
//   links    distinct links to film / series pages (/watch/movie-…, /watch/tv-…; 322.3.123: and
//            the Moviesmod family's /movie/27205, /tv/1416, /watch/tv/1416/2/5)
//   moved    322.3.123: the new address a moved site names on its own page (site_move.js,
//            loaded before this script), '' when the page is not such a notice
//   check    a «verify / continue» page is showing (Cloudflare's or the site's own)
//   blocked  Cloudflare's «Unavailable For Legal Reasons» page
// Nothing on the page is pressed or changed.
(function () {
  try {
    var FILM = /\/watch\/(movie|tv)-[a-z0-9-]+|\/watch\/(movie|tv)\/\d{1,12}(?:\/\d{1,3}\/\d{1,4})?|\/(movie|tv)\/\d{1,12}/i;
    function clean(v) { return String(v || '').replace(/[⭐★☆]/g, '').replace(/\s+/g, ' ').trim(); }
    function norm(v) { return clean(v).toLowerCase(); }
    function bad(t) {
      t = norm(t);
      return !t || /^(watch now|play|play now|home|movies|select server|trailer|download|settings|view details)$/.test(t)
        // 322.3.123: share buttons and pop-up buttons are never servers
        || /^(whatsapp|telegram|facebook|twitter|x|reddit|pinterest|email|share|copy link|copy|more|more…|more\.\.\.|join|later)$/.test(t);
    }
    function known(t) {
      t = norm(t);
      return /^(vidsrc\.mov|vidsrc\.fyi|vidrock|vidnest|vidking|vidlink|vidfast|vidup|videasy|111movies|2embed|multiembed|superflix|peachify|vidzee|vidrift|videm|mappletv|moviesapi|vixsrc|vidsrc-v[1-4]|vip \(no ads\))$/.test(t);
    }
    var here = (String(location.pathname || '').match(FILM) || [''])[0].toLowerCase();
    function filmOf(e) {
      var m = String(e.getAttribute('href') || '').match(FILM);
      return m ? m[0].toLowerCase() : '';
    }
    // a link to ANOTHER film is a film card; a link to this film (?server=…) may be a server
    function otherFilm(e) { var f = filmOf(e); return !!f && f !== here; }
    function navLink(e) {
      if (String(e.tagName || '').toUpperCase() !== 'A') return false;
      var h = String(e.getAttribute('href') || '').trim();
      return !!h && h.charAt(0) !== '#' && !/^javascript:/i.test(h);
    }
    function serverish(e) { return !otherFilm(e) && (!navLink(e) || (!!here && filmOf(e) === here)); }
    var SEL = 'button,a,[role=button],[data-server],[data-src],[data-url],[data-embed]';
    function label(e) { return clean(e.innerText || e.textContent || e.getAttribute('data-server') || ''); }
    var all = [].slice.call(document.querySelectorAll(SEL)).filter(function (e) {
      var t = label(e);
      return t.length >= 2 && t.length <= 48 && !bad(t);
    });
    var base = all.filter(function (e) { return e.hasAttribute('data-server') || known(label(e)); });
    var boxes = [];
    base.forEach(function (e) {
      var b = e.parentElement;
      if (!b) return;
      var n = all.filter(function (x) { return b.contains(x); }).length;
      if (n < 3 && b.parentElement) b = b.parentElement;
      if (boxes.indexOf(b) < 0) boxes.push(b);
    });
    var bigGroup = false;
    if (!boxes.length) {
      var cand = all.filter(serverish), best = null, bn = 0, seen = [];
      cand.forEach(function (x) {
        var p = x.parentElement;
        if (!p || seen.indexOf(p) >= 0) return;
        seen.push(p);
        var n = cand.filter(function (y) { return y.parentElement === p; }).length;
        if (n > bn) { bn = n; best = p; }
      });
      if (best && bn >= 10) { boxes.push(best); bigGroup = true; }
    }
    var names = {}, servers = 0, kn = 0;
    all.forEach(function (x) {
      if (!boxes.some(function (b) { return b.contains(x); })) return;
      if (!serverish(x)) return;
      var t = label(x), k = norm(t);
      if (!k || names[k]) return;
      names[k] = 1;
      servers++;
      if (known(t)) kn++;
    });
    if (!kn && !bigGroup) servers = 0;

    var links = {}, ln = 0;
    [].slice.call(document.querySelectorAll('a[href]')).forEach(function (a) {
      var f = filmOf(a);
      if (f && !links[f]) { links[f] = 1; ln++; }
    });

    var text = String((document.body && document.body.innerText) || '').slice(0, 4000);
    var flat = text.replace(/\s+/g, ' ').trim();
    var cf = !!document.querySelector('#challenge-form,#challenge-running,#challenge-stage,#cf-challenge-running,' +
      '.cf-browser-verification,iframe[src*="challenges.cloudflare.com"]');
    try { if (window._cf_chl_opt) cf = true; } catch (_) {}
    var check = cf ||
      /just a (moment|quick check)|checking your browser|verify(ing)? (that )?you are (a )?human|are you (a )?(human|robot)|real person,? not a bot|one more step|attention required|ddos protection|needs to review the security of your connection/i.test(flat) ||
      (flat.length < 700 && ln === 0 && servers === 0 && /\b(continue|verify|captcha|human|robot|security check)\b/i.test(flat));
    var blocked = /unavailable for legal reasons/i.test(flat) && /cloudflare/i.test(flat);
    var moved = '';
    try { if (typeof window.__subhubSiteMoveV3123 === 'function') moved = String(window.__subhubSiteMoveV3123() || ''); } catch (_) {}
    return JSON.stringify({ servers: servers, known: kn, links: ln, check: !!check, blocked: !!blocked,
      host: String(location.hostname || '').toLowerCase(), len: flat.length, moved: moved });
  } catch (e) {
    return JSON.stringify({ servers: -1, known: 0, links: -1, check: false, blocked: false, error: String(e && e.message || e) });
  }
})();
