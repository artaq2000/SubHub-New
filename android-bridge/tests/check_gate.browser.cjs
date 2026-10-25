// 322.3.124: the site's check («Just a quick check — Continue to StreamBoppo») and the viewer.
// After the viewer's «✓ تحقّق الآن» the film page was shown to him with its ads, and no video
// came: the site takes its check away in the SAME page (no new page loads, so no new report
// came), so 7 s later the app «showed the check» — which by then was the film page — and the
// search stood still; and the saved server had been pressed under the check (ignored by the
// site) and was never pressed again. In Chromium, on pages shaped like the real ones:
//   - the server is never pressed under the check, and its tries do not run out meanwhile;
//     the moment the check goes (in the same page, or the page redrawn), «gone» is reported
//     and the saved server is pressed — the page switches its player to it;
//   - a check that shows late is reported (so its card comes), and its going too;
//   - the watch after the press: «gone» once the check leaves the page, not for a redraw;
//   - «is the check there?» is right even while our «only the box» style hides the rest of
//     their page, and that style goes with the check (the page is whole again);
//   - a page without a check: the server is pressed at once, no check word reported.
// The scripts are taken from DirectStreamPlayer.java itself (a Java method body is valid
// JavaScript once «String x =» reads «var x =»), so this is the real code.
const {chromium} = require('playwright');
const fs = require('fs'), path = require('path'), assert = require('assert');
const java = fs.readFileSync(path.join(__dirname, '../app/src/main/java/com/artaq/subhub/DirectStreamPlayer.java'), 'utf8');

