(function () {
  'use strict';
  // 322.3.92: exclusive/limited subtitles inside the native app players.
  // The site already serves these as short 4-second pieces (mode "segments")
  // so the full file never reaches the device. The native player owns the
  // clock: it asks for the piece of the current time, and this pump fetches
  // that piece with the same signed segment link the site uses.
  if (window.__subhubSegmentPumpV3292) return;
  const token = '__VIDSRC_GUARD_TOKEN__';
  const BUCKET = 4;
  let current = null;

  function bridge() { return window.SubHubAndroidBridge; }

  function arDigits(v) {
    return String(v).replace(/\d/g, function (d) { return '٠١٢٣٤٥٦٧٨٩'[Number(d)]; });
  }

  function startNotice(limit) {
    if (!limit) return '';
    return '🔒 الترجمة متاحة مجاناً لأول ' + arDigits(Math.max(1, Math.round(limit / 60))) + ' دقيقة';
  }

  function endNotice(message) {
    const m = String(message || '').trim();
    if (m && m.length <= 160) return m;
    return '🔒 انتهت مدة الترجمة المجانية — اشترك لإكمال الترجمة';
  }

  function cueList(list) {
    if (typeof _serverCueList === 'function') {
      try { return _serverCueList(list); } catch (_) {}
    }
    return (list || []).map(function (q) {
      return { start: Number(q.s || 0), end: Number(q.e || 0), text: String(q.t || '') };
    });
  }

  function clean(list) {
    return (list || [])
      .filter(function (c) { return c && Number.isFinite(c.start) && Number.isFinite(c.end) && c.end > c.start; })
      .map(function (c) { return { start: c.start, end: c.end, text: String(c.text || '') }; });
  }

  function alive(st) {
    if (current !== st) return false;
    try { return st.isAlive(); } catch (_) { return false; }
  }

  function send(st, bucket, cues, notice) {
    if (!alive(st)) return;
    const b = bridge();
    if (!b || typeof b.directStreamSegmentCues !== 'function') return;
    try {
      b.directStreamSegmentCues(token, st.session, st.index, bucket, JSON.stringify(cues || []), notice || '');
    } catch (_) {}
  }

  function fetchBucket(st, bucket) {
    const k = String(bucket);
    if (st.cache[k]) return Promise.resolve(st.cache[k]);
    if (st.loading[k]) return st.loading[k];
    const url = st.url + '&t=' + encodeURIComponent(String(bucket));
    const p = fetch(url, { cache: 'no-store' }).then(async function (r) {
      if (!r.ok) {
        let msg = '';
        try { msg = await r.text(); } catch (_) {}
        const err = new Error('segment ' + r.status);
        err.status = r.status;
        err.serverMessage = (r.status === 403 || r.status === 410 || r.status === 426 || r.status === 503)
          ? String(msg || '').slice(0, 160) : '';
        throw err;
      }
      const d = await r.json();
      const entry = d && d.ended
        ? { ended: true, message: String(d.message || ''), cues: [] }
        : { ended: false, message: '', cues: clean(cueList(d && d.cues)) };
      st.cache[k] = entry;
      const keys = Object.keys(st.cache);
      if (keys.length > 30) delete st.cache[keys[0]];
      return entry;
    }).finally(function () {
      delete st.loading[k];
    });
    st.loading[k] = p;
    return p;
  }

  function merged(st, bucket) {
    const out = [];
    const seen = {};
    [bucket - BUCKET, bucket, bucket + BUCKET].forEach(function (b) {
      const e = st.cache[String(b)];
      if (!e || e.ended) return;
      e.cues.forEach(function (c) {
        const id = c.start + '|' + c.end + '|' + c.text;
        if (seen[id]) return;
        seen[id] = true;
        out.push(c);
      });
    });
    out.sort(function (a, b) { return a.start - b.start; });
    return out;
  }

  function overLimit(st, bucket, message) {
    const notice = st.endShown ? '' : endNotice(message);
    st.endShown = true;
    send(st, bucket, [], notice);
  }

  function request(st, time) {
    const t = Math.max(0, Number(time) || 0);
    const bucket = Math.floor(t / BUCKET) * BUCKET;
    if (st.limit && t >= st.limit) { overLimit(st, bucket, ''); return; }
    // Back before the limit again: allow the end notice to show once more later.
    st.endShown = false;
    fetchBucket(st, bucket).then(function (entry) {
      if (!alive(st)) return;
      if (entry.ended) { overLimit(st, bucket, entry.message); return; }
      send(st, bucket, merged(st, bucket), '');
      const next = bucket + BUCKET;
      if (!st.limit || next < st.limit) {
        fetchBucket(st, next).catch(function () {});
      }
    }).catch(function (err) {
      if (!alive(st)) return;
      const msg = err && err.serverMessage ? err.serverMessage : '';
      if (msg && st.lastError !== msg) {
        st.lastError = msg;
        send(st, bucket, [], msg);
      }
    });
  }

  window.__subhubSegmentPumpV3292 = {
    // Returns true when the subtitle was handed to the native segment clock.
    start: function (session, index, access, isAlive) {
      if (!access || !access.segmentUrl || !Number.isInteger(index)) return false;
      const b = bridge();
      if (!b || typeof b.directStreamSegmentMode !== 'function'
          || typeof b.directStreamSegmentCues !== 'function') return false;
      const st = {
        session: String(session || ''),
        index: index,
        url: String(access.segmentUrl),
        limit: Math.max(0, Number(access.limitSeconds || 0)),
        cache: {},
        loading: {},
        endShown: false,
        lastError: '',
        isAlive: typeof isAlive === 'function' ? isAlive : function () { return true; }
      };
      current = st;
      if (!alive(st)) return true;
      try {
        b.directStreamSegmentMode(token, st.session, st.index, startNotice(st.limit));
      } catch (_) {
        current = null;
        return false;
      }
      return true;
    },
    stop: function (session) {
      if (current && current.session === String(session || '')) current = null;
    }
  };

  // Native player → "what is shown at this time?" (time is already sync-adjusted).
  window.__subhubDirectSegment = function (session, index, time) {
    const st = current;
    if (!st || st.session !== String(session || '') || st.index !== index) return;
    if (!alive(st)) { current = null; return; }
    request(st, time);
  };
})();
