(function () {
  'use strict';
  if (window.__soapDiagInstalled) return;
  window.__soapDiagInstalled = true;

  const TARGET_RE = /(?:api\.ilove2day\.com|sv2\.nontongo\.(?:day|stream)|anotherday\.soapsoap123\.workers\.dev|media\.medmedia05\.mom)/i;
  const MAX_BODY = 16000;

  function absUrl(v) {
    try {
      if (v && typeof v === 'object' && 'url' in v) v = v.url;
      return new URL(String(v || ''), location.href).href;
    } catch (_) { return String(v || ''); }
  }

  function isTarget(url) { return TARGET_RE.test(String(url || '')); }

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
                url, status: res.status, body: cap(t, MAX_BODY)
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
      if (isTarget(u)) emit('beacon-request', { url: u, method: 'POST', body: bodyText(data) });
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
        if (isTarget(u)) emit('dom-url', { tag: node.tagName || '', attr, url: u });
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