function methodBody(sig) {
  const start = java.indexOf(sig);
  if (start < 0) throw new Error('no method ' + sig);
  const bodyStart = java.indexOf('{', start) + 1;
  const bodyEnd = java.indexOf('\n    }\n', bodyStart);
  return java.slice(bodyStart, bodyEnd).replace(/\bString\s+(\w+)\s*=/g, 'var $1 =');
}
const GATE_WORDS = JSON.parse(java.match(/GATE_WORDS =\s*("(?:[^"\\]|\\.)*");/)[1]);
const gatePresentFn = () => new Function('GATE_WORDS', methodBody('private static String gatePresentFn() {'))(GATE_WORDS);
function js(name, env) {
  const base = {JSONObject: {quote: v => JSON.stringify(String(v))}, normalizeServerLabel: v => String(v),
    GATE_WORDS, gatePresentFn, sourceChoiceToken: 'T0K'};
  const all = Object.assign(base, env || {});
  return new Function(...Object.keys(all), methodBody('private String ' + name + '() {'))(...Object.values(all));
}
const picker = (wanted, openServers) => js('providerPickerScript', {
  preferredServerLabel: wanted, openServers: openServers, interactiveSource: false});

const SERVERS = ['vidsrc.mov', 'VidSrc.fyi', '⭐ VIP (No ADS)', 'VidFast', 'VidUp', 'vidrock', 'vidnest', 'vidlink',
  '111movies', 'multiembed', 'mappletv', 'moviesapi', 'Videm', 'VidSrc.sh', 'VidSrc.su'];
const GRID = '<div style="height:200px;background:#000"><iframe id="player" src="https://embed.test/vidsrc.mov" style="width:100%;height:100%"></iframe></div>' +
  '<h4>Select Server</h4><div class="servers">' + SERVERS.map(s =>
    `<button class="srv" style="margin:3px" onclick="document.getElementById('player').src='https://embed.test/' + encodeURIComponent(this.textContent.replace('⭐','').trim())">${s}</button>`).join('') +
  '</div>';
// Their check: a card on a dark backdrop over the page; «Continue» takes it away in the same page.
const CHECK = '<div id="chk" style="position:fixed;inset:0;background:rgba(0,0,0,.85);z-index:50">' +
  '<div class="card" style="width:300px;margin:120px auto;background:#123;border-radius:14px;padding:16px;color:#fff">' +
  '<h2>Just a quick check</h2><p>We make sure you\'re a real person, not a bot.</p>' +
  '<button id="go" onclick="document.getElementById(\'chk\').remove()">Continue to StreamBoppo</button></div></div>';
const page = body => '<!doctype html><html><head><meta name="viewport" content="width=device-width"></head>' +
  '<body style="margin:0;color:#ddd;background:#111">' + body + '</body></html>';
const FILM = page('<h1>Insidious: Out of the Further</h1>' + GRID);
const IN_PAGE_CHECK = page('<h1>Insidious: Out of the Further</h1>' + GRID + CHECK);
// The page itself only appears once the check is passed (the site draws it then).
const CHECK_THEN_PAGE = page('<div id="app">' + CHECK.replace('document.getElementById(\'chk\').remove()',
  'window.__draw()') + '</div><script>window.__draw=function(){document.getElementById(\'app\').innerHTML=' +
  JSON.stringify('<h1>Insidious: Out of the Further</h1>' + GRID).replace(/</g, '\\u003c') + ';};</script>');
const LATE_CHECK = page('<h1>Insidious: Out of the Further</h1>' + GRID +
  '<script>setTimeout(function(){document.body.insertAdjacentHTML("beforeend",' +
  JSON.stringify(CHECK).replace(/</g, '\\u003c') + ');},1000);</script>');

const FAKE_BRIDGE = `window.__calls = [];
  window.SubHubSourceChoice = new Proxy({}, {get: (t, k) => (...a) => window.__calls.push([k, ...a])});
  true;`;

(async () => {
  const browser = await chromium.launch({headless: true, executablePath: process.env.CHROME_PATH || undefined});
  const ctx = await browser.newContext({viewport: {width: 390, height: 844}});
  const tab = await ctx.newPage();
  tab.on('pageerror', e => { console.error('page error:', e); process.exitCode = 1; });
  let html = '';
  await tab.route('**/*', r => r.request().resourceType() === 'document'
    ? r.fulfill({status: 200, contentType: 'text/html; charset=utf-8', body: html})
    : r.fulfill({status: 204, body: ''}));
  async function open(p) { html = p; await tab.goto('https://streamboppo.cyou/movie/1197137'); await tab.evaluate(FAKE_BRIDGE); }
  const calls = () => tab.evaluate(() => window.__calls);
  const of = (c, k) => c.filter(x => x[0] === k);
  const gates = async () => of(await calls(), 'gate').map(x => x[2]);
  const until = (fn, ms) => tab.waitForFunction(fn, null, {timeout: ms || 4000});

  for (const openServers of [true, false]) {
    // --- the check over the film page: nothing pressed under it, then the server at once
    await open(IN_PAGE_CHECK);
    await tab.evaluate(picker('VidUp', openServers));
    await tab.waitForTimeout(1800);
    let c = await calls();
    assert.deepEqual(of(c, 'gate').map(x => x.slice(1)), [['T0K', true]], 'the check is reported, once');
    assert.equal(of(c, 'picked').length, 0, 'no server pressed under the check');
    assert.equal(of(c, 'servers').length, 0, 'a grid under the check does not count');
    assert.equal(await tab.evaluate(() => document.getElementById('player').src), 'https://embed.test/vidsrc.mov');
    await tab.click('#go');                                   // the viewer passes it (in the same page)
    await until(() => window.__calls.some(c => c[0] === 'resolved'));
    c = await calls();
    assert.deepEqual(await gates(), [true, false], 'then «gone»');
    assert.deepEqual(of(c, 'picked')[0], ['picked', 'T0K', 'VidUp'], 'the saved server is pressed right after');
    assert.equal(of(c, 'resolved')[0][3], 'https://embed.test/VidUp', 'and the page switched its player to it');

    // --- the page drawn only after the check: the same
    await open(CHECK_THEN_PAGE);
    await tab.evaluate(picker('VidUp', openServers));
    await tab.waitForTimeout(1200);
    assert.deepEqual(await gates(), [true]);
    assert.equal(of(await calls(), 'picked').length, 0);
    await tab.click('#go');
    await until(() => window.__calls.some(c => c[0] === 'resolved'));
    c = await calls();
    assert.deepEqual(await gates(), [true, false]);
    assert.equal(of(c, 'resolved')[0][3], 'https://embed.test/VidUp', 'pressed on the page drawn after the check');
  }

  // --- the waiting uses up no tries: two minutes on the check, still pressed afterwards
  await open(IN_PAGE_CHECK);
  await tab.clock.install();
  await tab.evaluate(picker('VidUp', true));
  await tab.clock.runFor(150000);                             // 150 s on the check (the tries are 240 × 0.5 s)
  assert.equal(of(await calls(), 'picked').length, 0);
  await tab.evaluate(() => document.getElementById('chk').remove());
  await tab.clock.runFor(1500);
  assert.equal(of(await calls(), 'picked').length, 1, 'still pressed after a long check');
  await ctx.clearCookies();
  // (a fresh tab for the real clock)
  const tab2 = await ctx.newPage();
  tab2.on('pageerror', e => { console.error('page error:', e); process.exitCode = 1; });
  await tab.close();
  const T = tab2;
  await T.route('**/*', r => r.request().resourceType() === 'document'
    ? r.fulfill({status: 200, contentType: 'text/html; charset=utf-8', body: html})
    : r.fulfill({status: 204, body: ''}));
  async function open2(p) { html = p; await T.goto('https://streamboppo.cyou/movie/1197137'); await T.evaluate(FAKE_BRIDGE); }
  const calls2 = () => T.evaluate(() => window.__calls);
  const gates2 = async () => of(await calls2(), 'gate').map(x => x[2]);

  // --- a check that shows late: reported when it comes, and when it goes
  await open2(LATE_CHECK);
  await T.evaluate(picker('nosuchserver', true));
  await T.waitForTimeout(600);
  assert.deepEqual(await gates2(), [], 'no check yet, nothing said');
  await T.waitForTimeout(1200);
  assert.deepEqual(await gates2(), [true], 'the late check is reported (its card comes)');
  await T.evaluate(() => document.getElementById('chk').remove());
  await T.waitForTimeout(800);
  assert.deepEqual(await gates2(), [true, false]);

  // --- the watch after the viewer's press
  await open2(IN_PAGE_CHECK);
  await T.evaluate(js('gateWatchScript'));
  await T.waitForTimeout(1300);
  assert.deepEqual(await gates2(), [], 'the check is still there: nothing');
  // a redraw (the check away for a moment) is not «gone»
  await T.evaluate(() => { const k = document.getElementById('chk'); k.style.display = 'none'; setTimeout(() => { k.style.display = ''; }, 250); });
  await T.waitForTimeout(1300);
  assert.deepEqual(await gates2(), [], 'a redraw is not taken for «gone»');
  await T.click('#go');
  await T.waitForTimeout(1300);
  assert.deepEqual(await gates2(), [false], '«gone» once the check left the page');
  await T.waitForTimeout(1000);
  assert.deepEqual(await gates2(), [false], 'said once');

  // --- «is the check there?» with our «only the box» style on their page
  const present = () => T.evaluate(js('gatePresentScript'));
  await open2(FILM);
  assert.equal(await present(), false, 'a film page: no check');
  await open2(IN_PAGE_CHECK);
  assert.equal(await present(), true, 'their check');
  await T.evaluate(js('gateOnlyScript'));
  assert.equal(await T.evaluate(() => !!document.querySelector('.card[data-subhub-gate]')), true, 'their box is kept');
  assert.equal(await T.evaluate(() => getComputedStyle(document.querySelector('.srv')).visibility), 'hidden', 'the rest hidden');
  assert.equal(await present(), true, 'still their check while only the box shows');
  await T.evaluate(() => document.getElementById('chk').remove());     // passed: the site takes it away
  assert.equal(await present(), false, 'gone, even with the rest of the page hidden by us');
  await T.evaluate(js('gateClearScript'));
  assert.equal(await T.evaluate(() => !!document.getElementById('__subhubGateOnly')), false, 'our style goes with the check');
  assert.equal(await T.evaluate(() => getComputedStyle(document.querySelector('.srv')).visibility), 'visible', 'the page is whole again');
  // their box hidden (not removed) = passed too
  await open2(IN_PAGE_CHECK);
  await T.evaluate(js('gateOnlyScript'));
  await T.evaluate(() => { document.getElementById('chk').style.display = 'none'; });
  assert.equal(await present(), false, 'their box hidden: passed');
  // their box saying «Verifying…» = still the check
  await open2(IN_PAGE_CHECK);
  await T.evaluate(js('gateOnlyScript'));
  await T.evaluate(() => { document.querySelector('.card').innerHTML = '<p>Verifying…</p>'; });
  assert.equal(await present(), true, '«Verifying…» in their box: still the check');

  // --- a page without a check: the server at once, no check word
  await open2(FILM);
  await T.evaluate(picker('VidUp', true));
  await T.waitForFunction(() => window.__calls.some(c => c[0] === 'resolved'), null, {timeout: 4000});
  assert.deepEqual(await gates2(), [], 'no check, nothing said');
  assert.equal(of(await calls2(), 'resolved')[0][3], 'https://embed.test/VidUp');

  // --- the page's own first look still says the same as before (card / no card)
  await open2(IN_PAGE_CHECK);
  await T.evaluate(js('gateCheckScript'));
  await T.waitForTimeout(700);
  assert.deepEqual(await gates2(), [true]);
  await open2(FILM);
  await T.evaluate(js('gateCheckScript'));
  await T.waitForTimeout(3300);
  assert.deepEqual(await gates2(), [false]);

  if (process.exitCode) throw new Error('page errors');
  console.log('Chromium: the site\'s check and the viewer (no server pressed under the check, tries kept, pressed right after it — in the same page or the page drawn after; a late check reported; the watch says «gone» once, not for a redraw; «is the check there?» right under our box style, the style goes with it; no check = pressed at once): PASS');
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
