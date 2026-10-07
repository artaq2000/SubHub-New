const fs = require('fs'), vm = require('vm'), path = require('path');
const A = path.join(process.argv[2] || path.join(__dirname, '..'), 'app/src/main/assets');
function ctx() {
  const writes = [], reports = [];
  const c = {
    console, setTimeout: (f) => 0, setInterval: () => 0, clearTimeout() {}, Date, Math, Number, String, Object, JSON, Array, Promise, URL,
    document: { getElementById: () => null, createElement: () => ({ style: {}, appendChild() {}, addEventListener() {} }), querySelector: () => null, querySelectorAll: () => [], body: { appendChild() {} } },
    isLoggedIn: true, currentMovie: { id: 'm1' },
    checkOwnerAccess: async () => true,
    db: { collection: () => ({ doc: (id) => ({ set: async (data, opt) => { writes.push({ id, data, opt }); } }) }) },
    SubHubAndroidBridge: {
      directStreamSyncPinned: (t, s, ok, msg, off) => reports.push({ s, ok, msg, off }),
      directStreamSaveResult: (t, s, ok, msg) => reports.push({ s, ok, msg, legacy: true })
    },
    _lastRenderedMovieDoc: { moviesmodSubtitleOffsetMs: 0, seriesAppSources: { s1e2: { vsm: { enabled: true, label: 'X', subtitleOffsetMs: 0 } } } },
    _mdocPut() {}
  };
  c.window = c; c.globalThis = c;
  return { c, writes, reports };
}
function load(c, file) {
  let src = fs.readFileSync(path.join(A, file), 'utf8');
  src = src.replace('let active = null;', 'let active = null; window.__setActive_' + file.replace(/\W/g, '') + ' = v => { active = v; };');
  vm.runInContext(src, c);
}
function assert(x, m) { if (!x) throw new Error(m); }
(async () => {
  // films: every card writes only its own timing field for this film
  for (const [f, W] of [['moviesmod','Moviesmod'],['vsm','Vsm'],['vidnest','Vidnest'],['open1','Open1'],['open2','Open2']]) {
    const { c, writes, reports } = ctx(); vm.createContext(c); load(c, f + '_stream.js');
    c['__setActive_' + f + '_streamjs']({ session: 'S1', movieId: 'm1', owner: true, catalog: [], subtitleOffsetMs: 0 });
    const ok = await c['__subhub' + W + 'SyncPinNow']('S1', 2300.4);
    assert(ok === true, f + ' ok');
    assert(writes.length === 1 && writes[0].id === 'm1' && writes[0].opt.merge === true, f + ' write');
    const keys = Object.keys(writes[0].data).sort().join(',');
    assert(keys === [f + 'SubtitleOffsetMs', f + 'UpdatedAt'].sort().join(','), f + ' only timing fields: ' + keys);
    assert(writes[0].data[f + 'SubtitleOffsetMs'] === 2300, f + ' value');
    assert(reports[0].ok && reports[0].off === 2300 && /مؤخَّرة 2.3/.test(reports[0].msg), f + ' report ' + JSON.stringify(reports[0]));
    // wrong session / not owner => no write
    await c['__subhub' + W + 'SyncPinNow']('OTHER', 100);
    c.isLoggedIn = false; await c['__subhub' + W + 'SyncPinNow']('S1', 100);
    assert(writes.length === 1, f + ' guarded');
    // clamp
    c.isLoggedIn = true; await c['__subhub' + W + 'SyncPinNow']('S1', 9e9);
    assert(writes[1].data[f + 'SubtitleOffsetMs'] === 600000, f + ' clamp');
  }
  // series: episode-scoped write, film handler untouched for other sessions
  {
    const { c, writes, reports } = ctx(); vm.createContext(c);
    load(c, 'vsm_stream.js'); load(c, 'series_stream.js');
    c.__setActive_series_streamjs({ session: 'E1', mode: 'vsm', owner: true, movieId: 'm1', season: 1, episode: 2, catalog: [], pending: null });
    const ok = await c.__subhubVsmSyncPinNow('E1', -1500);
    assert(ok, 'series ok');
    assert(JSON.stringify(writes[0].data) .includes('"s1e2":{"vsm":{"subtitleOffsetMs":-1500'), 'series path ' + JSON.stringify(writes[0].data));
    assert(c._lastRenderedMovieDoc.seriesAppSources.s1e2.vsm.label === 'X' && c._lastRenderedMovieDoc.seriesAppSources.s1e2.vsm.subtitleOffsetMs === -1500, 'series cache keeps server');
    assert(/الحلقة 2/.test(reports[0].msg) && /مقدَّمة 1.5/.test(reports[0].msg), 'series msg');
    // a film session falls through to the film handler
    c.__setActive_vsm_streamjs({ session: 'F1', movieId: 'm1', owner: true, catalog: [] });
    await c.__subhubVsmSyncPinNow('F1', 500);
    assert(writes[1].data.vsmSubtitleOffsetMs === 500, 'delegates to film');
  }
  console.log('sync pin JS OK');
})().catch(e => { console.error(e); process.exit(1); });
