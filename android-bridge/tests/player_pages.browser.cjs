// 322.3.123: what the player does inside a site's page, in Chromium, on pages shaped like
// the real ones (FlixFrame / StreamBoppo, 25 Oct 2026):
//   - popup_close.js closes the site's own pop-up over the server buttons («The site may
//     close tomorrow — Join our Telegram channel» → «Later»), never «Join», never the
//     site's check page, never a link that leaves the page, never a box that does not float;
//   - the server chosen from the app's list is pressed INSIDE the page even when that
//     pop-up covers it (the page takes it: its player changes), and no finger tap lands on
//     the pop-up;
//   - a moved site's page lists no «servers» from its share row;
//   - the owner's «⚙️» taps: exactly N presses on play, this far apart; 0 = none; the usual
//     way without it.
// The two player scripts are taken from DirectStreamPlayer.java itself (the Java method
// body is valid JavaScript once «String x =» reads «var x =»), so this is the real code.
const {chromium} = require('playwright');
const fs = require('fs'), path = require('path'), assert = require('assert');
const asset = n => fs.readFileSync(path.join(__dirname, '../app/src/main/assets', n), 'utf8');
const java = fs.readFileSync(path.join(__dirname, '../app/src/main/java/com/artaq/subhub/DirectStreamPlayer.java'), 'utf8');
const popupClose = asset('popup_close.js');

function javaScriptOf(name, env) {
  const start = java.indexOf('private String ' + name + '() {');
  if (start < 0) throw new Error('no method ' + name);
  const bodyStart = java.indexOf('{', start) + 1;
  const bodyEnd = java.indexOf('\n    }\n', bodyStart);
  const body = java.slice(bodyStart, bodyEnd).replace(/\bString\s+(\w+)\s*=/g, 'var $1 =');
  const base = {JSONObject: {quote: v => JSON.stringify(String(v))}, normalizeServerLabel: v => String(v)};
  const all = Object.assign(base, env);
  return new Function(...Object.keys(all), body)(...Object.values(all));
}

const SERVERS = ['vidsrc.mov', 'vidsrc.fyi', '⭐ VIP (No ADS)', 'vidfast', 'vidup', 'vidrock', 'vidnest', 'vidlink',
  '111movies', 'multiembed', 'mappletv', 'moviesapi', 'vidzee', 'vidrift', 'videm', 'vidsrc-V3', 'vidsrc-V4', 'apiplayer',
  'nontongo', 'vidapi', 'vidcore-V1', 'vixsrc', 'strigil', 'cinesrc', 'embedbandit', 'vidlux', 'vidspark', 'wavembed'];
const TELEGRAM = '<div id="tg" style="position:fixed;left:0;right:0;bottom:0;height:46vh;background:#111;z-index:99">' +
  '<b>The site may close tomorrow</b><p>If that happens, the FlixFrame Telegram channel will be the only place where ' +
  'you\'ll find the new link. Join now while you\'re thinking about it.</p>' +
  '<button id="join" onclick="window.__joined=1">Join</button><button id="later" onclick="this.closest(\'#tg\').remove()">Later</button>' +
  '<button id="x" aria-label="close" onclick="this.closest(\'#tg\').remove()">×</button></div>';
const FILM = '<!doctype html><html><head><meta name="viewport" content="width=device-width"></head><body style="margin:0">' +
  '<div style="height:60vh;background:#000"><iframe id="player" src="about:blank" style="width:100%;height:100%"></iframe></div>' +
  '<h4>SERVERS</h4><div class="servers">' + SERVERS.map(s =>
    `<button class="srv" style="margin:3px" onclick="document.getElementById('player').src='https://embed.test/' + encodeURIComponent(this.textContent.trim())">${s}</button>`).join('') +
  '</div>' + TELEGRAM + '</body></html>';
const CHECK = '<!doctype html><html><body><div style="position:fixed;inset:0;background:#123">' +
  '<h2>Just a quick check</h2><p>We make sure you\'re a real person, not a bot. Join thousands of viewers.</p>' +
  '<button id="go" onclick="window.__went=1">Continue to FlixFrame</button><button id="no" onclick="window.__closedCheck=1">Later</button></div></body></html>';
const LEAVE = '<!doctype html><html><body><div style="position:fixed;bottom:0;left:0;right:0">' +
  '<p>Join our Telegram channel for the new link</p><a id="leave" href="https://t.me/x">Later</a></div>' +
  '<div><p>Telegram news</p><button id="static" onclick="window.__static=1">Close</button></div></body></html>';
const SHARE = '<!doctype html><html><body><a href="https://flixframe.cyou/watch/movie-hope-1fgfeb6a">Use the New DopeBox? Click here →</a>' +
  '<div class="share">' + ['WhatsApp', 'Telegram', 'Facebook', 'X', 'Reddit', 'Copy link', 'More…'].map(t =>
    `<button>${t}</button>`).join('') + '</div></body></html>';
