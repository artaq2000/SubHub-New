// 322.3.127: «📱 مشاهدة بالتطبيق» for films (assets/app_list_stream.js + auto_chain.js +
// app_cards_order.js) in Chromium, with the app bridge and the site faked:
//  - a film with 2+ app servers: one automatic button + «🔀 اختيار السيرفر» first; the
//    numbered buttons hidden until the chooser opens (and closed again on another film);
//  - the automatic search arms each try and opens the servers one after the other
//    (a failure hands over, a success is kept by name), then the order on this device
//    puts the server that worked first;
//  - one place per film: the list sends the film's key (not the server's) and the site's
//    place for the film; the app's reports write it (finished = removed);
//  - one server only / the owner: no automatic button.
//  - 322.3.128: the player is told when a server is the film's only one (it may wait longer
//    while the video still comes); never in the automatic search or with several servers.
const {chromium} = require('playwright');
const fs = require('fs'), path = require('path'), assert = require('assert');
const A = p => fs.readFileSync(path.join(__dirname, '../app/src/main/assets', p), 'utf8');
const order = A('app_cards_order.js'), list = A('app_list_stream.js'), chain = A('auto_chain.js');

const PAGE = 'https://fbox.gd/watch/movie-the-cycle-si1d7tgk';
const STUBS = `
var currentMovie = {id: 'tmdb_movie_12345', title: 'The Cycle'};
var isLoggedIn = false;
window.toasts = []; window.opens = []; window.arms = []; window.ends = 0;
function showToast(t) { window.toasts.push(t); }
async function checkOwnerAccess() { return true; }
function _buildSubtitleTrackCatalog() { return [{name: 'ترجمة SubHub', isDefault: true}]; }
window.__servers = {
  tmdb_movie_12345: [
    {id: 'a1', label: 'VidFast', key: 'vidfast', pageUrl: '${PAGE}'},
    {id: 'b2', label: 'VIP (No ADS)', key: 'vip-no-ads', pageUrl: '${PAGE}'},
    {id: 'c3', label: 'vidnest', key: 'vidnest', pageUrl: '${PAGE}'}
  ],
  tmdb_movie_777: [{id: 'z9', label: 'VidFast', key: 'vidfast', pageUrl: 'https://fbox.gd/watch/movie-other-ab12cd34'}]
};
window._lastRenderedMovieDoc = {id: currentMovie.id, title: 'The Cycle', contentType: 'movie', appServers: window.__servers[currentMovie.id]};
var db = {collection: function () { return {doc: function (id) { return {
  get: async function () {
    if (id === '_appSettings') return {exists: true, data: function () { return {fboxDomains: ['flixframe.cyou', 'flixhaven.cyou']}; }};
    return {exists: true, data: function () { return {appServers: (window.__servers[id] || []).slice()}; }};
  },
  set: async function () {}
}; }}; }};
window.SubHubAndroidBridge = {
  openDirectStream: function (t, raw) { window.opens.push(JSON.parse(raw)); },
  directStreamAutoArm: function (t, raw) { window.arms.push(JSON.parse(raw)); },
  directStreamAutoEnd: function () { window.ends++; }
};
`;

