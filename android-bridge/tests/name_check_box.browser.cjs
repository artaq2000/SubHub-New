// 322.3.122: «🩺 فحص» in «📦 أسماء fbox» (assets/app_list_stream.js) in Chromium, with
// the app bridge and the site faked: check all on the film's page, live rows while
// the answers come (typing a new name is never interrupted), 451 kept apart with its
// note, «↪️ يحوّل إلى» + «➕ أضِفه» (added and checked at once), a new name checked by
// itself, stop, late answers of an old run ignored, answers kept on the device, and
// an old app without the bridge method told to update.
const {chromium} = require('playwright');
const fs = require('fs'), path = require('path'), assert = require('assert');
const script = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/app_list_stream.js'), 'utf8');

const STUBS = `
var currentMovie = {id: 'm1', title: 'Animals'};
var isLoggedIn = true;
window.toasts = []; window.calls = []; window.sets = [];
function showToast(t, k) { window.toasts.push(t); }
async function checkOwnerAccess() { return true; }
window._lastRenderedMovieDoc = {id: 'm1', title: 'Animals', contentType: 'movie',
  fboxUrl: 'https://fbox.gd/watch/movie-animals-li2ws86x', appServers: []};
window.__names = ['moviesjoy.sx', 'dopebox.sx', 'watch32.gd'];
var db = {collection: function (c) { return {doc: function (id) { return {
  get: async function () {
    if (id === '_appSettings') return {exists: true, data: function () { return {fboxDomains: window.__names.slice()}; }};
    return {exists: true, data: function () { return {appServers: [], fboxUrl: window._lastRenderedMovieDoc.fboxUrl}; }};
  },
  set: async function (o) { window.sets.push([id, JSON.parse(JSON.stringify(o))]); if (o.fboxDomains) window.__names = o.fboxDomains.slice(); }
}; }}; }};
window.SubHubAndroidBridge = {
  nameCheckStart: function (t, raw) { window.calls.push(['start', t, JSON.parse(raw)]); },
  nameCheckStop: function (t) { window.calls.push(['stop', t]); }
};
window.send = function (e) { window.__subhubNameCheckV3122(JSON.stringify(e)); };
`;

