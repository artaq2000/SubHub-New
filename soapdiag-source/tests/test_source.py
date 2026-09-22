from pathlib import Path
r = Path(__file__).resolve().parents[1]
manifest=(r/'app/src/main/AndroidManifest.xml').read_text()
java=(r/'app/src/main/java/com/artaq/soapdiag/MainActivity.java').read_text()
js=(r/'app/src/main/assets/diag.js').read_text()
assert 'android.permission.INTERNET' in manifest
assert 'com.artaq.soapdiag' in (r/'app/build.gradle').read_text()
for domain in ['onlyflix.to','cdnm.ink','ilove2day.com','nontongo.day','nontongo.stream','cdnmvs.online','soapsoap123.workers.dev','medmedia05.mom']:
    assert domain in java or domain in js
for hook in ['window.fetch','XMLHttpRequest','sendBeacon']:
    assert hook in js
assert 'DOCUMENT_START_SCRIPT' in java
assert 'Cookie: [present, value redacted]' in java
assert 'Authorization: [present, value redacted]' in java
print('SoapDiag source tests passed')

assert 's1.cdnmvs.online' in java or 's1\\.cdnmvs\\.online' in js
assert 'candidate-url' in java and 'candidate-url' in js
assert 'Server 1' in java

assert 'https://onlyflix.to/resident-evil-2/' in java

assert 'isServer1Embed' in java
assert 'SERVER 1 EMBED FOUND' in java

assert '__soapdiag_server1' in java
assert 'createElement(\'iframe\')' in java
assert 'versionName \'1.4\'' in (r/'app/build.gradle').read_text()