const PLAYER = '<!doctype html><html><body style="margin:0"><div id="box" style="width:100vw;height:56vw;background:#000">' +
  '<button class="play-btn" aria-label="Play" style="width:80px;height:80px">▶</button></div></body></html>';

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
  async function open(page, url) { html = page; await tab.goto(url); await tab.evaluate(FAKE_BRIDGE); }
  const calls = () => tab.evaluate(() => window.__calls);

  // --- the site's pop-up: «Later», never «Join»
  await open(FILM, 'https://flixframe.cyou/watch/movie-hope-1fgfeb6a');
  assert.equal(await tab.evaluate(popupClose + ';window.__subhubPopupCloseV3123()'), 1, 'one pop-up closed');
  assert.equal(await tab.locator('#tg').count(), 0, 'the Telegram pop-up is gone');
  assert.equal(await tab.evaluate(() => window.__joined), undefined, '«Join» never pressed');
  assert.equal(await tab.evaluate(() => window.__subhubPopupCloseV3123()), 0, 'nothing more to close');
  // never the site's check page
  await open(CHECK, 'https://flixframe.cyou/');
  assert.equal(await tab.evaluate(popupClose + ';window.__subhubPopupCloseV3123()'), 0);
  assert.equal(await tab.evaluate(() => window.__closedCheck), undefined, 'the check page is left alone');
  assert.equal(await tab.evaluate(() => window.__went), undefined, '«Continue» is not pressed');
  // never a link that leaves the page, never a box that does not float
  await open(LEAVE, 'https://flixframe.cyou/');
  assert.equal(await tab.evaluate(popupClose + ';window.__subhubPopupCloseV3123()'), 0);
  assert.equal(tab.url(), 'https://flixframe.cyou/', 'still on the page');
  assert.equal(await tab.evaluate(() => window.__static), undefined, 'a box in the page is not a pop-up');

  // --- the server chosen from the app's list, under the pop-up
  const picker = label => javaScriptOf('providerPickerScript', {
    preferredServerLabel: '', sourceChoiceToken: 'T0K', openServers: true, interactiveSource: true}) +
    `;window.__subhubSelectProvider(${JSON.stringify(label)})`;
  await open(FILM, 'https://flixframe.cyou/watch/movie-hope-1fgfeb6a');
  const covered = await tab.evaluate(() => {
    const b = [...document.querySelectorAll('.srv')].find(e => e.textContent.trim() === 'vidspark');
    const r = b.getBoundingClientRect();
    const top = document.elementFromPoint(r.left + r.width / 2, Math.min(innerHeight - 1, r.top + r.height / 2));
    return !b.contains(top);
  });
  assert.equal(covered, true, 'the fixture really covers «vidspark»');
  assert.equal(await tab.evaluate(picker('vidspark')), true, 'the choice is taken');
  await tab.waitForFunction(() => window.__calls.some(c => c[0] === 'resolved'), null, {timeout: 5000});
  let c = await calls();
  assert.deepEqual(c.find(x => x[0] === 'picked'), ['picked', 'T0K', 'vidspark'], 'the page reports the server it took');
  assert.equal(c.find(x => x[0] === 'resolved')[3], 'https://embed.test/vidspark', 'the page switched its player');
  assert.equal(await tab.evaluate(() => document.getElementById('player').src), 'https://embed.test/vidspark');
  await tab.waitForTimeout(400);
  assert.equal((await calls()).filter(x => x[0] === 'autoPick').length, 0, 'no finger tap on the pop-up');
  // a name that is not on the page: «not found»
  assert.equal(await tab.evaluate(picker('nosuchserver')), false);

  // --- a moved site's page: its share row is never «servers»
  await open(SHARE, 'https://dopebox.sx/watch/movie-hope-1fgfeb6a');
  await tab.evaluate(javaScriptOf('providerPickerScript', {
    preferredServerLabel: '', sourceChoiceToken: 'T0K', openServers: true, interactiveSource: false}));
  c = await calls();
  const lists = c.filter(x => x[0] === 'servers').map(x => JSON.parse(x[2]));
  assert(lists.length > 0, 'the page was read');
  assert(lists.every(l => l.length === 0), 'no share button listed: ' + JSON.stringify(lists[0]));

  // --- the owner's «⚙️» taps
  async function taps(rule) {
    await open(PLAYER, 'https://embed.test/vidspark');
    await tab.evaluate(javaScriptOf('providerPlayScript', {
      sourceChoiceToken: 'T0K', tapRuleTaps: rule.taps, tapRuleGapMs: rule.gap || 2000}));
    await tab.waitForTimeout(rule.wait);
    return (await calls()).filter(x => x[0] === 'playTarget').length;
  }
  assert.equal(await taps({taps: 2, gap: 600, wait: 2600}), 2, 'two taps, 600 ms apart');
  assert.equal(await taps({taps: 0, wait: 2000}), 0, '0 = never tap');
  assert(await taps({taps: -1, wait: 2000}) >= 6, 'the usual way: many taps');
  // the taps come at the given times
  await open(PLAYER, 'https://embed.test/vidspark');
  await tab.evaluate(javaScriptOf('providerPlayScript', {sourceChoiceToken: 'T0K', tapRuleTaps: 3, tapRuleGapMs: 700}));
  await tab.waitForTimeout(1000);
  assert.equal((await calls()).filter(x => x[0] === 'playTarget').length, 1, 'the first tap at 0.7 s');
  await tab.waitForTimeout(1600);
  assert.equal((await calls()).filter(x => x[0] === 'playTarget').length, 3, 'then one every 0.7 s, three in all');

  if (process.exitCode) throw new Error('page errors');
  console.log('Chromium: player in the site page (pop-up closed with «Later», never «Join» / the check page / a link out; the chosen server pressed under the pop-up and the page switched; no share row as servers; «⚙️» taps exact, 0 = none, usual way without): PASS');
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
