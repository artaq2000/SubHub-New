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
assert "BRIDGE_BUILD = '322.3.40'" in site
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
assert "versionCode 49" in gradle
assert "versionName '322.3.40'" in gradle
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


# VidSrc owner-only Android test guard (322.3.33).
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

assert 'prepareVidSrcSubHubUiV3216' in site
assert "cc.style.display = 'flex'" in site
assert 'installVidSrcPseudoFullscreenV3216' in site
assert '_activatePseudoFullscreen(box)' in site
assert "frame.removeAttribute('allowfullscreen')" in site
assert 'isVidSrcChainV3216' in js
assert 'location.ancestorOrigins' in js
assert '__subhub_vidsrc_caption_hide_v3216' in js

assert '__subhub_vidsrc_layout_v3217' in site
assert 'data-subhub-vidsrc' in site
assert 'installVidSrcInteractionGuardV3217' in js
assert '__subhub_vidsrc_controls_v3217' in js
assert "window.open = function () { return null; }" in js
assert '.vjs-fullscreen-control{display:none!important;}' in js
assert "ev.stopImmediatePropagation()" in js

assert "width:100%!important;height:100%!important" in site
assert "transform-origin:center center!important" in site
assert "_applyScreenModeV342(false, false)" in site
assert "transform:none!important" not in site[site.find("__subhub_vidsrc_layout_v3217"):site.find("__subhub_vidsrc_layout_v3217")+5000]

assert 'setVidSrcImmersive' in main
assert 'vidSrcPseudoFullscreenActive' in main
assert 'BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE' in main
assert 'SYSTEM_UI_FLAG_IMMERSIVE_STICKY' in main
assert 'onWindowFocusChanged' in main
assert 'setVidSrcImmersiveV3219' in site
assert 'setVidSrcImmersiveV3219(true)' in site
assert 'setVidSrcImmersiveV3219(false)' in site

assert "tracks[i].mode = 'disabled'" in js

assert 'forceVidSrcCaptionsOffV3221' in js
assert 'findVidSrcOffItemV3221' in js
assert 'findVidSrcCaptionButtonV3221' in js
assert 'vidSrcCaptionMenuOpenedV3221' in js
assert "cc.click()" in js
assert "setTimeout(clickVidSrcOffV3221, 80)" in js
assert "off.click()" in js
assert "player.setCurrentCaptions(-1)" in js

assert 'ensureVidSrcTakeoverV3222' in site
assert 'setVidSrcTakeoverActiveV3222' in site
assert 'updateVidSrcTakeoverV3222' in site
assert 'subhub-vidsrc-takeover-v3222' in site
assert "data-sh3222=\"play\"" in site
assert "sendVidSrcSafeCommandV3211('takeover'" in site
assert 'applyVidSrcTakeoverV3222' in js
assert '__subhub_vidsrc_takeover_v3222' in js
assert "cmd === 'takeover'" in js
assert 'video.controls = false' in js
assert '.jw-controlbar' in js
assert '.vjs-control-bar' in js

assert 'vidSrcTakeoverEnabledV3223' in site
assert 'clockReadyState >= 1' in site
assert '.video-modal-box:not(.pseudo-fullscreen)' in site
assert "forceVidSrcCaptionsOffV3221();\n      suppressVidSrcCaptionsV3215();" not in js
assert '[class*="subtitle" i]' in js
assert '[class*="caption" i]' in js

assert "setVidSrcTakeoverActiveV3222(true)" in site
assert 'setVidSrcCaptionScrubV3224' in js
assert 'scrubVidSrcProviderCaptionsV3224' in js
assert 'setInterval(' in js
assert 'data-subhub-hidden-overlay-v3224' in js

assert 'sh-v3225-more' in site
assert 'bindVidSrcSubtitleDragV3225' in site
assert 'setPointerCapture' in site
assert "overlay.style.setProperty('bottom'" in site
assert 'vidSrcTrackHookedV3225' in js
assert "tracks.addEventListener('change'" in js
assert '80\n    );' in js

assert "touchmove" in site
assert "passive:false" in site
assert "vidSrcDeepQueryAllV3226" in js
assert "video::cue" in js
assert "::-webkit-media-text-track-container" in js

assert "pointer-events:none" in site
assert "sh-v3227-menu" in site
assert "data-sh3222=\"subtitles\"" in site
assert "data-sh3222=\"screen\"" in site
assert "data-sh3222=\"fullscreen\"" in site
assert "toggleSubtitleModal" in site
assert "_cycleScreenModeV342" in site
assert "toggleEmbedFullscreen" in site
assert "requestVidSrcCaptionOffV3227" in site
assert "cmd === 'captionoff'" in js
assert "forceVidSrcCaptionOffV3227" in js
assert "findVidSrcOffDeepV3227" in js

