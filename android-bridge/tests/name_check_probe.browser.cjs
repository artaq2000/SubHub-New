// 322.3.122: the page reader of «🩺 فحص الأسماء» (assets/name_check_probe.js) in
// Chromium, on pages shaped like the real ones: an fbox film page with its server
// grid (names as seen on moviesjoy.sx, 22 Oct 2026), its front page, Cloudflare's
// check and 451 pages, a parked name, and pages that must NOT count as servers.
// 322.3.123: Moviesmod-family pages (StreamBoppo: /movie/27205), a site's own «new home» page
// and its old film page («Use the New DopeBox?») read as moved, share rows never servers.
// The app runs site_move.js first (MainActivity), as here.
const {chromium} = require('playwright');
const fs = require('fs'), path = require('path'), assert = require('assert');
const probe = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/site_move.js'), 'utf8') + '\n'
  + fs.readFileSync(path.join(__dirname, '../app/src/main/assets/name_check_probe.js'), 'utf8');

const SERVERS = ['vidsrc.mov', 'vidsrc.fyi', '⭐ VIP (No ADS)', 'vidfast', 'vidup', 'vidrock', 'vidnest', 'vidlink',
  '111movies', 'multiembed', 'mappletv', 'moviesapi', 'vidzee', 'vidrift', 'videm', 'vidsrc-V1', 'vidsrc-V2',
  'vidsrc-V3', 'vidsrc-V4', 'apiplayer', 'nontongo', 'vidapi', 'vidapi-V2', 'vidcore-V1', 'vidcore-V2', 'vixsrc',
  'strigil', 'cinesrc', 'embedbandit', 'vidlux', 'vidspark', 'wavembed', 'embed-api', 'nsrplay', 'vidzen',
  'cinextream', 'streamvault', 'vapgrid', 'vidbolt'];
const nav = '<header><a href="/">Home</a><a href="/movie">Movie</a><a href="/tv-show">TV-Series</a>' +
  '<a href="/top-imdb">Top IMDb</a><button>☰</button><a href="/search">🔍</a></header>';
const cards = n => '<div class="related">' + Array.from({length: n}, (_, i) =>
  `<a href="/watch/movie-film-${i}-x${i}y"><img alt=""><span>Film number ${i}</span></a>`).join('') + '</div>';
