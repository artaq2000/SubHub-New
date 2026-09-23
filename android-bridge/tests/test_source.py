from pathlib import Path
root=Path(__file__).resolve().parents[1]
main=(root/'app/src/main/java/com/artaq/subhub/MainActivity.java').read_text(encoding='utf-8')
js=(root/'app/src/main/assets/player_clock.js').read_text(encoding='utf-8')
site=(root/'app/src/main/assets/site_bridge.js').read_text(encoding='utf-8')

assert 'SubHubAndroidBridge' in main
assert 'window.SubHubNativeClock' in main
assert 'pendingClockRaw' in main and 'clockDispatchRunnable' in main
assert 'USE_NATIVE_FULLSCREEN_SUBTITLE = false' in main

# Stable 322.2 clock must remain intact.
assert "document.querySelectorAll('video')" in js
assert 'requestVideoFrameCallback' in js
assert "send(video, false, 'frame', st.lastFrameTime)" in js
assert 'seq: ++seq' in js

# SoapDiag 1.16 preparation behavior.
assert "جارٍ تحضير الفيديو..." in js
assert "سيظهر المشغّل مباشرةً عند الجاهزية" in js
assert "current > 0.03" in js
assert "readyState >= 2" in js
assert "signalPlayerReady('media-playing-event')" in js
assert "signalPlayerReady('media-playing-progress')" in js
assert "setTimeout(reveal, 6500)" in js
assert "setTimeout(reveal, 11000)" in js
assert "setTimeout(hidePrepareOverlay, 40)" in js
assert "setTimeout(forceInlinePlayerRepaint, 16)" in js

assert "BRIDGE_BUILD = '322.2.1'" in site
assert 'lastSeq' in site and 'estimatedTime' in site
assert '_onlyflixUseTimeV317' in site
print('source checks OK')