(async () => {
  const browser = await chromium.launch({headless: true, executablePath: process.env.CHROME_PATH || undefined});
  const page = await browser.newPage({viewport: {width: 390, height: 844}});
  page.on('pageerror', e => { console.error('page error:', e); process.exitCode = 1; });
  await page.route('https://subhub.test/**', r => r.fulfill({status: 200, contentType: 'text/html; charset=utf-8',
    body: '<!doctype html><html dir="rtl"><body><div class="owner-watch-pills"></div></body></html>'}));
  await page.goto('https://subhub.test/');
  await page.addScriptTag({content: STUBS});
  await page.addScriptTag({content: script});
  const box = '#subhub-fbox-names-box';
  await page.waitForSelector('#subhub-applist-section');
  // open the names box
  await page.locator('#subhub-applist-section button', {hasText: '✏️ الأسماء'}).click();
  await page.waitForSelector(box);
  await page.waitForFunction(() => document.querySelectorAll('[data-ncheck-host]').length === 3);
  assert.equal(await page.locator('[data-ncheck-all]').innerText(), '🩺 فحص الكل');
  assert.equal(await page.locator('[data-ncheck-one]').count(), 3, 'a 🩺 on every name');
  assert.match(await page.locator('[data-ncheck-head]').innerText(), /افحص الأسماء/);

  const input = page.locator(box + ' input');
  // check all: on this film's page, every name, in the owner's order
  await page.locator('[data-ncheck-all]').click();
  // the owner is typing a new name while the check runs: never interrupted
  await input.click();
  await input.type('tinyzone');
  let calls = await page.evaluate(() => window.calls);
  assert.equal(calls.length, 1);
  assert.equal(calls[0][0], 'start');
  assert.deepEqual(calls[0][2].names, ['moviesjoy.sx', 'dopebox.sx', 'watch32.gd']);
  assert.equal(calls[0][2].path, '/watch/movie-animals-li2ws86x', "the film's fbox page");
  const run = calls[0][2].run;
  assert.equal(await page.locator('[data-ncheck-all]').innerText(), '⏹ إيقاف الفحص');
  const rowText = h => page.locator(`[data-ncheck-host="${h}"]`).innerText();
  await page.evaluate(r => { send({type: 'start', run: r, total: 3, path: '/watch/movie-animals-li2ws86x', net: 'wifi'});
    send({type: 'name', run: r, host: 'moviesjoy.sx', index: 0}); }, run);
  assert.match(await rowText('moviesjoy.sx'), /جارٍ الفحص/);
  assert.match(await rowText('dopebox.sx'), /في الانتظار/);
  assert.match(await page.locator('[data-ncheck-head]').innerText(), /جارٍ الفحص ١ من ٣ — على صفحة «Animals» · واي فاي/);
  assert.equal(await page.locator('[data-ncheck-one]').first().isDisabled(), true, 'one-name checks wait for the run');
  await page.evaluate(r => {
    send({type: 'result', run: r, host: 'moviesjoy.sx', index: 0, state: 'blocked', code: 451, err: 0, servers: 0, links: 0,
      to: '', colo: 'LHR', loc: 'SA', uk: true, ipv6: true});
    send({type: 'name', run: r, host: 'dopebox.sx', index: 1});
    send({type: 'result', run: r, host: 'dopebox.sx', index: 1, state: 'ok', code: 0, err: 0, servers: 41, links: 12, to: '', colo: '', loc: ''});
    send({type: 'name', run: r, host: 'watch32.gd', index: 2});
    send({type: 'result', run: r, host: 'watch32.gd', index: 2, state: 'redirect', code: 0, err: 0, servers: 0, links: 0, to: 'watch32.to'});
  }, run);
  assert.match(await rowText('moviesjoy.sx'), /محجوب على اتصالك \(451\) — يمرّ عبر بريطانيا/);
  assert.match(await rowText('moviesjoy.sx'), /LHR · IPv6/);
  assert.match(await rowText('dopebox.sx'), /✅ يعمل — السيرفرات: ٤١/);
  assert.match(await rowText('watch32.gd'), /يحوّل إلى watch32\.to/);
  assert.equal(await page.locator('[data-ncheck-note]').isVisible(), true, 'the 451 note shows');
  // typing went on untouched
  assert.equal(await input.evaluate(e => e === document.activeElement), true, 'the input kept its focus');
  assert.equal(await input.inputValue(), 'tinyzone');
  await page.evaluate(r => send({type: 'done', run: r, stopped: false}), run);
  assert.equal(await page.locator('[data-ncheck-all]').innerText(), '🩺 فحص الكل');
  assert.match(await page.locator('[data-ncheck-head]').innerText(), /آخر فحص: الآن — على صفحة «Animals» · واي فاي/);
  const toasts = await page.evaluate(() => window.toasts);
  assert.equal(toasts[toasts.length - 1], 'انتهى الفحص: ١ يعمل · ١ محجوب عندك · ١ يحتاج نظرة');
  // late answers of an old run change nothing
  await page.evaluate(r => send({type: 'result', run: r, host: 'dopebox.sx', state: 'down', code: 522}), run);
  await page.evaluate(() => send({type: 'result', run: 'other', host: 'dopebox.sx', state: 'down', code: 522}));
  assert.match(await rowText('dopebox.sx'), /✅ يعمل/);
  // kept on this device
  const kept = await page.evaluate(() => JSON.parse(localStorage.getItem('subhub_fbox_check_v3122')));
  assert.equal(kept.results['moviesjoy.sx'].state, 'blocked');
  assert.equal(kept.results['moviesjoy.sx'].colo, 'LHR');
  assert.equal(kept.results['dopebox.sx'].servers, 41);
  assert.equal(kept.meta.net, 'wifi');

  // «➕ أضِفه» on the moved name: added to the list, then checked by itself
  await page.locator(`[data-ncheck-host="watch32.gd"] button`, {hasText: 'أضِفه'}).click();
  await page.waitForFunction(() => window.calls.length === 2);
  let sets = await page.evaluate(() => window.sets);
  assert.deepEqual(sets[sets.length - 1][1].fboxDomains, ['moviesjoy.sx', 'dopebox.sx', 'watch32.gd', 'watch32.to']);
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls[1][2].names, ['watch32.to'], 'the added name is checked at once');
  const run2 = calls[1][2].run;
  assert.notEqual(run2, run);
  await page.waitForFunction(() => !!document.querySelector('[data-ncheck-host="watch32.to"]'));
  assert.match(await rowText('watch32.gd'), /موجود في القائمة/);
  assert.equal(await page.locator(`[data-ncheck-host="watch32.gd"] button`, {hasText: 'أضِفه'}).count(), 0);
  // stop it
  await page.locator('[data-ncheck-all]').click();
  calls = await page.evaluate(() => window.calls);
  assert.equal(calls[2][0], 'stop');
  assert.match(await page.locator('[data-ncheck-head]').innerText(), /\(أُوقف\)/);
  await page.evaluate(r => send({type: 'result', run: r, host: 'watch32.to', state: 'ok', servers: 9}), run2);
  assert.equal(await page.locator('[data-ncheck-host="watch32.to"]').isVisible(), false, 'nothing after the stop');

  // a name typed and added is checked by itself
  await input.fill('tinyzone.sx');
  await page.locator(box + ' button', {hasText: '➕ إضافة'}).click();
  await page.waitForFunction(() => window.calls.length === 4);
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls[3][2].names, ['tinyzone.sx']);
  await page.evaluate(r => { send({type: 'name', run: r, host: 'tinyzone.sx', index: 0});
    send({type: 'result', run: r, host: 'tinyzone.sx', state: 'noAnswer', err: -2});
    send({type: 'done', run: r, stopped: false}); }, calls[3][2].run);
  assert.match(await rowText('tinyzone.sx'), /لا يرد — الاسم غير معروف \(DNS\)/);

  // one name by its 🩺
  await page.locator('[data-ncheck-host="dopebox.sx"]').locator('xpath=..').locator('[data-ncheck-one]').click();
  calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls[4][2].names, ['dopebox.sx']);
  await page.evaluate(r => send({type: 'done', run: r, stopped: true}), calls[4][2].run);

  // the front page when the film has no fbox page
  await page.evaluate(() => { window._lastRenderedMovieDoc.fboxUrl = ''; });
  await page.locator('[data-ncheck-all]').click();
  calls = await page.evaluate(() => window.calls);
  assert.equal(calls[5][2].path, '/', 'no film page: their front page');
  await page.evaluate(r => send({type: 'done', run: r, stopped: true}), calls[5][2].run);

  // an app without the check: told to update, nothing breaks
  await page.evaluate(() => { delete window.SubHubAndroidBridge.nameCheckStart; });
  await page.locator('[data-ncheck-all]').click();
  const t2 = await page.evaluate(() => window.toasts);
  assert.equal(t2[t2.length - 1], 'الفحص يحتاج تطبيق SubHub بنسخة 322.3.122 أو أحدث.');
  assert.equal(await page.locator('[data-ncheck-all]').innerText(), '🩺 فحص الكل');
  if (process.exitCode) throw new Error('page errors');
  console.log('Chromium: names check box (check all on the film page, live rows, typing kept, 451 apart with note, moved name added and checked, new name checked, one name, stop, old runs ignored, kept on device, front page, old app): PASS');
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