const page = (title, body) => `<!doctype html><html><head><title>${title}</title></head><body>${body}</body></html>`;
const FIX = {
  film: page('Animals', nav + '<div class="crumb"><a href="/">Home</a> / <a href="/movie">Movie</a> / Animals</div>' +
    '<div class="player"><button class="play">▶</button></div><h4>SERVERS</h4><div class="servers">' +
    SERVERS.map(s => `<button class="srv" data-id="${s}">${s}</button>`).join('') + '</div>' + cards(12)),
  filmLinks: page('Animals', nav + '<h4>SERVERS</h4><div class="servers">' + SERVERS.slice(0, 20).map(s =>
    `<a href="/watch/movie-animals-li2ws86x?server=${encodeURIComponent(s)}">${s}</a>`).join('') + '</div>' + cards(6)),
  filmNoGrid: page('Animals', nav + '<div class="genres">' + Array.from({length: 22}, (_, i) =>
    `<a href="/genre/g${i}">Genre ${i}</a>`).join('') + '</div><p>Coming soon</p>' + cards(15)),
  filmCardsRow: page('Animals', nav + '<div class="row">' + Array.from({length: 18}, (_, i) =>
    `<a href="/watch/movie-other-${i}-ab${i}">Other film ${i}</a>`).join('') + '</div>'),
  front: page('Moviesjoy', nav + '<h1>Moviesjoy</h1><p>Watch free movies</p>' + cards(24)),
  cfCheck: page('Just a moment...', '<div id="challenge-running"></div><h1>moviesjoy.sx</h1>' +
    '<p>Verifying you are human. This may take a few seconds.</p>' +
    '<p>moviesjoy.sx needs to review the security of your connection before proceeding.</p>'),
  ownCheck: page('Check', '<div><h2>Just a quick check</h2><button>Continue to Moviesmod</button></div>'),
  shortContinue: page('Wait', '<div><p>Click below to go on.</p><button>Continue</button></div>'),
  blocked451: page('Unavailable For Legal Reasons', '<h1>Error HTTP 451</h1><p>2026-10-22 18:54:56 UTC</p>' +
    '<h2>Unavailable For Legal Reasons</h2><h2>What happened?</h2><p>In response to a legal order, Cloudflare has taken ' +
    'steps to limit access to this website through Cloudflare\'s pass-through security and CDN services within ' +
    'United Kingdom.</p><p>Performance &amp; security by <a href="https://www.cloudflare.com">Cloudflare</a></p>'),
  parked: page('moviesjoy.sx', '<h1>This domain is for sale</h1><p>Buy moviesjoy.sx today. Related searches: ' +
    '<a href="/r?q=movies">Movies</a> <a href="/r?q=stream">Stream</a></p>'),
  // 322.3.123: as seen on 25 Oct 2026
  mmFilm: page('Resident Evil', '<header><a href="/">StreamBoppo</a><button>🔍</button><button>☰</button></header>' +
    '<div class="crumb"><a href="/">Home</a> / <a href="/movies">Movies</a> / Resident Evil</div><div class="player"></div>' +
    '<p>For best experience, use uBlock Origin or Brave Browser</p><h4>Select Server (⭐ = Recommended)</h4><div class="grid">' +
    SERVERS.map(s => `<button>${s}</button>`).join('') + '</div><div class="more">' +
    Array.from({length: 8}, (_, i) => `<a href="https://streamboppo.cyou/movie/${1000 + i}">Film ${i}</a>`).join('') + '</div>'),
  mmFront: page('StreamBoppo', '<h1>StreamBoppo</h1>' + Array.from({length: 10}, (_, i) =>
    `<a href="https://streamboppo.cyou/${i % 2 ? 'tv' : 'movie'}/${200 + i}">Title ${i}</a>`).join('') +
    '<a href="https://streamboppo.cyou/watch/tv/220542">Watch</a><a href="https://t.me/+abc">Telegram</a>'),
  newHome: page('Moviesmod', '<div class="card"><p>YOU WERE ABOUT TO WATCH</p><b>Resident Evil</b><span>2026</span></div>' +
    '<h2>Moviesmod has a new home: <span>streamboppo.cyou</span></h2><p>Same catalogue, same quality, nothing to install. ' +
    'Your title continues there.</p><a class="go" href="https://streamboppo.cyou/movie/1234">Continue to streamboppo.cyou →</a>' +
    '<p>Opening automatically in 27…</p><p>Remember the new address</p><code>streamboppo.cyou</code><button>Copy</button>'),
  newHomeNoLink: page('DopeBox', '<h2>DopeBox has a new home: flixframe.cyou</h2><p>Same catalogue.</p>' +
    '<button onclick="location.href=\'https://flixframe.cyou/\'">Continue to flixframe.cyou →</button>'),
  oldFilmPage: page('Hope', '<header><a href="/">dp</a></header><div class="crumb"><a href="/">Home</a> / <a href="/movie">Movie</a> / Hope</div>' +
    '<a class="big" href="https://flixframe.cyou/watch/movie-hope-1fgfeb6a">Use the New DopeBox? Click here →</a>' +
    '<div><b>Looking for fresh, unblocked sources?</b> FlixNetwork keeps all our sites and their latest working links in one place.' +
    '<a href="https://flixnetwork.is/">Go to FlixNetwork.is →</a><a href="https://flixframe.cyou/">or open the new DopeBox</a></div>' +
    '<p>Share this movie and let others know!</p><div class="share"><a href="https://wa.me/?t=x">WhatsApp</a>' +
    '<a href="https://t.me/share?u=x">Telegram</a><a href="https://facebook.com/s?u=x">Facebook</a><button>X</button>' +
    '<a href="https://reddit.com/submit">Reddit</a><button>Copy link</button><button>More…</button></div>'),
  shareRow: page('Hope', '<div class="share">' + ['WhatsApp', 'Telegram', 'Facebook', 'X', 'Reddit', 'Copy link',
    'More…', 'Email', 'Pinterest', 'Share', 'Join', 'Later'].map(t => `<button>${t}</button>`).join('') + '</div>')
};

