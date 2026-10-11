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
  assert.deepStrictEqual(e.arms[0], { chain: e.arms[0].chain, index: 0, total: 3, more: true });
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
console.log('auto chain: order, hand-over, remember (by name), fallback, skip, cancel — OK');
