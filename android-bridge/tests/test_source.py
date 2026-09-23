from pathlib import Path
root=Path(__file__).resolve().parents[1]
main=(root/'app/src/main/java/com/artaq/subhub/MainActivity.java').read_text(encoding='utf-8')
js=(root/'app/src/main/assets/player_clock.js').read_text(encoding='utf-8')
site=(root/'app/src/main/assets/site_bridge.js').read_text(encoding='utf-8')

assert 'SubHubAndroidBridge' in main
assert 'window.SubHubNativeClock' in main
assert 'addDocumentStartJavaScript' in main
assert 'pendingClockRaw' in main and 'clockDispatchRunnable' in main
assert 'USE_NATIVE_FULLSCREEN_SUBTITLE = false' in main

assert "document.querySelectorAll('video')" in js
assert "video,audio" not in js
assert 'requestVideoFrameCallback' in js
assert 'frameMediaTime' in js
assert "clockSource = 'video.currentTime'" in js
assert "clockSource = 'player-ui'" in js
assert 'const videoTime = Number(v.currentTime || 0)' in js
assert 'effective = ui.current' in js

assert "BRIDGE_BUILD = '322.3'" in site
assert 'clockDiagShown' in site
assert 'lastSeq' in site and 'estimatedTime' in site
assert '_onlyflixUseTimeV317' in site
assert 'site_bridge.js' in main and 'onPageFinished' in main

print('source checks OK')
