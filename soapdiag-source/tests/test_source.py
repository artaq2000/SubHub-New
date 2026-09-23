from pathlib import Path
import hashlib

r = Path(__file__).resolve().parents[1]
manifest = (r/'app/src/main/AndroidManifest.xml').read_text()
java = (r/'app/src/main/java/com/artaq/soapdiag/MainActivity.java').read_text()
js_path = r/'app/src/main/assets/diag.js'
js = js_path.read_text()
gradle = (r/'app/build.gradle').read_text()

assert 'android.permission.INTERNET' in manifest
assert 'com.artaq.soapdiag' in gradle
assert "versionName '1.16'" in gradle

for domain in ['onlyflix.to','cdnm.ink','ilove2day.com','nontongo.day','nontongo.stream','cdnmvs.online','soapsoap123.workers.dev','medmedia05.mom']:
    assert domain in java or domain in js

for hook in ['window.fetch','XMLHttpRequest','sendBeacon']:
    assert hook in js

# Exact uploaded 1.16 player/overlay asset.
assert hashlib.sha256(js_path.read_bytes()).hexdigest() == '13549b4407a43190c6c4df30af057cf67c838c556ff20e1fad2c6ef9c2b1b1d6'
assert 'جارٍ تحضير الفيديو...' in js
assert '__soapdiag_prepare_overlay' in js
assert 'forceInlinePlayerRepaint' in js
assert 'maximizeServer1Frame' in js

# 1.16 Java flow remains intact.
assert '=== SoapDiag 1.16 ===' in java
assert 'https://onlyflix.to/resident-evil-2/' in java
assert 'DOCUMENT_START_SCRIPT' in java
assert 'Cookie: [present, value redacted]' in java
assert 'Authorization: [present, value redacted]' in java
assert '[TEST] Auto Server 1 extraction' in java
assert 'isServer1Embed' in java
assert 'SERVER 1 EMBED FOUND' in java
assert '__soapdiag_server1' in java
assert "createElement('iframe')" in java
assert 'manifestFromSegment' in java
assert '[DERIVED SERVER 1 MANIFEST]' in java

# Only new feature: title search before handing the real URL to the 1.16 flow.
assert 'movieNameInput' in java
assert 'Button search = button("بحث")' in java
assert 'اسم الفيلم بالإنجليزية' in java
assert 'private void startMovieSearch()' in java
assert 'https://onlyflix.to/?s=' in java
assert 'tryOpenOnlyFlixSearchResult' in java
assert '[SEARCH FOUND]' in java
assert 'urlInput.setText(found)' in java

print('SoapDiag 1.16 + title-search source tests passed')