assert "ensureVidSrcSubtitleGestureV3229" in site
assert "subhub-vidsrc-subtitle-gesture-v3229" in site
assert "touch-action:none" in site
assert "pinchStartDist" in site
assert "vidSrcGestureFontPxV3229" in site
assert "Math.min(120" in site
assert "data-subhub-vidsrc=\"1\"" in site
assert "body>#subPanel" not in site
assert "max-height:min(58vh,560px)" not in site

assert "syncVidSrcSubPanelLayoutV3230" in site
assert "subhub-subpanel-open-v3230" in site
assert "visibility:hidden!important;opacity:0!important;pointer-events:none!important" in site
assert "'z-index:2147482990'" in site

assert "height:62vh!important" in site
assert "height:36vh!important" in site
assert "overflow-y:auto!important" in site
assert "ensureVidSrcProviderShortcutsV3231" in site
assert "providerquality" in site
assert "providersubs" in site
assert "providercaptionsoff" in site
assert "openVidSrcProviderQualityV3231" in js
assert "openVidSrcProviderSubsV3231" in js
assert "forceVidSrcProviderCaptionsOffV3231" in js
assert "subhub-provider-captions-off-v3231" in js
assert 'button[data-sh3222="back"]' in site
assert 'button[data-sh3222="forward"]' in site

# VidSrc 322.3.32 compact top controls.
assert 'subhub-provider-close-hidden-v3233' in site
assert 'data-subhub-hidden-close-v3233' in site
assert 'data-sh3222="provider-subs"' in site
assert "sendVidSrcSafeCommandV3211('providersubs')" in site

# VidSrc 322.3.33 late-DOM sync and single play control.
assert 'startVidSrcProviderUiSyncV3233' in site
assert "display:none!important;font-size:24px" in site

# VidSrc 322.3.34 toolbar late-DOM sync and provider UI takeover.
assert 'scrubVidSrcProviderUiV3234' in js
assert 'setVidSrcProviderUiScrubV3234' in js
assert 'media-control-bar' in js
assert 'data-subhub-provider-ui-hidden-v3234' in js
assert 'startVidSrcProviderUiSyncV3233();' in site

# 322.3.35 provider center play remains interactive across reopen.
assert "display:none!important;visibility:hidden!important;opacity:0!important;pointer-events:none!important;}" in site
assert "media-control-bar" in js
assert "button[aria-label*=\"play\" i]" not in js[js.find("function scrubVidSrcProviderUiV3234"):js.find("function restoreVidSrcProviderUiV3234")]
assert "modal && box" in site


# Direct stream 322.3.38 capture-to-R2 path: reuse existing R2 UI untouched.
direct_js = (root / 'app/src/main/assets/direct_stream.js').read_text(encoding='utf-8')
capture = (root / 'app/src/main/java/com/artaq/subhub/DirectStreamCapture.java').read_text(encoding='utf-8')
assert "mode:'capture_to_r2'" in direct_js
assert 'window._watchSources' in direct_js
assert 'playSelectedWatchSource()' in direct_js
assert '__subhubDirectCaptured' in direct_js
assert '_subSettings' not in direct_js
assert "getElementById('subPanel')" not in direct_js
assert 'DirectStreamCapture' in main
assert 'endsWith(".m3u8")' in capture
assert 'DirectStreamPlayer(MainActivity.this' not in main

# Direct stream 322.3.40 keeps the exact R2 web UI and delegates HLS decoding to Media3.
direct_js = (root / 'app/src/main/assets/direct_stream.js').read_text(encoding='utf-8')
backend = (root / 'app/src/main/java/com/artaq/subhub/DirectR2Playback.java').read_text(encoding='utf-8')
assert 'findR2VideoElement' in direct_js
assert 'installR2Facade' in direct_js
assert 'startDirectR2Playback' in direct_js
assert 'getDirectR2State' in direct_js
assert 'updateDirectR2Bounds' in direct_js
assert 'directR2Command' in direct_js
assert "video.src = String(url)" not in direct_js
assert 'DirectR2Playback' in main
assert 'HlsMediaSource' in backend
assert 'TextureView' in backend
assert 'root.addView(frame, 0' in backend
assert 'DirectStreamPlayer(MainActivity.this' not in main
