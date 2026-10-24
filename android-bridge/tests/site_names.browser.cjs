// 322.3.123: Moviesmod under several names in «سيرفرات التطبيق» (assets/app_list_stream.js),
// in Chromium with the app bridge and the site faked:
//   viewer — a saved Moviesmod server (moviesmod.gd/movie/1234) opens on the chosen name
//     with the next names after it; a name that failed goes last; a name that worked comes
//     first (while the owner's ✓ is the same); a name the site said it left is replaced by
//     its new home; the owner's «⚙️» taps go with the server;
//   owner — «🎬 أسماء Moviesmod» box: add (kept by the app too), a name of the other family
//     refused, «🩺 فحص» on the film's Moviesmod page, «🔁 استبدله» in place (the chosen ✓
//     stays chosen); «➕ إضافة سيرفر من Moviesmod» opens exactly the chosen name; «⚙️»
//     saves the taps for the server name and shows them on the row.
const {chromium} = require('playwright');
const fs = require('fs'), path = require('path'), assert = require('assert');
const script = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/app_list_stream.js'), 'utf8');

const STUBS = owner => `
var currentMovie = {id: 'tmdb_movie_1234', title: 'Resident Evil', year: '2026'};
var isLoggedIn = ${owner ? 'true' : 'false'};
window.toasts = []; window.opens = []; window.pushed = []; window.calls = []; window.sets = [];
function showToast(t, k) { window.toasts.push(t); }
async function checkOwnerAccess() { return true; }
function _buildSubtitleTrackCatalog() { return []; }
var firebase = {firestore: {FieldValue: {delete: function () { return '__delete__'; }}}};
window.__settings = {fboxDomains: ['flixframe.cyou', 'dopebox.sx'],
  moviesmodDomains: ['streamboppo.cyou', 'streamdude.cyou', 'moviesmod.gd'],
  serverRules: {'vip-no-ads': {taps: 2, gap: 3}}};
window.__items = [{id: 'a1', label: 'VIP (No ADS)', key: 'vip-no-ads', pageUrl: 'https://moviesmod.gd/movie/1234',
  embedUrl: '', subtitleOffsetMs: 0, createdAt: 1, updatedAt: 1}];
window._lastRenderedMovieDoc = {id: 'tmdb_movie_1234', title: 'Resident Evil', contentType: 'movie', appServers: window.__items};
var db = {collection: function (c) { return {doc: function (id) { return {
  get: async function () {
    if (id === '_appSettings') return {exists: true, data: function () { return JSON.parse(JSON.stringify(window.__settings)); }};
    return {exists: true, data: function () { return {appServers: JSON.parse(JSON.stringify(window.__items))}; }};
  },
  set: async function (o) {
    window.sets.push([id, JSON.parse(JSON.stringify(o))]);
    if (id === '_appSettings') {
      if (o.fboxDomains) window.__settings.fboxDomains = o.fboxDomains.slice();
      if (o.moviesmodDomains) window.__settings.moviesmodDomains = o.moviesmodDomains.slice();
      if (o.serverRules) Object.keys(o.serverRules).forEach(function (k) {
        if (o.serverRules[k] === '__delete__') delete window.__settings.serverRules[k];
        else window.__settings.serverRules[k] = o.serverRules[k];
      });
    }
  }
}; }}; }};
window.SubHubAndroidBridge = {
  openDirectStream: function (t, raw) { window.opens.push(JSON.parse(raw)); },
  siteNamesV3123: function (t, raw) { window.pushed.push(JSON.parse(raw)); },
  nameCheckStart: function (t, raw) { window.calls.push(['start', JSON.parse(raw)]); },
  nameCheckStop: function () { window.calls.push(['stop']); }
};
window.send = function (e) { window.__subhubNameCheckV3122(JSON.stringify(e)); };
`;
const PAGE = '<!doctype html><html dir="rtl"><body><div class="watch-pills"></div><div class="owner-watch-pills"></div></body></html>';