(async () => {
  const browser = await chromium.launch({headless: true, executablePath: process.env.CHROME_PATH || undefined});
  const page = await browser.newPage({viewport: {width: 390, height: 844}});
  page.on('pageerror', e => { console.error('page error:', e); process.exitCode = 1; });
  await page.route('https://subhub.test/**', r => r.fulfill({status: 200, contentType: 'text/html; charset=utf-8',
    body: '<!doctype html><html dir="rtl"><body><div class="watch-pills"><button class="watch-pill" id="trailer">🎞️ الإعلان</button></div></body></html>'}));
  await page.goto('https://subhub.test/');
  // a place the site keeps for this film (watched 40:00 on a site server, 1 h ago)
  await page.evaluate(() => localStorage.setItem('subhub_site_resume_v405', JSON.stringify({
    tmdb_movie_12345: {t: 2400, d: 6731, at: Date.now() - 3600000}})));
  await page.addScriptTag({content: STUBS});
  await page.addScriptTag({content: order});
  await page.addScriptTag({content: list});
  await page.addScriptTag({content: chain});

  // ---- the buttons
  await page.waitForSelector('#subhub-appauto-btn');
  await page.waitForFunction(() => document.querySelectorAll('.subhub-applist-btn[data-applist="app"]').length === 3);
  const ids = await page.evaluate(() => [].slice.call(document.querySelector('.watch-pills').children)
    .filter(b => b.hasAttribute('data-app-order')).map(b => b.id));
  assert.deepEqual(ids, ['subhub-appauto-btn', 'subhub-appchoose-btn', 'subhub-applist-btn-a1', 'subhub-applist-btn-b2',
    'subhub-applist-btn-c3'], 'automatic + chooser first among the app buttons: ' + ids);
  assert.equal(await page.locator('#subhub-appauto-btn').innerText(), '📱 مشاهدة بالتطبيق');
  assert.equal(await page.locator('#subhub-appchoose-btn').innerText(), '🔀 اختيار السيرفر ▾');
  const hiddenCount = () => page.evaluate(() => [].slice.call(document.querySelectorAll('.subhub-applist-btn[data-applist="app"]'))
    .filter(b => getComputedStyle(b).display === 'none').length);
  assert.equal(await hiddenCount(), 3, 'numbered buttons hidden');
  assert.equal(await page.evaluate(() => document.getElementById('subhub-appauto-btn').classList.contains('subhub-applist-btn')), false,
    'the site never renumbers the automatic button');
  await page.locator('#subhub-appchoose-btn').click();
  await page.waitForFunction(() => document.getElementById('subhub-appchoose-btn').textContent === '🔀 اختيار السيرفر ▴');
  assert.equal(await hiddenCount(), 0, 'chooser open: numbered buttons shown');
  await page.waitForTimeout(1000);   // the 900 ms redraw keeps them shown
  assert.equal(await hiddenCount(), 0);
  await page.locator('#subhub-appchoose-btn').click();
  await page.waitForFunction(() => document.getElementById('subhub-appchoose-btn').textContent === '🔀 اختيار السيرفر ▾');
  assert.equal(await hiddenCount(), 3);

  // ---- the automatic search
  await page.locator('#subhub-appauto-btn').click();
  await page.waitForFunction(() => window.opens.length === 1);
  let st = await page.evaluate(() => ({opens: window.opens, arms: window.arms}));
  assert.equal(st.arms.length, 1);
  assert.equal(st.arms[0].index, 0);
  assert.equal(st.arms[0].total, 3);
  assert.equal(st.arms[0].more, true);
  let o = st.opens[0];
  assert.equal(o.mode, 'list');
  assert.equal(o.interactiveSource, false);
  assert.equal(o.serverLabel, 'VidFast', 'nothing known yet: the owner\'s order');
  assert.equal(o.titleKey, 'tmdb_movie_12345', 'the film\'s place, not the server\'s');
  assert.ok(/_a1$/.test(o.resumeKey), 'the old key still names the server (used once): ' + o.resumeKey);
  assert.equal(o.filmProgress.movieId, 'tmdb_movie_12345');
  assert.equal(o.filmProgress.resumeMs, 2400000, 'the site\'s place goes with it');
  assert.ok(o.filmProgress.at > 0);
  assert.equal(o.site, 'fbox');
  assert.equal(o.onlyServer, false, 'three servers: not the only one');
  const chainId = st.arms[0].chain;
  // the app: the player opened, VidFast does not play → closed, next
  await page.evaluate(c => window.__subhubAutoOpenedV3110(c, 0), chainId);
  await page.evaluate(s => window.__subhubDirectClosed(s), o.session);
  await page.evaluate(c => window.__subhubAutoNextV3110(c), chainId);
  await page.waitForFunction(() => window.opens.length === 2);
  st = await page.evaluate(() => ({opens: window.opens, arms: window.arms}));
  assert.equal(st.arms[1].index, 1);
  assert.equal(st.opens[1].serverLabel, 'VIP (No ADS)');
  assert.equal(st.opens[1].titleKey, 'tmdb_movie_12345', 'same place on the next server');
  await page.evaluate(c => window.__subhubAutoOpenedV3110(c, 1), chainId);
  await page.evaluate(c => window.__subhubAutoOkV3110(c), chainId);   // VIP plays
  const stats = await page.evaluate(() => JSON.parse(localStorage.getItem('shSrvStatsV401') || '{}'));
  const s = stats.tmdb_movie_12345;
  assert.equal(s.vidfast.fail, 1);
  assert.equal(s.vidfast.ok, 0);
  assert.equal(s['vip (no ads)'].ok, 1);
  // the player closes; next time VIP (worked) comes first
  await page.evaluate(s => window.__subhubDirectClosed(s), st.opens[1].session);
  await page.locator('#subhub-appauto-btn').click();
  await page.waitForFunction(() => window.opens.length === 3);
  st = await page.evaluate(() => ({opens: window.opens, arms: window.arms}));
  assert.equal(st.opens[2].serverLabel, 'VIP (No ADS)', 'the server that worked comes first');
  assert.equal(st.arms[2].index, 0);
  assert.ok(st.arms[2].chain !== chainId, 'a new search');
  // vidnest (untried) now comes before VidFast (failed)
  await page.evaluate(() => { window.__subhubAutoOpenedV3110(window.arms[2].chain, 0); });
  await page.evaluate(s => window.__subhubDirectClosed(s), st.opens[2].session);
  await page.evaluate(() => window.__subhubAutoNextV3110(window.arms[2].chain));
  await page.waitForFunction(() => window.opens.length === 4);
  assert.equal(await page.evaluate(() => window.opens[3].serverLabel), 'vidnest', 'untried before failed');
  await page.evaluate(() => window.__subhubAutoCancelV3110());
  await page.evaluate(() => window.__subhubDirectClosed(window.opens[3].session));

  // ---- one place per film, shared with the site
  await page.evaluate(() => window.__subhubFilmProgressV3127('tmdb_movie_12345', 3125.4, 6731));
  let place = await page.evaluate(() => JSON.parse(localStorage.getItem('subhub_site_resume_v405')).tmdb_movie_12345);
  assert.equal(place.t, 3125);
  assert.equal(place.d, 6731);
  assert.ok(Date.now() - place.at < 5000);
  await page.evaluate(() => window.__subhubFilmProgressV3127('tmdb_movie_12345', 2, 6731));   // too early: kept
  place = await page.evaluate(() => JSON.parse(localStorage.getItem('subhub_site_resume_v405')).tmdb_movie_12345);
  assert.equal(place.t, 3125);
  await page.evaluate(() => window.__subhubFilmProgressV3127('tmdb_movie_12345', 6500, 6731));   // the end: removed
  assert.equal(await page.evaluate(() => 'tmdb_movie_12345' in JSON.parse(localStorage.getItem('subhub_site_resume_v405'))), false);
  // a manual number from the chooser gets the same film key
  await page.locator('#subhub-appchoose-btn').click();
  await page.locator('#subhub-applist-btn-c3').click();
  await page.waitForFunction(() => window.opens.length === 5);
  o = await page.evaluate(() => window.opens[4]);
  assert.equal(o.serverLabel, 'vidnest');
  assert.equal(o.titleKey, 'tmdb_movie_12345');
  assert.equal(o.filmProgress.resumeMs, 0, 'finished: no place');
  assert.equal(o.onlyServer, false, 'chosen by hand among three: others remain');
  await page.evaluate(s => window.__subhubDirectClosed(s), o.session);

  // ---- another film with ONE server: no automatic button, its button shown
  await page.evaluate(() => {
    currentMovie = {id: 'tmdb_movie_777', title: 'Other'};
    window._lastRenderedMovieDoc = {id: 'tmdb_movie_777', title: 'Other', contentType: 'movie', appServers: window.__servers.tmdb_movie_777};
  });
  await page.waitForFunction(() => !!document.getElementById('subhub-applist-btn-z9'));
  await page.waitForTimeout(1000);
  assert.equal(await page.locator('#subhub-appauto-btn').count(), 0);
  assert.equal(await page.locator('#subhub-appchoose-btn').count(), 0);
  assert.equal(await hiddenCount(), 0, 'its button is shown');
  // its only server: the player may wait longer while the video still comes (322.3.128)
  await page.locator('#subhub-applist-btn-z9').click();
  await page.waitForFunction(() => window.opens.length === 6);
  o = await page.evaluate(() => window.opens[5]);
  assert.equal(o.serverLabel, 'VidFast');
  assert.equal(o.onlyServer, true, 'the film\'s only app server');
  await page.evaluate(s => window.__subhubDirectClosed(s), o.session);
  // back to the first film: the chooser starts closed again
  await page.evaluate(() => {
    currentMovie = {id: 'tmdb_movie_12345', title: 'The Cycle'};
    window._lastRenderedMovieDoc = {id: 'tmdb_movie_12345', title: 'The Cycle', contentType: 'movie', appServers: window.__servers.tmdb_movie_12345};
  });
  await page.waitForSelector('#subhub-appauto-btn');
  await page.waitForFunction(() => document.querySelectorAll('.subhub-applist-btn[data-applist="app"]').length === 3);
  await page.waitForTimeout(1000);
  assert.equal(await page.locator('#subhub-appchoose-btn').innerText(), '🔀 اختيار السيرفر ▾');
  assert.equal(await hiddenCount(), 3);

  // ---- the owner: his section, no viewer buttons, the hint names the automatic button
  await page.evaluate(() => {
    isLoggedIn = true;
    const g = document.querySelector('.watch-pills');
    g.classList.add('owner-watch-pills');
  });
  await page.waitForSelector('#subhub-applist-section');
  assert.equal(await page.locator('#subhub-appauto-btn').count(), 0);
  assert.match(await page.locator('#subhub-applist-section').innerText(), /«📱 مشاهدة بالتطبيق» يجرّب السيرفرات بالترتيب/);

  await browser.close();
  if (!process.exitCode) console.log('film automatic button, search order, one place per film — OK');
})().catch(e => { console.error(e); process.exit(1); });
