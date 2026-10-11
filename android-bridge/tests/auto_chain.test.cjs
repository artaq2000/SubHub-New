// 322.3.110: «مشاهدة تلقائية» chain — order, hand-over, fallback, skip, remember.
const fs = require('fs');
const vm = require('vm');
const assert = require('assert');
const src = fs.readFileSync(__dirname + '/../app/src/main/assets/auto_chain.js', 'utf8');

function makeEnv() {
  const calls = [], arms = [], ends = [], oks = [], timers = [];
  const win = {
    currentMovie: { id: 'tt1' },
    SubHubAndroidBridge: {
      directStreamAutoArm: (t, j) => arms.push(JSON.parse(j)),
      directStreamAutoEnd: () => ends.push(1)
    },
    seriesPlayExtraV383: (sn, en, key) => calls.push(sn + ':' + en + ':' + key),
    __subhubSeriesAutoOkV400: (mid, sn, en, key) => oks.push([mid, sn, en, key])
  };
  win.window = win;
  const ctx = vm.createContext(Object.assign(win, {
    setTimeout: (fn, ms) => { timers.push({ fn, ms }); return timers.length; },
    clearTimeout: () => {},
    JSON, Math, Date, String, Number, Array
  }));
  vm.runInContext(src, ctx);
  const flush = (minMs) => { // run queued timers with delay <= minMs (or all short ones)
    let ran = true;
    while (ran) {
      ran = false;
      for (let i = 0; i < timers.length; i++) {
        const t = timers[i];
        if (!t.done && t.ms <= minMs) { t.done = true; t.fn(); ran = true; }
      }
    }
  };
  return { win, calls, arms, ends, oks, timers, flush };
}

// 1) order + hand-over + remember the one that worked
{
  const e = makeEnv();
  e.win.__subhubAutoChainV3110({ movieId: 'tt1', season: 1, episode: 2, keys: ['list:b2', 'list:b1', 'open3'], fallback: '' });
  assert.deepStrictEqual(e.calls, ['1:2:list:b2']);
  assert.deepStrictEqual(e.arms[0], { chain: e.arms[0].chain, index: 0, total: 3, more: true, expectMs: 0 });
  const id = e.arms[0].chain;
  e.win.__subhubAutoOpenedV3110(id, 0);
  e.win.__subhubAutoNextV3110(id); e.flush(200);
  assert.deepStrictEqual(e.calls, ['1:2:list:b2', '1:2:list:b1']);
  assert.strictEqual(e.arms[1].index, 1);
  e.win.__subhubAutoOpenedV3110(id, 1);
  e.win.__subhubAutoOkV3110(id);
  assert.deepStrictEqual(e.oks, [['tt1', 1, 2, 'list:b1']]);
  e.win.__subhubAutoNextV3110(id);            // chain over: only ends the cover
  assert.strictEqual(e.calls.length, 2);
  assert.ok(e.ends.length >= 1);
}

// 2) last server: more=false without fallback; with fallback the site player opens at the end
{
  const e = makeEnv();
  e.win.__subhubAutoChainV3110({ movieId: 'tt1', season: 1, episode: 1, keys: ['open3'], fallback: '' });
  assert.strictEqual(e.arms[0].more, false);
  const f = makeEnv();
  f.win.__subhubAutoChainV3110({ movieId: 'tt1', season: 1, episode: 1, keys: ['open3'], fallback: 'vidfast' });
  assert.strictEqual(f.arms[0].more, true);
  f.win.__subhubAutoNextV3110(f.arms[0].chain); f.flush(200);
  assert.deepStrictEqual(f.calls, ['1:1:open3', '1:1:vidfast']);
}

// 3) a server whose script never opens the player is skipped after the wait
{
  const e = makeEnv();
  e.win.__subhubAutoChainV3110({ movieId: 'tt1', season: 2, episode: 3, keys: ['a', 'b'], fallback: '' });
  e.flush(20000);
  assert.deepStrictEqual(e.calls, ['2:3:a', '2:3:b']);
}

