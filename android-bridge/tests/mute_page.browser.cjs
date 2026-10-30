// 322.3.127: the hidden server page never makes a sound. The script shipped in
// DirectStreamPlayer.MUTE_SCRIPT runs at document start in every frame (the app uses
// WebViewCompat.addDocumentStartJavaScript with "*"; here Playwright's addInitScript,
// which also runs in every frame before the page's own scripts). In the top page, a
// same-site frame and a frame of another site: play() mutes, «muted = false» is
// refused, an autoplay element is muted when it starts, sound turned on is turned
// off again — and the media still plays (we only take its link).
const {chromium} = require('playwright');
const fs = require('fs'), path = require('path'), assert = require('assert');

const java = fs.readFileSync(path.join(__dirname, '../app/src/main/java/com/artaq/subhub/DirectStreamPlayer.java'), 'utf8');
const m = java.match(/static final String MUTE_SCRIPT = ([\s\S]*?");\n/);
assert.ok(m, 'MUTE_SCRIPT found');
const MUTE = m[1].match(/"((?:[^"\\]|\\.)*)"/g).map(s => JSON.parse(s)).join('');
assert.ok(MUTE.startsWith('(function(){') && MUTE.endsWith('})();'), 'a whole script');

const FRAME = `<!doctype html><body><script>${'(' + String(function () {
  // a 1-second silent WAV, then: play(), unmute, autoplay element, volume up
  function wav() {
    const n = 8000, b = new ArrayBuffer(44 + n * 2), v = new DataView(b);
    const w = (o, s) => { for (let i = 0; i < s.length; i++) v.setUint8(o + i, s.charCodeAt(i)); };
    w(0, 'RIFF'); v.setUint32(4, 36 + n * 2, true); w(8, 'WAVE'); w(12, 'fmt ');
    v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
    v.setUint32(24, 8000, true); v.setUint32(28, 16000, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true);
    w(36, 'data'); v.setUint32(40, n * 2, true);
    return URL.createObjectURL(new Blob([b], {type: 'audio/wav'}));
  }
  window.report = {};
  const a = document.createElement('audio');
  a.src = wav(); a.loop = true;
  document.body.appendChild(a);
  a.play().then(function () {
    window.report.playMuted = a.muted;
    a.muted = false;                               // the page tries to turn the sound on
    window.report.afterUnmute = a.muted;
    a.volume = 1;
    window.report.playing = !a.paused;
  }).catch(function (e) { window.report.err = String(e); });
  const v = document.createElement('video');      // starts by itself
  v.autoplay = true; v.src = wav(); v.loop = true;
  v.addEventListener('playing', function () { setTimeout(function () { window.report.autoMuted = v.muted; window.report.autoPlaying = !v.paused; }, 50); });
  document.body.appendChild(v);
}) + ')();'}</script></body>`;

(async () => {
  const browser = await chromium.launch({headless: true, executablePath: process.env.CHROME_PATH || undefined,
    args: ['--autoplay-policy=no-user-gesture-required']});
  const ctx = await browser.newContext();
  await ctx.addInitScript({content: MUTE});
  const page = await ctx.newPage();
  page.on('pageerror', e => { console.error('page error:', e); process.exitCode = 1; });
  await page.route('https://fbox.test/**', r => r.fulfill({status: 200, contentType: 'text/html; charset=utf-8',
    body: r.request().url().endsWith('/frame') ? FRAME
      : FRAME.replace('</body>', '<iframe src="/frame"></iframe><iframe src="https://vidnest.test/embed"></iframe></body>')}));
  await page.route('https://vidnest.test/**', r => r.fulfill({status: 200, contentType: 'text/html; charset=utf-8', body: FRAME}));
  await page.goto('https://fbox.test/watch/movie-x');
  await page.waitForFunction(() => document.querySelectorAll('iframe').length === 2);
  const frames = page.frames();
  assert.equal(frames.length, 3, 'top page + 2 frames');
  for (const f of frames) {
    await f.waitForFunction(() => window.report && 'afterUnmute' in window.report && 'autoMuted' in window.report, null, {timeout: 15000});
    const r = await f.evaluate(() => window.report);
    const where = f === page.mainFrame() ? 'top page' : f.url();
    assert.equal(r.err, undefined, where + ': ' + r.err);
    assert.equal(r.playMuted, true, where + ': play() mutes');
    assert.equal(r.afterUnmute, true, where + ': muted = false refused');
    assert.equal(r.playing, true, where + ': still plays');
    assert.equal(r.autoMuted, true, where + ': autoplay muted');
    assert.equal(r.autoPlaying, true, where + ': autoplay still plays');
  }
  // twice in the same frame (a WebView may run it again): harmless
  await page.evaluate(MUTE);
  assert.equal(await page.evaluate(() => document.querySelector('audio').muted), true);

  // without the script (the owner's add-server page) the page's sound is its own
  const plain = await browser.newPage();
  await plain.route('https://fbox.test/**', r => r.fulfill({status: 200, contentType: 'text/html; charset=utf-8', body: FRAME}));
  await plain.goto('https://fbox.test/watch/movie-x');
  await plain.waitForFunction(() => window.report && 'afterUnmute' in window.report, null, {timeout: 15000});
  assert.equal(await plain.evaluate(() => window.report.afterUnmute), false, 'no script: the page can turn its sound on');

  await browser.close();
  if (!process.exitCode) console.log('hidden page muted in every frame (and still playing) — OK');
})().catch(e => { console.error(e); process.exit(1); });
