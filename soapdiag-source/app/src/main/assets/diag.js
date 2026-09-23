(function () {
  'use strict';
  if (window.__soapDiagInstalled) return;
  window.__soapDiagInstalled = true;

  const TARGET_RE = /(?:onlyflix\.to|cdnm\.ink|api\.ilove2day\.com|sv\d+\.nontongo\.(?:day|stream)|cdnmvs\.online|anotherday\.soapsoap123\.workers\.dev|media\.medmedia05\.mom)/i;
  const SERVER1_RE = /^https?:\/\/s1\.cdnmvs\.online\//i;
  const M3U8_RE = /\.m3u8(?:[?#]|$)/i;
  const MAX_BODY = 16000;
  let qualityUiDone = false;
  let qualityMenuOpened = false;
  let qualityUiPasses = 0;

  function isVisible(el) {
    try {
      const r = el.getBoundingClientRect();
      const st = getComputedStyle(el);
      return r.width > 0 && r.height > 0 && st.display !== 'none' && st.visibility !== 'hidden' && Number(st.opacity || 1) > 0;
    } catch (_) { return false; }
  }

  function ownText(el) {
    try { return String(el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim(); }
    catch (_) { return ''; }
  }

  function clickElement(el, reason) {
    if (!el) return false;
    try {
      emit('quality-ui-action', {
        url: location.href,
        action: reason,
        tag: el.tagName || '',
        text: ownText(el).slice(0, 120),
        cls: String(el.className || '').slice(0, 180)
      });
      el.dispatchEvent(new MouseEvent('pointerdown', { bubbles: true, cancelable: true, view: window }));
      el.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, view: window }));
      el.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, cancelable: true, view: window }));
      el.click();
      return true;
    } catch (_) {
      try { el.click(); return true; } catch (_) { return false; }
    }
  }

  function exactQualityNodes(q) {
    const wanted = String(q) + 'p';
    const all = Array.from(document.querySelectorAll('button,[role="button"],[role="menuitem"],[role="option"],li,a,span,div'));
    return all.filter(el => {
      if (!isVisible(el)) return false;
      const t = ownText(el).toLowerCase();
      return t === wanted || t === String(q);
    }).sort((a,b) => {
      const ac = a.children ? a.children.length : 0;
      const bc = b.children ? b.children.length : 0;
      if (ac !== bc) return ac - bc;
      return ownText(a).length - ownText(b).length;
    });
  }

  function findQualityTrigger() {
    const all = Array.from(document.querySelectorAll('button,[role="button"],a,span,div'));
    let candidates = all.filter(el => {
      if (!isVisible(el)) return false;
      const text = ownText(el);
      const attrs = [
        el.getAttribute && el.getAttribute('aria-label'),
        el.getAttribute && el.getAttribute('title'),
        el.getAttribute && el.getAttribute('data-title'),
        el.getAttribute && el.getAttribute('data-quality'),
        el.className
      ].filter(Boolean).join(' ');
      return /^(?:240|360|480|720|1080)p$/i.test(text) ||
             /quality|resolution|settings|gear|جودة|إعدادات/i.test(attrs);
    });
    candidates.sort((a,b) => {
      const ta = ownText(a), tb = ownText(b);
      const aq = /p$/i.test(ta) ? 0 : 1;
      const bq = /p$/i.test(tb) ? 0 : 1;
      if (aq !== bq) return aq - bq;
      return (a.children ? a.children.length : 0) - (b.children ? b.children.length : 0);
    });
    return candidates[0] || null;
  }

  function trySelect1080FromPlayerUi() {
    if (qualityUiDone || !/cdnmovies-stream\.online$/i.test(location.hostname)) return;
    qualityUiPasses++;

    const q1080 = exactQualityNodes(1080);
    if (q1080.length) {
      const target = q1080[0];
      const text = ownText(target);
      if (clickElement(target, 'select-1080')) {
        qualityUiDone = true;
        emit('quality-ui-selected', { url: location.href, quality: 1080, text: text });
        return;
      }
    }

    if (!qualityMenuOpened || qualityUiPasses % 3 === 0) {
      const trigger = findQualityTrigger();
      if (trigger && clickElement(trigger, 'open-quality-menu')) {
        qualityMenuOpened = true;
        emit('quality-ui-menu', { url: location.href, text: ownText(trigger) });
      }
    }

    if (qualityUiPasses >= 24) {
      emit('quality-ui-timeout', { url: location.href });
      clearInterval(qualityUiTimer);
    }
  }

  const qualityUiTimer = /cdnmovies-stream\.online$/i.test(location.hostname)
    ? setInterval(trySelect1080FromPlayerUi, 650)
    : null;

  function absUrl(v) {
    try {
      if (v && typeof v === 'object' && 'url' in v) v = v.url;
      return new URL(String(v || ''), location.href).href;
    } catch (_) { return String(v || ''); }
  }

  function isTarget(url) { return TARGET_RE.test(String(url || '')); }

  function emitCandidate(url, source) {
    const u = absUrl(url);
    if (SERVER1_RE.test(u) && M3U8_RE.test(u)) {
      emit('candidate-url', { url: u, source: source || '' });
      if (/cdnmovies-stream\.online$/i.test(location.hostname)) {
        setTimeout(trySelect1080FromPlayerUi, 120);
      }
      return true;
    }
    return false;
  }

  function scanText(raw, source) {
    try {
      const s = String(raw || '').replace(/\\\//g, '/');
      const urls = s.match(/https?:\/\/[^\s"'<>]+/gi) || [];
      urls.slice(0, 120).forEach(u => emitCandidate(u.replace(/[),;]+$/, ''), source));
    } catch (_) {}
  }

  function cap(v, n) {
    const s = String(v == null ? '' : v);
    return s.length > n ? s.slice(0, n) + '\n…[truncated]' : s;
  }

  function headerObject(input) {
    const out = {};
    try {
      const h = new Headers(input || {});
      h.forEach((v, k) => { out[k] = v; });
    } catch (_) {}
    return out;
  }

  function bodyText(body) {
    try {
      if (body == null) return '';
      if (typeof body === 'string') return cap(body, MAX_BODY);
      if (body instanceof URLSearchParams) return cap(body.toString(), MAX_BODY);
      if (body instanceof FormData) {
        const parts = [];
        body.forEach((v, k) => parts.push(k + '=' + (typeof v === 'string' ? v : '[File ' + (v && v.name || '') + ']')));
        return cap(parts.join('&'), MAX_BODY);
      }
      if (body instanceof Blob) return '[Blob ' + body.type + ' ' + body.size + ' bytes]';
      if (body instanceof ArrayBuffer) return '[ArrayBuffer ' + body.byteLength + ' bytes]';
      if (ArrayBuffer.isView(body)) return '[TypedArray ' + body.byteLength + ' bytes]';
      return cap(String(body), MAX_BODY);
    } catch (e) { return '[unreadable body: ' + e + ']'; }
  }

  function emit(kind, data) {
    try {
      if (window.SoapDiagBridge && typeof window.SoapDiagBridge.emit === 'function') {
        window.SoapDiagBridge.emit(JSON.stringify({
          kind: kind,
          data: data || {},
          page: location.href,
          ts: Date.now()
        }));
      }
    } catch (_) {}
  }

  emit('diag-installed', { page: location.href });

  let server1Clicked = false;
  function tryClickServer1() {
    if (server1Clicked) return;
    try {
      const nodes = Array.from(document.querySelectorAll('button,a,[role="button"],li,[data-server],[data-id],[data-name]'));
      for (const el of nodes) {
        const text = String((el.innerText || el.textContent || '')).trim();
        const attrs = [
          el.getAttribute && el.getAttribute('data-server'),
          el.getAttribute && el.getAttribute('data-id'),
          el.getAttribute && el.getAttribute('data-name'),
          el.getAttribute && el.getAttribute('title'),
          el.getAttribute && el.getAttribute('aria-label')
        ].filter(Boolean).join(' ');
        const hay = (text + ' ' + attrs).toLowerCase();
        const isOne = /(?:server|سيرفر)\s*0*1\b/i.test(hay) || /\bserver1\b/i.test(hay) || /^1$/.test(text);
        if (isOne) {
          server1Clicked = true;
          emit('server1-click', { text: text.slice(0, 200) });
          try { el.click(); } catch (_) {}
          break;
        }
      }
    } catch (_) {}
  }

  function scanDomForServer1() {
    try {
      const root = document.documentElement;
      if (!root) return;
      scanText(root.innerHTML.slice(0, 300000), 'document-html');
      document.querySelectorAll('script').forEach(sc => scanText(sc.textContent || '', 'script'));
      document.querySelectorAll('[src],[href],[data-src],[data-url],[data-link]').forEach(el => {
        ['src','href','data-src','data-url','data-link'].forEach(attr => {
          const v = el.getAttribute && el.getAttribute(attr);
          if (v) emitCandidate(v, 'attribute:' + attr);
        });
      });
      tryClickServer1();
    } catch (_) {}
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', scanDomForServer1, { once: true });
  } else {
    scanDomForServer1();
  }
  let autoPasses = 0;
  const autoTimer = setInterval(() => {
    scanDomForServer1();
    autoPasses++;
    if (autoPasses >= 20 || SERVER1_RE.test(location.href)) clearInterval(autoTimer);
  }, 700);

  // fetch(): captures POST body and readable API responses without changing the request.
  if (typeof window.fetch === 'function') {
    const nativeFetch = window.fetch;
    window.fetch = function(input, init) {
      const url = absUrl(input);
      let method = 'GET';
      try { method = String((init && init.method) || (input && input.method) || 'GET').toUpperCase(); } catch (_) {}
      let headers = {};
      try { headers = headerObject((init && init.headers) || (input && input.headers)); } catch (_) {}
      if (isTarget(url)) {
        emitCandidate(url, 'fetch');
        const immediateBody = init && Object.prototype.hasOwnProperty.call(init, 'body') ? bodyText(init.body) : '';
        emit('fetch-request', { url, method, headers, body: immediateBody });
        if (!immediateBody && typeof Request !== 'undefined' && input instanceof Request) {
          try {
            input.clone().text().then(t => {
              if (t) emit('fetch-request-body', { url, method, body: cap(t, MAX_BODY) });
            }).catch(() => {});
          } catch (_) {}
        }
      }
      let p;
      try { p = nativeFetch.apply(this, arguments); }
      catch (e) {
        if (isTarget(url)) emit('fetch-error', { url, method, error: String(e) });
        throw e;
      }
      return p.then(function(res) {
        if (isTarget(url)) {
          const hdrs = {};
          try { res.headers.forEach((v, k) => hdrs[k] = v); } catch (_) {}
          emit('fetch-response-head', { url, status: res.status, statusText: res.statusText, headers: hdrs });
          const ct = String((hdrs['content-type'] || '')).toLowerCase();
          if (!/video|audio|octet-stream/.test(ct)) {
            try {
              res.clone().text().then(t => emit('fetch-response-body', {
                url, status: res.status, body: cap(t, M3U8_RE.test(url) ? 1400 : MAX_BODY)
              })).catch(e => emit('fetch-response-body-error', { url, error: String(e) }));
            } catch (_) {}
          }
        }
        return res;
      }, function(err) {
        if (isTarget(url)) emit('fetch-error', { url, method, error: String(err) });
        throw err;
      });
    };
  }

  // XMLHttpRequest: captures request body, custom headers and readable response bodies.
  if (window.XMLHttpRequest && XMLHttpRequest.prototype) {
    const X = XMLHttpRequest.prototype;
    const nativeOpen = X.open;
    const nativeSend = X.send;
    const nativeSetRequestHeader = X.setRequestHeader;

    X.open = function(method, url) {
      this.__soapDiag = { method: String(method || 'GET').toUpperCase(), url: absUrl(url), headers: {} };
      return nativeOpen.apply(this, arguments);
    };

    X.setRequestHeader = function(k, v) {
      try { if (this.__soapDiag) this.__soapDiag.headers[String(k)] = String(v); } catch (_) {}
      return nativeSetRequestHeader.apply(this, arguments);
    };

    X.send = function(body) {
      const m = this.__soapDiag || { method: 'GET', url: '', headers: {} };
      if (isTarget(m.url)) {
        emitCandidate(m.url, 'xhr');
        emit('xhr-request', { url: m.url, method: m.method, headers: m.headers, body: bodyText(body) });
        const done = () => {
          const data = { url: m.url, method: m.method, status: 0, responseType: '' };
          try { data.status = this.status; } catch (_) {}
          try { data.responseType = this.responseType || ''; } catch (_) {}
          try { data.responseURL = this.responseURL || ''; } catch (_) {}
          try {
            if (!this.responseType || this.responseType === 'text') data.body = cap(this.responseText || '', MAX_BODY);
            else if (this.responseType === 'json') data.body = cap(JSON.stringify(this.response), MAX_BODY);
            else data.body = '[' + this.responseType + ' response]';
          } catch (e) { data.body = '[response unreadable: ' + e + ']'; }
          emit('xhr-response', data);
        };
        this.addEventListener('loadend', done, { once: true });
        this.addEventListener('error', () => emit('xhr-error', { url: m.url, method: m.method }), { once: true });
      }
      return nativeSend.apply(this, arguments);
    };
  }

  // sendBeacon(): common for /events style POSTs.
  if (navigator.sendBeacon) {
    const nativeBeacon = navigator.sendBeacon.bind(navigator);
    navigator.sendBeacon = function(url, data) {
      const u = absUrl(url);
      if (isTarget(u)) {
        emitCandidate(u, 'beacon');
        emit('beacon-request', { url: u, method: 'POST', body: bodyText(data) });
      }
      return nativeBeacon.apply(navigator, arguments);
    };
  }

  // Record player-created media/source URLs even when the media request itself is native.
  const recordNode = function(node) {
    try {
      if (!node || !node.getAttribute) return;
      ['src', 'href'].forEach(attr => {
        const v = node.getAttribute(attr);
        if (!v) return;
        const u = absUrl(v);
        if (isTarget(u)) {
          emitCandidate(u, 'dom');
          emit('dom-url', { tag: node.tagName || '', attr, url: u });
        }
      });
    } catch (_) {}
  };
  try {
    new MutationObserver(list => {
      list.forEach(m => {
        if (m.type === 'attributes') recordNode(m.target);
        m.addedNodes && m.addedNodes.forEach(n => {
          recordNode(n);
          try { n.querySelectorAll && n.querySelectorAll('[src],[href]').forEach(recordNode); } catch (_) {}
        });
      });
    }).observe(document.documentElement || document, { subtree: true, childList: true, attributes: true, attributeFilter: ['src','href'] });
  } catch (_) {}
})();

