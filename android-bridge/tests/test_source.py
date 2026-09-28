from pathlib import Path
root=Path(__file__).resolve().parents[1]
main=(root/'app/src/main/java/com/artaq/subhub/MainActivity.java').read_text(encoding='utf-8')
js=(root/'app/src/main/assets/player_clock.js').read_text(encoding='utf-8')
site=(root/'app/src/main/assets/site_bridge.js').read_text(encoding='utf-8')

assert 'SubHubAndroidBridge' in main
assert 'window.SubHubNativeClock' in main
assert 'pendingClockRaw' in main and 'clockDispatchRunnable' in main
assert 'USE_NATIVE_FULLSCREEN_SUBTITLE = false' in main

# Stable 322.2 timing stays intact.
assert "document.querySelectorAll('video')" in js
assert 'requestVideoFrameCallback' in js
assert "send(video, false, 'frame', st.lastFrameTime)" in js
assert 'seq: ++seq' in js
assert "current > 0.03" in js
assert "readyState >= 2" in js

# OnlyFlix/CDNM wrapper is advanced in the background.
assert 'tryAdvanceShareWrapper' in js
assert 'watchShareInnerPlayer' in js
assert "sendStage('deep-frame-loaded')" in js
assert "sendStage('deep-player-ui-ready'" in js
assert "if (!isShareHost() || shareAdvanceClicked) return false" in js

# SubHub keeps its own cover until the real nested player is ready.
assert "BRIDGE_BUILD = '322.3.15'" in site
assert 'subhub-onlyflix-cover-v3223' in site
assert 'ensureOnlyFlixCover' in site
assert 'revealOnlyFlixDeepPlayer' in site
assert "stage === 'deep-frame-loaded'" in site
assert "stage === 'deep-player-ui-ready'" in site
assert "s.adminKey === 'onlyflix'" in site
assert 'requestAnimationFrame(run)' in site
assert 'lastSeq' in site and 'estimatedTime' in site
assert '_onlyflixUseTimeV317' in site

print('source checks OK')

gradle=(root/'app/build.gradle').read_text(encoding='utf-8')
assert "versionCode 24" in gradle
assert "versionName '322.3.15'" in gradle
assert 'startPairingFlow' in main
assert 'syncNativeSubscription' in main
assert 'getNativeVersion' in main
assert 'isAppPaired' in main
assert 'UPDATES_WORKER_URL' in main
assert '<string name="app_name">SubHub</string>' in (root/'app/src/main/res/values/strings.xml').read_text(encoding='utf-8')

assert 'installSystemBarInsets' in main
assert 'WindowInsets.Type.systemBars()' in main
assert 'installUiPolishV324' in site
assert 'cleanupLegacyAnnouncementV324' in site
assert '#brandMark{display:none!important;}' in site
assert '#ownerVersionTag{display:none!important;}' in site
assert (root/'app/src/main/res/drawable-nodpi/subhub_launcher_foreground_32232.webp').exists()
manifest=(root/'app/src/main/AndroidManifest.xml').read_text(encoding='utf-8')
assert 'android:icon="@drawable/subhub_launcher_pretty_32234"' in manifest
assert 'android:roundIcon="@drawable/subhub_launcher_pretty_32234"' in manifest
assert 'android:scheme="subhub"' in manifest
assert 'android:host="paired"' in manifest

assert 'root.setOnApplyWindowInsetsListener' in main
assert 'lp.topMargin = top + dp(6)' in main
assert 'lp.bottomMargin = bottom' in main
assert (root/'app/src/main/res/mipmap-anydpi-v26/subhub_launcher_32233.xml').exists()

assert (root/'app/src/main/res/drawable/subhub_launcher_fg_32233.xml').exists()
assert (root/'app/src/main/res/values/colors.xml').exists()

assert (root/'app/src/main/res/drawable-nodpi/subhub_launcher_pretty_32234.webp').exists()


# Android subtitle downloads must work inside the app, including authenticated and blob URLs.
assert 'webView.setDownloadListener' in main
assert 'DownloadManager.Request' in main
assert 'CookieManager.getInstance().getCookie(cleanUrl)' in main
assert 'installDownloadInterceptor' in main
assert 'saveDataUrl' in main
assert 'MediaStore.Downloads.EXTERNAL_CONTENT_URI' in main
assert 'MAX_SUBTITLE_DOWNLOAD_BYTES' in main


# VidSrc owner-only Android test guard (322.3.15).
assert 'vidSrcGuardActive' in main
assert 'setVidSrcGuard' in main
assert 'shouldBlockVidSrcNavigation' in main
assert 'notifyVidSrcBlocked' in main
assert 'VIDSRC_GUARD_TOKEN' in site
assert 'installVidSrcGuardV328' in site
assert 'isVidSrcFrameActiveV328' in site
assert '__VIDSRC_GUARD_TOKEN__' in site

assert 'installVidSrcNoSandboxV329' in site
assert 'vidfastNoSandbox: true' in site
assert "h === 'vidsrc.to'" in site

assert "typeof currentMovie !== 'undefined'" in site
assert "typeof isLoggedIn !== 'undefined'" in site
assert "typeof vidsrcTrialAddedV355 !== 'function'" in site
assert "openEmbedPlayer(url, {" in site

assert 'SAFE_PLAYER_TYPE_V3211' in js
assert 'SUBHUB_SAFE_PLAYER_V1' in js
assert 'relaySafeCommandDownV3211' in js
assert 'handleSafeCommandV3211' in js
assert "cmd === 'seek'" in js
assert 'installVidSrcSafeControlsV3211' in site
assert 'sendVidSrcSafeCommandV3211' in site
assert '__subhubVidSrcDurationV3211' in site
assert 'if (vidSrcGuardActive) return true;' in main

assert 'bootstrapSafePlayV3212' in js
assert 'findSafePlayTargetV3212' in js
assert 'vjs-big-play-button' in js
assert 'jw-icon-playback' in js
assert 'setTimeout(post, 1350)' in site

assert 'tapVidSrc' in main
assert 'MotionEvent.obtain' in main
assert 'webView.dispatchTouchEvent(down)' in main
assert 'nativeTapVidSrcV3213' in site
assert "pointer-events', 'none'" in site

assert "v322.3.14 — Aloha-style strategy" in site
assert "openEmbedPlayer(url, {" in site
assert "vidfastNoSandbox: true" in site
assert "installVidSrcSafeControlsV3211();\n  installVidSrcNoSandboxV329();" not in site

assert 'lastVidSrcBlockedAt' in main
assert 'age < 2500L' in main
assert 'useNativeFullscreenSubtitle()' in main
assert 'return USE_NATIVE_FULLSCREEN_SUBTITLE || vidSrcGuardActive;' in main
assert 'isVidSrcGuardActive' in main
assert 'vidsrc\\.to' in js
assert 'isVidSrcHostV3215' in js
assert 'suppressVidSrcCaptionsV3215' in js
assert "tracks[i].mode = 'disabled'" in js
