from pathlib import Path
root=Path(__file__).resolve().parents[1]
main=(root/'app/src/main/java/com/artaq/subhub/MainActivity.java').read_text(encoding='utf-8')
js=(root/'app/src/main/assets/player_clock.js').read_text(encoding='utf-8')
site=(root/'app/src/main/assets/site_bridge.js').read_text(encoding='utf-8')

assert 'SubHubAndroidBridge' in main
assert 'window.SubHubNativeClock' in main
assert 'pendingClockRaw' in main and 'clockDispatchRunnable' in main
assert 'USE_NATIVE_FULLSCREEN_SUBTITLE = false' in main

# Stable 322.2 timing + SoapDiag 1.16 preparation must remain untouched.
assert "document.querySelectorAll('video')" in js
assert 'requestVideoFrameCallback' in js
assert "send(video, false, 'frame', st.lastFrameTime)" in js
assert 'seq: ++seq' in js
assert "جارٍ تحضير الفيديو..." in js
assert "current > 0.03" in js
assert "readyState >= 2" in js
assert "setTimeout(reveal, 6500)" in js
assert "setTimeout(reveal, 11000)" in js

# v322.2.2 click acknowledgement.
assert "BRIDGE_BUILD = '322.2.2'" in site
assert 'subhub-opening-v3222' in site
assert 'جارٍ الفتح…' in site
assert 'requestAnimationFrame(run)' in site
assert "s.adminKey === 'onlyflix'" in site
assert 'playerModalIsOpen' in site
assert 'lastSeq' in site and 'estimatedTime' in site
assert '_onlyflixUseTimeV317' in site
print('source checks OK')