// 4) cancel / another series: the chain stops
{
  const e = makeEnv();
  e.win.__subhubAutoChainV3110({ movieId: 'tt1', season: 1, episode: 1, keys: ['a', 'b'], fallback: '' });
  const id = e.arms[0].chain;
  e.win.__subhubAutoCancelV3110();
  e.win.__subhubAutoNextV3110(id); e.flush(200);
  assert.deepStrictEqual(e.calls, ['1:1:a']);
  const g = makeEnv();
  g.win.__subhubAutoChainV3110({ movieId: 'tt1', season: 1, episode: 1, keys: ['a', 'b'], fallback: '' });
  g.win.currentMovie = { id: 'other' };
  g.win.__subhubAutoNextV3110(g.arms[0].chain); g.flush(200);
  assert.deepStrictEqual(g.calls, ['1:1:a']);
}
// 5) site v401: every try is recorded by the server's NAME (ok + time, or failure)
{
  const e = makeEnv();
  const stats = [];
  e.win.__subhubServerStatV401 = (mid, name, ok, ms) => stats.push([mid, name, ok, typeof ms === 'number']);
  e.win.__subhubAutoChainV3110({ movieId: 'tt1', season: 1, episode: 4, keys: ['list:x', 'open3'], names: ['VidCore', 'APIPlayer'], fallback: '' });
  const id = e.arms[0].chain;
  e.win.__subhubAutoOpenedV3110(id, 0);
  e.win.__subhubAutoNextV3110(id); e.flush(200);          // VidCore failed
  e.win.__subhubAutoOpenedV3110(id, 1);
  e.win.__subhubAutoOkV3110(id);                           // APIPlayer played
  assert.deepStrictEqual(stats, [['tt1', 'VidCore', false, true], ['tt1', 'APIPlayer', true, true]]);
  assert.deepStrictEqual(e.oks, []);                       // the old episode memory is not needed
}
// 6) 322.3.112: each server's usual start time goes to the player
{
  const e = makeEnv();
  e.win.__subhubAutoChainV3110({ movieId: 'tt1', season: 1, episode: 1, keys: ['a', 'b'], names: ['A', 'B'], ms: [12000, 'x'], fallback: '' });
  assert.strictEqual(e.arms[0].expectMs, 12000);
  e.win.__subhubAutoOpenedV3110(e.arms[0].chain, 0);
  e.win.__subhubAutoNextV3110(e.arms[0].chain); e.flush(200);
  assert.strictEqual(e.arms[1].expectMs, 0);
}

(async function () {
  const tick = () => new Promise(r => setImmediate(r));
  // 7) 322.3.112: our own files come first (own:<slot>); one that cannot open is skipped at once
  {
    const e = makeEnv();
    const own = [];
    e.win.SubHubSeriesOwnSourceV3112 = (raw) => { own.push(raw.slot + '@' + raw.season + ':' + raw.episode); return Promise.resolve(raw.slot === 'free'); };
    e.win.__subhubAutoChainV3110({ movieId: 'tt1', season: 1, episode: 5, keys: ['own:r2', 'own:free', 'list:x'], names: ['SubHub VIP', 'SubHub', 'APIPlayer'], fallback: '' });
    await tick(); e.flush(200); await tick(); e.flush(200);
    assert.deepStrictEqual(own, ['r2@1:5', 'free@1:5']);       // VIP refused → the free file at once
    assert.deepStrictEqual(e.calls, []);                       // no app server yet
    assert.strictEqual(e.arms[1].index, 1);
    e.win.__subhubAutoOpenedV3110(e.arms[1].chain, 1);
    e.win.__subhubAutoNextV3110(e.arms[1].chain); e.flush(200);  // our file did not play → next
    assert.deepStrictEqual(e.calls, ['1:5:list:x']);
  }
  // 8) older app (no own function): own keys are passed over
  {
    const e = makeEnv();
    e.win.__subhubAutoChainV3110({ movieId: 'tt1', season: 1, episode: 6, keys: ['own:free', 'list:y'], fallback: '' });
    e.flush(200);
    assert.deepStrictEqual(e.calls, ['1:6:list:y']);
  }
  // 9) «الحلقة التالية»: select the episode, then «مشاهدة تلقائية» for it
  {
    const e = makeEnv();
    const sel = [];
    e.win.seriesSelectEpisodeV403 = (sn, en) => sel.push(sn + ':' + en);
    e.win.seriesAutoWatchV400 = (sn, en) => e.win.__subhubAutoChainV3110({ movieId: 'tt1', season: sn, episode: en, keys: ['list:z'], fallback: '' });
    assert.strictEqual(e.win.__subhubNextEpisodeV3112('tt1', 2, 1), true);
    assert.deepStrictEqual(sel, ['2:1']);
    e.flush(500);
    assert.deepStrictEqual(e.calls, ['2:1:list:z']);
    assert.strictEqual(e.ends.length, 0);                      // the black screen stays for the search
    // nothing to play → the black screen ends
    const f = makeEnv();
    f.win.seriesAutoWatchV400 = () => {};
    f.win.__subhubNextEpisodeV3112('tt1', 1, 3); f.flush(500);
    assert.strictEqual(f.ends.length, 1);
    // another title open → no search
    const g = makeEnv();
    assert.strictEqual(g.win.__subhubNextEpisodeV3112('other', 1, 3), false);
    assert.strictEqual(g.ends.length, 1);
  }
  console.log('auto chain: order, hand-over, remember (by name), fallback, skip, cancel, own files first, usual time, next episode — OK');
})().catch(e => { console.error(e); process.exit(1); });