async function setup(browser, owner) {
  const page = await browser.newPage({viewport: {width: 390, height: 844}});
  page.on('pageerror', e => { console.error('page error:', e); process.exitCode = 1; });
  await page.route('https://subhub.test/**', r => r.fulfill({status: 200, contentType: 'text/html; charset=utf-8', body: PAGE}));
  await page.goto('https://subhub.test/');
  await page.addScriptTag({content: STUBS(owner)});
  await page.addScriptTag({content: script});
  return page;
}

(async () => {
  const browser = await chromium.launch({headless: true, executablePath: process.env.CHROME_PATH || undefined});

  // ---------------------------------------------------------------- viewer
  let page = await setup(browser, false);
  await page.waitForSelector('.subhub-applist-btn');
  async function play() {
    const n = await page.evaluate(() => window.opens.length);
    await page.locator('.subhub-applist-btn').click();
    await page.waitForFunction(k => window.opens.length > k, n);
    const last = await page.evaluate(() => window.opens[window.opens.length - 1]);
    await page.evaluate(s => window.__subhubDirectClosed(s), last.session);   // the player closed
    return last;
  }
  let o = await play();
  assert.equal(o.site, 'moviesmod');
  assert.equal(o.serverPageUrl, 'https://streamboppo.cyou/movie/1234', 'the saved page on the chosen name');
  assert.deepEqual(o.mmHosts, ['streamdude.cyou', 'moviesmod.gd'], 'the next names, in order');
  assert.deepEqual(o.tapRule, {taps: 2, gapMs: 3000}, "the owner's «⚙️» taps go with the server");
  assert.equal(o.serverLabel, 'VIP (No ADS)');
  assert.equal(o.interactiveSource, false);
  let pushed = await page.evaluate(() => window.pushed);
  assert.deepEqual(pushed[pushed.length - 1].mm, ['streamboppo.cyou', 'streamdude.cyou', 'moviesmod.gd'], 'the app keeps the names too');
  // the chosen name failed on this device → last
  await page.evaluate(() => window.__subhubSiteHostV3123('mm', 'streamboppo.cyou', false));
  o = await play();
  assert.equal(o.serverPageUrl, 'https://streamdude.cyou/movie/1234');
  assert.deepEqual(o.mmHosts, ['moviesmod.gd', 'streamboppo.cyou']);
  // the old cards' hook still marks fbox names only
  await page.evaluate(() => window.__subhubFboxHostV3120('streamdude.cyou', false));
  o = await play();
  assert.equal(o.serverPageUrl, 'https://streamdude.cyou/movie/1234', 'an fbox report never touches Moviesmod names');
  // a name that opened forgets its failure; the name that opened LAST is used first
  await page.evaluate(() => { window.__subhubSiteHostV3123('mm', 'streamboppo.cyou', true);
    window.__subhubSiteHostV3123('mm', 'streamdude.cyou', true); });
  o = await play();
  assert.equal(o.serverPageUrl, 'https://streamdude.cyou/movie/1234', 'the name that worked comes first');
  assert.deepEqual(o.mmHosts, ['streamboppo.cyou', 'moviesmod.gd']);
  // the site said it moved: its new home takes its place
  await page.evaluate(() => window.__subhubSiteMovedV3123('mm', 'streamdude.cyou', 'streamnew.cyou'));
  o = await play();
  assert.equal(o.serverPageUrl, 'https://streamnew.cyou/movie/1234', 'the new home of the name that worked');
  assert.deepEqual(o.mmHosts, ['streamboppo.cyou', 'moviesmod.gd']);
  // the owner chose another ✓: what this device remembered for the old one no longer leads
  await page.close();
  page = await setup(browser, false);
  await page.waitForSelector('.subhub-applist-btn');
  await page.evaluate(() => {
    window.__settings.moviesmodDomains = ['moviesmod.gd', 'streamboppo.cyou'];
    localStorage.setItem('subhub_site_good_v3123', JSON.stringify({mm: {host: 'streamboppo.cyou', chosen: 'streamdude.cyou', at: Date.now()}}));
  });
  o = await play();
  assert.equal(o.serverPageUrl, 'https://moviesmod.gd/movie/1234', "the owner's new ✓ wins over an old memory");
  await page.close();

  // ---------------------------------------------------------------- owner
  page = await setup(browser, true);
  await page.waitForSelector('#subhub-applist-section');
  await page.evaluate(() => { window.__settings.moviesmodDomains = ['streamboppo.cyou', 'streamdude.cyou', 'moviesmod.gd']; });
  const sec = '#subhub-applist-section';
  assert.match(await page.locator(sec).innerText(), /🎬 Moviesmod — المختار: /);
  await page.locator(sec + ' button[data-names-fam="mm"]').click();
  const box = '#subhub-mm-names-box';
  await page.waitForSelector(box);
  await page.waitForFunction(() => document.querySelectorAll('#subhub-mm-names-box [data-ncheck-host]').length === 3);
  assert.match(await page.locator(box).innerText(), /🎬 أسماء Moviesmod/);
  assert.equal(await page.locator('#subhub-fbox-names-box').count(), 0, 'one box at a time');
  // add a name: saved and kept by the app
  const input = page.locator(box + ' input');
  await input.fill('streamzippy.cyou');
  await page.locator(box + ' button', {hasText: '➕ إضافة'}).click();
  await page.waitForFunction(() => window.sets.some(s => s[1].moviesmodDomains));
  let sets = await page.evaluate(() => window.sets);
  assert.deepEqual(sets[sets.length - 1][1].moviesmodDomains, ['streamboppo.cyou', 'streamdude.cyou', 'moviesmod.gd', 'streamzippy.cyou']);
  pushed = await page.evaluate(() => window.pushed);
  assert.deepEqual(pushed[pushed.length - 1].mm, ['streamboppo.cyou', 'streamdude.cyou', 'moviesmod.gd', 'streamzippy.cyou']);
  let calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls[calls.length - 1][1].names, ['streamzippy.cyou'], 'a new name is checked at once');
  await page.evaluate(r => send({type: 'done', run: r, stopped: false}), calls[calls.length - 1][1].run);
  // a name of the other family is refused
  await input.fill('dopebox.sx');
  await page.locator(box + ' button', {hasText: '➕ إضافة'}).click();
  const toasts = await page.evaluate(() => window.toasts);
  assert.equal(toasts[toasts.length - 1], '«dopebox.sx» في أسماء fbox — احذفه من هناك أولاً');
  // check all: on the film's Moviesmod page
  await page.locator(box + ' [data-ncheck-all]').click();
  calls = await page.evaluate(() => window.calls);
  const run = calls[calls.length - 1][1];
  assert.equal(run.path, '/movie/1234', "the film's Moviesmod page");
  assert.deepEqual(run.names, ['streamboppo.cyou', 'streamdude.cyou', 'moviesmod.gd', 'streamzippy.cyou']);
  await page.evaluate(r => {
    send({type: 'result', run: r, host: 'streamboppo.cyou', state: 'redirect', to: 'streamxyz.cyou'});
    send({type: 'result', run: r, host: 'streamdude.cyou', state: 'ok', servers: 45});
    send({type: 'result', run: r, host: 'moviesmod.gd', state: 'redirect', to: 'streamdude.cyou'});
    send({type: 'result', run: r, host: 'streamzippy.cyou', state: 'notSite'});
    send({type: 'done', run: r, stopped: false});
  }, run.run);
  const rowText = h => page.locator(`${box} [data-ncheck-host="${h}"]`).innerText();
  assert.match(await rowText('streamboppo.cyou'), /انتقل إلى streamxyz\.cyou/);
  assert.match(await rowText('moviesmod.gd'), /موجود في القائمة/);
  assert.match(await rowText('streamdude.cyou'), /✅ يعمل — السيرفرات: ٤٥/);
  const t2 = await page.evaluate(() => window.toasts);
  assert.equal(t2[t2.length - 1], 'انتهى الفحص: ١ يعمل · ٢ انتقل · ١ لا يعمل');
  // the fbox answers are kept apart
  assert.equal(await page.evaluate(() => localStorage.getItem('subhub_fbox_check_v3122')), null);
  // «🔁 استبدله» on the chosen name: the new name in its place (still the chosen one)
  await page.locator(`${box} [data-ncheck-host="streamboppo.cyou"] button`, {hasText: 'استبدله'}).click();
  await page.waitForFunction(() => window.sets[window.sets.length - 1][1].moviesmodDomains
    && window.sets[window.sets.length - 1][1].moviesmodDomains[0] === 'streamxyz.cyou');
  sets = await page.evaluate(() => window.sets);
  assert.deepEqual(sets[sets.length - 1][1].moviesmodDomains, ['streamxyz.cyou', 'streamdude.cyou', 'moviesmod.gd', 'streamzippy.cyou']);
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls[calls.length - 1][1].names, ['streamxyz.cyou'], 'the new name is checked at once');
  await page.evaluate(r => send({type: 'done', run: r, stopped: true}), calls[calls.length - 1][1].run);
  // «🗑️ احذفه» on a name whose new home is already listed: it just goes
  await page.locator(`${box} [data-ncheck-host="moviesmod.gd"] button`, {hasText: 'احذفه'}).click();
  await page.waitForFunction(() => window.sets[window.sets.length - 1][1].moviesmodDomains
    && window.sets[window.sets.length - 1][1].moviesmodDomains.length === 3);
  sets = await page.evaluate(() => window.sets);
  assert.deepEqual(sets[sets.length - 1][1].moviesmodDomains, ['streamxyz.cyou', 'streamdude.cyou', 'streamzippy.cyou']);

  // a new Moviesmod server: exactly the chosen name, nothing else
  await page.locator(sec + ' button', {hasText: '➕ إضافة سيرفر من Moviesmod'}).click();
  await page.waitForFunction(() => window.opens.length === 1);
  o = await page.evaluate(() => window.opens[0]);
  assert.equal(o.site, 'moviesmod');
  assert.equal(o.serverPageUrl, 'https://streamxyz.cyou/movie/1234', 'the owner gets exactly the chosen name');
  assert.deepEqual(o.mmHosts, []);
  assert.equal(o.openChooser, false, 'no saved page: the chooser does not pop up by itself');
  assert.equal(o.tapRule, null, 'no taps while choosing');
  await page.evaluate(s => window.__subhubDirectClosed(s), o.session);

  // «⚙️» on the row: the taps for this server name, shown on the row
  await page.locator(sec + ' [data-rule-btn="a1"]').click();
  await page.waitForSelector('#subhub-applist-rule');
  await page.locator('#subhub-applist-rule [data-rule="taps"] button', {hasText: '٣'}).click();
  await page.locator('#subhub-applist-rule [data-rule="gap"] button', {hasText: '١ ث'}).click();
  await page.locator('#subhub-applist-rule button', {hasText: '💾 حفظ'}).click();
  await page.waitForFunction(() => !document.getElementById('subhub-applist-rule'));
  sets = await page.evaluate(() => window.sets);
  assert.deepEqual(sets[sets.length - 1][1].serverRules, {'vip-no-ads': {taps: 3, gap: 1}});
  await page.waitForFunction(() => /👆٣ · ١ث/.test(document.getElementById('subhub-applist-section').innerText));
  // back to the usual way: the rule is deleted
  await page.locator(sec + ' [data-rule-btn="a1"]').click();
  await page.locator('#subhub-applist-rule [data-rule="taps"] button', {hasText: 'تلقائي'}).click();
  await page.locator('#subhub-applist-rule button', {hasText: '💾 حفظ'}).click();
  await page.waitForFunction(() => !document.getElementById('subhub-applist-rule'));
  sets = await page.evaluate(() => window.sets);
  assert.deepEqual(sets[sets.length - 1][1].serverRules, {'vip-no-ads': '__delete__'});
  await page.waitForFunction(() => !/👆/.test(document.getElementById('subhub-applist-section').innerText));

  if (process.exitCode) throw new Error('page errors');
  console.log('Chromium: Moviesmod names (viewer: chosen name + next names, failed last, worked first, moved → new home, owner ✓ wins, taps passed; owner: own box, add kept by the app, other family refused, check on the film page, replace in place / drop when listed, add opens the chosen name, ⚙️ taps saved, shown and cleared): PASS');
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
