from pathlib import Path
root=Path(__file__).resolve().parents[1]
main=(root/'app/src/main/java/com/artaq/subhub/MainActivity.java').read_text(encoding='utf-8')
js=(root/'app/src/main/assets/player_clock.js').read_text(encoding='utf-8')
site=(root/'app/src/main/assets/site_bridge.js').read_text(encoding='utf-8')
assert 'SubHubAndroidBridge' in main
assert 'window.SubHubNativeClock' in main
assert 'addDocumentStartJavaScript' in main
assert 'onShowCustomView' in main and 'nativeSubtitle' in main
assert 'currentTime' in js and 'mediaClock' in js
assert '80' in js
assert 'SubHubNativeClock' in site and '_onlyflixUseTimeV317' in site
assert 'site_bridge.js' in main and 'onPageFinished' in main
print('source checks OK')