(async () => {
  const browser = await chromium.launch({headless: true, executablePath: process.env.CHROME_PATH || undefined});
  const ctx = await browser.newContext({viewport: {width: 360, height: 640}});
  const tab = await ctx.newPage();
  let html = '';
  await tab.route('**/*', r => r.request().resourceType() === 'document'
    ? r.fulfill({status: 200, contentType: 'text/html; charset=utf-8', body: html})
    : r.fulfill({status: 204, body: ''}));
  async function read(fixture, url) {
    html = FIX[fixture];
    await tab.goto(url);
    return JSON.parse(await tab.evaluate(probe));
  }
  const FILM_URL = 'https://moviesjoy.sx/watch/movie-animals-li2ws86x';
  let r = await read('film', FILM_URL);
  assert.equal(r.servers, SERVERS.length, 'film page: every server of the grid, once');
  assert(r.known >= 15, 'film page: known names found');
  assert.equal(r.links, 12, 'film page: other films are links, not servers');
  assert.equal(r.check, false); assert.equal(r.blocked, false);
  r = await read('filmLinks', FILM_URL);
  assert.equal(r.servers, 20, 'servers given as links to this same film count');
  r = await read('filmNoGrid', FILM_URL);
  assert.equal(r.servers, 0, 'a genre menu is never servers');
  assert.equal(r.links, 15);
  r = await read('filmCardsRow', FILM_URL);
  assert.equal(r.servers, 0, 'a row of other films is never servers');
  r = await read('front', 'https://moviesjoy.sx/');
  assert.equal(r.servers, 0); assert.equal(r.links, 24, 'front page: film links');
  r = await read('cfCheck', FILM_URL);
  assert.equal(r.check, true, 'Cloudflare check page'); assert.equal(r.servers, 0);
  r = await read('ownCheck', 'https://moviesmod.gd/');
  assert.equal(r.check, true, "the site's own «Just a quick check»");
  r = await read('shortContinue', 'https://moviesjoy.sx/');
  assert.equal(r.check, true, 'a short page asking to continue');
  r = await read('blocked451', FILM_URL);
  assert.equal(r.blocked, true, '451 page'); assert.equal(r.check, false); assert.equal(r.servers, 0);
  r = await read('parked', 'https://moviesjoy.sx/');
  assert.deepEqual([r.servers, r.links, r.check, r.blocked], [0, 0, false, false], 'parked name: nothing of the site');
  // 322.3.123: the Moviesmod family
  const MM_FILM = 'https://streamboppo.cyou/movie/1234';
  r = await read('mmFilm', MM_FILM);
  assert.equal(r.servers, SERVERS.length, 'StreamBoppo film page: its grid');
  assert.equal(r.links, 8, 'StreamBoppo: /movie/<number> links are films'); assert.equal(r.moved, '');
  r = await read('mmFront', 'https://streamboppo.cyou/');
  assert.equal(r.links, 11, 'StreamBoppo front page: /movie/, /tv/ and /watch/tv/ links'); assert.equal(r.servers, 0);
  // a moved site says so on its own page
  r = await read('newHome', 'https://moviesmod.gd/movie/1234');
  assert.equal(r.moved, 'streamboppo.cyou', '«a new home» page: its own link');
  assert.equal(r.servers, 0);
  r = await read('newHomeNoLink', 'https://dopebox.sx/watch/movie-hope-1fgfeb6a');
  assert.equal(r.moved, 'flixframe.cyou', '«a new home» page without a link: the address written on it');
  r = await read('oldFilmPage', 'https://dopebox.sx/watch/movie-hope-1fgfeb6a');
  assert.equal(r.moved, 'flixframe.cyou', 'old film page: the new site, never FlixNetwork / share links');
  assert.equal(r.servers, 0, 'a share row is never servers');
  r = await read('shareRow', FILM_URL);
  assert.equal(r.servers, 0, 'share and pop-up buttons are never servers (even 12 of them)');
  r = await read('film', FILM_URL);
  assert.equal(r.moved, '', 'a working page is not moved');
  r = await read('ownCheck', 'https://moviesmod.gd/');
  assert.equal(r.moved, '', '«Continue to Moviesmod» (the check page) is not a move');
  // the script never presses anything
  html = FIX.film;
  await tab.goto(FILM_URL);
  await tab.evaluate(() => { window.__clicks = 0; document.addEventListener('click', () => window.__clicks++, true); });
  await tab.evaluate(probe);
  assert.equal(await tab.evaluate(() => window.__clicks), 0, 'nothing is clicked');
  html = FIX.oldFilmPage;
  await tab.goto('https://dopebox.sx/watch/movie-hope-1fgfeb6a');
  await tab.evaluate(() => { window.__clicks = 0; document.addEventListener('click', () => window.__clicks++, true); });
  await tab.evaluate(probe);
  assert.equal(await tab.evaluate(() => window.__clicks), 0, 'reading a move notice clicks nothing');
  console.log('Chromium: name check page reader (film grid, links to this film, menus and film rows not servers, front page, Cloudflare and own check pages, 451, parked name, Moviesmod family pages, moved sites, share rows, no clicks): PASS');
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
