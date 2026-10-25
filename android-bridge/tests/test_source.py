import re
from pathlib import Path
root=Path(__file__).resolve().parents[1]
main=(root/'app/src/main/java/com/artaq/subhub/MainActivity.java').read_text(encoding='utf-8')
js=(root/'app/src/main/assets/player_clock.js').read_text(encoding='utf-8')
site=(root/'app/src/main/assets/site_bridge.js').read_text(encoding='utf-8')
direct=(root/'app/src/main/java/com/artaq/subhub/DirectStreamPlayer.java').read_text(encoding='utf-8')
moviesmod=(root/'app/src/main/assets/moviesmod_stream.js').read_text(encoding='utf-8')

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
assert "BRIDGE_BUILD = '322.3.73'" in site
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
assert "versionCode 133" in gradle
assert "versionName '322.3.124'" in gradle
assert 'SubHubNativeResumeV3257' in site
assert 'wakeVidSrcPlaybackV3256' in site
assert 'vidSrcWakeRetryCountV3257' in site
assert 'vidSrcWakeRetryCountV3257 < 3' in site
assert "clockReadyState>=1 && !vidSrcPlaybackPendingV3252" in site
assert "vidSrcControlsDeadlineV3254=Date.now()+900" in site
assert "sendVidSrcSafeCommandV3211('takeover', {active:false})" in site
assert "root.style.setProperty('display', 'none', 'important')" in site
assert 'retryDelivery' in main
assert 'activeClockReadyState = 0' in main
assert 'refreshVidSrcChannels("refresh")' in main
assert '.sh-v3222-play{display:none!important' in site
assert '#subhub-vidsrc-takeover-v3222.sh-v3251-ready .sh-v3222-center{display:flex!important' in site
assert 'recoverVidSrcPlaybackV3254(p)) return' not in site
assert 'startPairingFlow' in main
assert 'syncNativeSubscription' in main
assert 'getNativeVersion' in main
assert 'openImdb' in main
assert 'normalizeImdbExternalUrl' in main
assert 'installImdbExternalOpenV3249' in site
assert 'normalizeImdbTargetV3249' in site
assert 'onShowFileChooser' in main
assert 'FileChooserParams.parseResult' in main
r2upload=(root/'app/src/main/assets/r2_upload.js').read_text(encoding='utf-8')
assert 'subhubAndroidR2BackgroundV348' in r2upload
assert '/api/create-multipart' in r2upload
assert '/api/complete-multipart' in r2upload
assert 'localStorage' in r2upload
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
# Playback is single-send; retries could undo a later pause.
assert 'setTimeout(post, 1350)' not in site

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

# 322.3.57 provider center feedback is hidden during SubHub takeover; the
# Android wake path temporarily disables takeover before forwarding a real tap.
assert "display:none!important;visibility:hidden!important;opacity:0!important;pointer-events:none!important;}" in site
assert "media-control-bar" in js
assert "button[aria-label*=\"play\" i]" in js[js.find("function scrubVidSrcProviderUiV3234"):js.find("function restoreVidSrcProviderUiV3234")]
assert "root.querySelectorAll('button,[role=\"button\"]')" in js
assert "modal && box" in site

# Direct stream 322.3.41 keeps 322.3.36 playback and polishes only its UI.
direct_player = (root/'app/src/main/java/com/artaq/subhub/DirectStreamPlayer.java').read_text(encoding='utf-8')
assert 'subhub_direct_stream_ui_v1' in direct_player
assert 'tool("✕"' in direct_player
assert 'menuButton.setText("⋮")' in direct_player
assert 'tool("HD"' in direct_player
assert 'tool("CC"' in direct_player
assert 'installSubtitleGesture' in direct_player
assert 'A−' in direct_player and 'A+' in direct_player
assert 'WindowInsets.Type.systemBars()' in direct_player
assert 'SYSTEM_UI_FLAG_IMMERSIVE_STICKY' in direct_player
assert 'HlsMediaSource.Factory(sourceFactory)' in direct_player
assert 'player.setMediaSource' in direct_player
assert 'player.play()' in direct_player
assert 'beginCapture()' in direct_player

# Direct stream 322.3.45 rounded per-line subtitle background with adjustable opacity.
direct_player = (root/'app/src/main/java/com/artaq/subhub/DirectStreamPlayer.java').read_text(encoding='utf-8')
assert 'menuButton.setText("⋮")' in direct_player
assert 'quickStrip.setVisibility(View.GONE)' in direct_player
assert 'showSubtitleSizePercent' in direct_player
assert 'Math.max(dp(6), root.getHeight() * subtitlePosition / 100)' in direct_player
assert 'subtitlePosition = Math.max(0, Math.min(72' in direct_player
assert 'showColorOptions' in direct_player
assert 'addColorDot' in direct_player
assert 'GradientDrawable.OVAL' in direct_player
assert 'showTransientValue("▭  ملاءمة"' in direct_player
assert 'status.setVisibility(View.GONE)' in direct_player
assert 'subtitle_color' in direct_player
assert 'new SubtitlePillTextView(activity)' in direct_player
assert 'background_opacity' in direct_player
assert 'adjustSubtitleBackground' in direct_player
assert 'showTransientValue("خلفية "' in direct_player
assert 'applySubtitleText' in direct_player
assert 'public boolean handleBack()' in direct_player



# 322.3.62: Moviesmod subscriber reuse keeps fresh per-device stream capture.
assert 'readAsset("moviesmod_stream.js")' in main
assert '"moviesmod".equals(mode)' in main
assert 'fallbackSourceUrl = "https://moviesmod.gd/" + kind + "/" + Uri.encode(tmdbId)' in main
# 322.3.123: Moviesmod opens on the chosen name of its names (moviesmod.gd is the saved form).
assert 'allowedHost = fboxHostValue.isEmpty() ? mmHostValue : fboxHostValue;' in main
# 322.3.98: purple/teal keys unchanged; open cards add their own prefix.
assert 'resumeKey = (vidnestMode ? "vidnest_" : openMode ? mode + "_" : "moviesmod_") + stableKey' in main
assert 'resumePrefKey' in direct
assert 'persistResume(false)' in direct
assert 'prefs.edit().putLong(key, position).apply()' in direct
assert 'player.seekTo(pendingResumeMs)' in direct
assert 'onCreateWindow' in direct
assert 'url.toLowerCase(Locale.ROOT).contains(".m3u8")' in direct
assert 'subhub-moviesmod-stream-button' in moviesmod
assert 'checkOwnerAccess' in moviesmod
assert "mode: 'moviesmod'" in moviesmod
assert 'external_source=imdb_id' in moviesmod
assert 'Moviesmod — تجريبي' in moviesmod


# Saved provider choice is centralized; no transient M3U8/cookies are persisted.
assert 'moviesmodServerKey' in moviesmod
assert 'moviesmodServerLabel' in moviesmod
assert 'moviesmodEnabled' in moviesmod
assert 'isSubscriber' in moviesmod
assert 'مشاهدة بالتطبيق' in moviesmod
assert '__subhubMoviesmodServerSelected' in moviesmod
assert 'serverKey: useSaved ? saved.key' in moviesmod
assert 'openMoviesmod(true)' in moviesmod
assert 'preferredServerKey' in main
assert 'preferredServerLabel' in main
assert 'serverSelected(String key, String label, String serverPageUrl, String embedUrl)' in direct
assert 'SubHubSourceChoice' in direct
assert 'providerPickerScript' in direct
assert 'ادخل يدوياً إلى الفيلم وأغلق الإعلانات' in direct
assert 'جارٍ الاتصال بالسيرفر' in direct
assert 'تم العثور على البث' in direct
assert 'جارٍ تشغيل الفيديو' in direct
assert 'preferredServerLabel' in direct


# 322.3.63: durable Moviesmod configuration and explicit owner controls.
assert 'ensureServerConfig' in moviesmod
assert "db.collection('subtitles').doc(movieId).get()" in moviesmod
assert 'savePendingServer' in moviesmod
assert 'deleteSavedServer' in moviesmod
assert "row.appendChild(control('حفظ'" in moviesmod
assert "row.appendChild(control('تعديل'" in moviesmod
assert "row.appendChild(control('حذف'" in moviesmod
assert 'pendingChoice' in moviesmod
assert 'لم يتم تغيير الإعداد السابق' in moviesmod
assert 'serverState.loaded' in moviesmod
assert 'autoPick(String token, String rawLabel' in direct
assert 'dispatchProviderTap' in direct
assert 'MotionEvent.ACTION_DOWN' in direct
assert 'window.SubHubSourceChoice.autoPick' in direct


# 322.3.64: save only real Moviesmod server names, then auto-select and auto-start robustly.
assert 'validServerLabel' in moviesmod
assert 'Watch now' not in moviesmod.split('validServerLabel',1)[1].split('normalizeSaved',1)[0]
assert 'showSavePrompt' in moviesmod
assert 'هل تريد حفظ هذا السيرفر لهذا الفيلم؟' in moviesmod
assert 'الإعداد المحفوظ السابق غير صالح' in moviesmod
assert 'isProviderServerLabel' in direct
assert 'isProviderServerLabel' in direct
assert 'playTarget(String token' in direct
assert 'window.SubHubSourceChoice.playTarget' in direct
assert 'providerPlayScript' in direct
assert 'querySelectorAll(\'video\')' in direct


# 322.3.65: Moviesmod server buttons are discovered from DOM and selected by identity.
assert 'showServerChooser' in direct
assert 'سيرفرات Moviesmod' in direct
assert 'window.__subhubSelectProvider' in direct
assert 'SubHubSourceChoice.servers' in direct
assert 'SubHubSourceChoice.resolved' in direct
assert 'data-server' in direct
assert 'data-src' in direct
assert 'data-url' in direct
assert 'data-embed' in direct
assert 'MutationObserver' in direct
assert 'openResolvedProvider' in direct
assert 'providerPlayScript' in direct
assert 'resolvedMainHost' in direct
assert 'ادخل يدوياً إلى الفيلم وأغلق الإعلانات' in direct
assert 'window.__subhubMoviesmodInstalledV3276' in moviesmod
assert 'validServerLabel' in moviesmod


# 322.3.66: owner manually clears the page first, then explicitly fetches servers.
assert 'manualServerButton' in direct
assert 'جلب السيرفرات' in direct
assert 'requestServerList' in direct
assert 'window.__subhubRequestServerList' in direct
assert 'probe.setAlpha(interactiveSource ? 1.0f : 0.0f)' in direct
assert 'view details' in direct
assert 'peachify' in direct
assert '((!interactiveSource && autoServer) || !selectedProviderLabel.isEmpty())' in direct
assert 'External top-frame navigations are ads often enough' in direct
assert 'resolvedMainHost = host;' in direct


# 322.3.67: saved-server mode stays visible through ads/verification and waits until the target button is actually clickable.
assert 'probe.setAlpha(interactiveSource ? 1.0f : 0.0f)' in direct
assert 'ادخل يدوياً إلى الفيلم وأغلق الإعلانات' in direct
assert 'جارٍ الاتصال بالسيرفر' in direct
assert 'function uncovered(e)' in direct
assert 'elementFromPoint' in direct
# 322.3.123: the server is pressed inside the page (its own click) even when a pop-up covers
# it; a finger tap stays only the fallback, and only on a button nothing covers.
assert 'var hit=hits.find(shown)||hits[0];' in direct
assert 'if(reportFrame(lab)||!uncovered(hit))return;' in direct
assert 'tries>=240' in direct
assert 'External top-frame navigations are ads often enough' in direct


# 322.3.68: save the Moviesmod server-list page itself and reopen there by default.
assert 'discoveredServerPageUrl' in direct
assert 'serverPage(String token, String rawUrl)' in direct
assert 'window.SubHubSourceChoice.serverPage' in direct
assert 'location.href' in direct
assert 'listener.serverSelected(key, label, discoveredServerPageUrl, resolvedProviderUrl)' in direct
assert 'serverPageUrl' in main
assert 'savedServerPageUrl' in main
assert 'base = savedUri;' in main and 'resolvedSourceUrl = base.toString();' in main   # 322.3.123: on the chosen name
assert 'moviesmodServerPageUrl' in moviesmod
assert 'normalizeServerPageUrl' in moviesmod
assert 'serverPageUrl: startPageUrl' in moviesmod
assert 'rawPageUrl' in moviesmod
assert 'pageUrl' in moviesmod


# 322.3.69: owner setup is manual; subscriber reuse is hidden and automatic.
assert 'interactiveSource' in main
assert 'config.optBoolean("interactiveSource", false)' in main
assert 'interactiveSource: owner' in moviesmod
assert 'const useSaved = subscriber && !forceManual' in moviesmod
assert 'manualChoice: owner' in moviesmod
assert "const ownerAuto = owner && !forceManual && savedComplete" in moviesmod
assert 'this.interactiveSource = interactiveSource' in direct
assert 'this.autoServer = !this.interactiveSource && !this.preferredServerLabel.isEmpty()' in direct
assert 'serverListRequested = true' in direct
assert '!serverListRequested' in direct
assert 'probe.setAlpha(interactiveSource ? 1.0f : 0.0f)' in direct
assert 'ادخل يدوياً إلى الفيلم وأغلق الإعلانات' in direct
assert 'subscriberStartupAttempt' in direct


# 322.3.70: subscriber startup timeout/retry flow.
assert 'subscriberStartupAttempt' in direct
assert 'startSubscriberStartupTimer' in direct
assert 'subscriberStartupTick' in direct
assert 'retrySubscriberStartup' in direct
assert 'if (phase == 4) timeoutMs = 30000L' in direct
assert 'startupStageText' in direct
assert 'subscriberStartupAttempt >= 2' in direct
assert 'interactiveSource ? 1200L : 300L' in direct
assert 'playbackReady = true' in direct


# 322.3.71: compact stable startup panel with readable real stages.
assert "button.textContent = '🎬 مشاهدة بالتطبيق'" in moviesmod
assert "button.style.cssText = 'cursor:pointer';" in moviesmod  # 322.3.81: compact pill size
assert 'new FrameLayout.LayoutParams(dp(310), dp(68)' in direct
assert 'subscriberStageBase' in direct
assert 'renderSubscriberStage' in direct
assert 'dots.append("•")' in direct
assert 'جارٍ فتح صفحة السيرفرات' in direct
assert 'جارٍ اختيار السيرفر' in direct
assert 'جارٍ فتح السيرفر' in direct
assert 'جارٍ جلب رابط الفيديو' in direct
assert 'جارٍ تجهيز الفيديو' in direct
assert 'جارٍ تشغيل الفيديو' in direct
assert 'جارٍ إعادة المحاولة' in direct
assert 'تعذّر تشغيل هذا السيرفر.' in direct   # 322.3.108: final message (was «تعذّر الاتصال. حاول مرة أخرى.»)


# 322.3.72: keep the provider/player visible inside a touchable video-sized preview.
assert 'applySubscriberProbePreviewLayout' in direct
assert 'new FrameLayout.LayoutParams(dp(320), dp(180), Gravity.CENTER)' in direct
assert 'probe.setAlpha(interactiveSource ? 1.0f : 0.0f)' in direct
assert 'probe.setBackgroundColor(Color.BLACK)' in direct
assert 'اضغط داخل شاشة الفيديو للتشغيل.' in direct
assert 'subscriberStartupPhase <= 4 && probe != null' in direct
assert 'if (phase == 4) timeoutMs = 30000L' in direct
assert 'root.post(this::applySubscriberProbePreviewLayout)' in direct
# 322.3.106: verify cards keep the page visible while the owner chooses.
assert 'probe.setAlpha(interactiveSource && !verifyFirst ? 0.02f : 1.0f)' in direct


# 322.3.73: subscriber sees only the focused player, and the WebView receives real touches.
assert 'import android.view.ViewParent;' in direct
assert 'probe.setClickable(true)' in direct
assert 'probe.setFocusableInTouchMode(true)' in direct
assert 'requestDisallowInterceptTouchEvent(true)' in direct
assert 'root.addView(probe, p)' in direct
assert 'subscriberProviderVisible' in direct
assert 'providerFocusScript' in direct
assert "__subhub_player_host" in direct
assert "document.querySelectorAll('iframe[src],video')" in direct
assert "e.style.cssText+=" in direct
assert "if(e.parentNode!==h)h.appendChild(e)" in direct
assert 'probe.setAlpha(subscriberProviderVisible ? 1.0f : 0.0f)' in direct
assert 'setTimeout(kick,120)' in direct
assert 'اضغط داخل شاشة الفيديو للتشغيل.' in direct


# 322.3.76: keep subtitle timing on ExoPlayer content time and save the
# working VidUp/VidFast server + subtitle correction while the movie is visible.
assert 'player.getContentPosition()' in direct
assert 'void saveRequested(long subtitleOffsetMs)' in direct
assert 'addCompactQuick("حفظ", this::requestSave)' in direct
assert 'public void setSubtitleOffsetMs(long value)' in direct
assert 'public void showSaveResult' in direct
assert '__subhubMoviesmodSaveNow' in moviesmod
assert 'moviesmodSubtitleOffsetMs' in moviesmod
assert 'directStreamSaveResult' in main
assert 'subtitleOffsetMs: Number(saved.subtitleOffsetMs || 0)' in moviesmod
assert 'supportedServerLabel' in moviesmod


# 322.3.77: any server for the owner test phase, in-player save/another-server
# decision bar, and owner re-entry plays the saved server automatically.
assert 'return compact.length >= 2;' in moviesmod
assert 'window.__subhubMoviesmodReopenManual' in moviesmod
assert 'ownerMode: owner' in moviesmod
assert 'openChooser: owner && !ownerAuto' in moviesmod
assert 'void reopenManual(String serverPageUrl)' in direct
assert 'private void buildDecisionBar()' in direct
assert 'تم التقاط رابط الفيديو ✓' in direct
assert 'chip("سيرفر آخر", 15, this::chooseAnotherServer)' in direct
assert 'age < 30000L' not in direct
assert 'config.optBoolean("ownerMode", interactiveSource)' in main
assert '"window.__subhubMoviesmod"' in main and 'ReopenManual(' in main



# 322.3.78: the owner's save also stores the server player URL for the website.
assert 'resolvedProviderUrl = u.toString();' in direct
assert 'moviesmodEmbedUrl: next.embedUrl' in moviesmod
assert 'function normalizeEmbedUrl(raw)' in moviesmod
assert 'rawEmbedUrl' in moviesmod
assert 'JSONObject.quote(embedUrl == null ? "" : embedUrl)' in main


# 322.3.79: yellow card for vidsrc.mov / VidSrc.fyi replaces the old direct button.
vsm=(root/'app/src/main/assets/vsm_stream.js').read_text(encoding='utf-8')
direct_js=(root/'app/src/main/assets/direct_stream.js').read_text(encoding='utf-8')
assert 'readAsset("vsm_stream.js")' in main
assert "mode: 'vsm'" in vsm
# 322.3.99: the yellow card is open to any server (owner's decision); checked below.
assert 'vsmEmbedUrl: next.embedUrl' in vsm
assert 'moviesmodEnabled' not in vsm
assert "embedUrl: (useSaved || ownerAuto) ? saved.embedUrl : ''" in vsm
assert "button.textContent = '▶ مشاهدة بالتطبيق'" in vsm
assert 'window.__subhubVsmReplacesDirect' in direct_js
assert '"vsm".equals(mode)' in main
assert 'ownerMode, openChooser, directEmbedUrl,' in main
assert 'openResolvedProvider(preferredServerLabel, directEmbedUrl);' in direct
assert 'private void scheduleDirectKick(WebView v)' in direct
print('322.3.79 checks OK')

# 322.3.80: clean cover while the hidden provider loads, real stage text, highlighted choices.
assert 'private void showDirectCover()' in direct
assert 'removeDirectCover();' in direct
assert '"جارٍ الاتصال بالسيرفر"' in direct and '"جارٍ تحضير الفيديو"' in direct
assert 'private void scheduleDirectReveal(WebView v)' in direct
assert 'private void markSelected(TextView v)' in direct
assert 'if (isQualityChoiceActive(choices.get(i))) markSelected(item);' in direct
assert 'if (captions && selectedSubtitle == index) markSelected(item);' in direct
print('322.3.80 checks OK')

# 322.3.81: animated cover, wrapping list rows, compact subscriber buttons.
assert 'private void setDirectCoverStage(String text)' in direct
assert 'private void fadeOutDirectCover()' in direct
assert 'private TextView listRow(TextView v)' in direct
assert 'stopCoverAnimators();' in direct
assert "button.style.cssText = 'border-color:#e8b544;cursor:pointer';" in vsm
assert 'min-height:72px' not in moviesmod
print('322.3.81 checks OK')

# 322.3.82: owner pins the selected subtitle to the server being watched.
assert 'void pinRequested(int index);' in direct
assert 'public void setPinned(int index, boolean pinned)' in direct
assert 'listener.pinRequested(pinIndex);' in direct
assert 'directStreamPinned(String token, String session, int index, boolean pinned)' in main
assert 'PinNow && ' in main
assert "window.__subhubVsmPinNow" in vsm and "setSubServerKeyV382('vsm')" in vsm
assert "window.__subhubMoviesmodPinNow" in moviesmod and "setSubServerKeyV382('moviesmod')" in moviesmod
assert 'pinned: x.pinned === true' in vsm and 'pinned: x.pinned === true' in moviesmod
print('322.3.82 checks OK')

# 322.3.82: downloaded subtitles keep their name (no ".txt" appended by MIME type).
assert 'private String downloadMimeFor(String fileName, String mimeType)' in main
assert 'values.put(MediaStore.MediaColumns.MIME_TYPE, downloadMimeFor(fileName, mimeType));' in main
assert 'request.setMimeType(downloadMimeFor(fileName, mimeType));' in main
print('322.3.82 download-name check OK')

# 322.3.83: Moviesmod / yellow cards for series episodes (SubHub Web v383).
series=(root/'app/src/main/assets/series_stream.js').read_text(encoding='utf-8')
assert 'readAsset("series_stream.js")' in main
assert main.index('readAsset("vsm_stream.js")') < main.index('readAsset("series_stream.js")')
assert 'window.SubHubSeriesAppSourceV383' in series
assert "kind: 'tv'" in series
assert "'https://moviesmod.gd/watch/tv/'" in series
assert 'seriesAppSources: { [key]: { [mode]: next } }' in series
assert "['Moviesmod', 'moviesmod'], ['Vsm', 'vsm']" in series
# 322.3.99: series yellow mode is open too; checked below.
print('322.3.83 series checks OK')

# 322.3.84: teal Vidnest card — a full copy of the purple flow for Vidnest only.
vidnest=(root/'app/src/main/assets/vidnest_stream.js').read_text(encoding='utf-8')
assert 'readAsset("vidnest_stream.js")' in main
assert main.index('readAsset("vsm_stream.js")') < main.index('readAsset("vidnest_stream.js")') < main.index('readAsset("series_stream.js")')
assert "mode: 'vidnest'" in vidnest
assert "return compact === 'vidnest';" in vidnest
assert 'vidnestServerPageUrl: next.pageUrl' in vidnest
assert 'moviesmodEnabled' not in vidnest and 'vsmEnabled' not in vidnest
assert 'window.__subhubVidnestServerSelected' in vidnest and 'window.__subhubVidnestPinNow' in vidnest
assert "setSubServerKeyV382('vidnest')" in vidnest
assert "button.style.cssText = 'border-color:#14b8a6;cursor:pointer';" in vidnest
assert 'final boolean vidnestMode = "vidnest".equals(mode);' in main
assert '"window.__subhubVidnest"' in main
assert '(vidnestMode ? "vidnest_" : openMode ? mode + "_" : "moviesmod_") + stableKey' in main
assert 'ownerMode, openChooser, directEmbedUrl, vidnestMode,' in main
assert 'static boolean isVidnestProxyPlaylist(Uri u)' in direct
assert 'return heldPlaylistResponse();' in direct
assert 'private void captureVidnest(String url, Map<String,String> headers, boolean proxyFormat)' in direct
assert 'long startDelay = vidnestMode ? 150L : (interactiveSource ? 1200L : 300L);' in direct
assert 'final boolean minimalHeaders = vidnestMode && vidnestCandidateIsProxy;' in direct
assert 'vidnestDiagnostic(error)' in direct
# the purple and yellow cards are untouched
assert "mode: 'moviesmod'" in moviesmod and "mode: 'vsm'" in vsm
assert 'vidnest' not in moviesmod and 'vidnest' not in vsm
print('322.3.84 vidnest checks OK')

# 322.3.85: probe the Vidnest playlist before ExoPlayer; owner sees the report in the bar.
assert 'private Object[] probeVidnestPlaylist(String url, String browserUa, String referer, String origin)' in direct
assert 'static String probeOnce(String url, VidnestPlan plan, String referer, String origin)' in direct
assert 'new VidnestPlan("بلا Referer", browserUa, false)' in direct
assert 'private void startExoPlayer(String userAgent, String referer, String origin)' in direct
assert 'if (vidnestMode && vidnestCandidateIsProxy && vidnestPlan == null) {' in direct
assert 'private void vidnestProbeFailed()' in direct
assert '(failureDetail.isEmpty() ? "" : "\\n\\n" + failureDetail)' in direct
assert 'failureDetail = vidnestDiag;' in direct
print('322.3.85 vidnest probe checks OK')

# 322.3.86: collect every Vidnest /proxy playlist and pick the first that passes a deep check.
assert 'private void evaluateVidnestCandidates()' in direct
assert 'static Object[] deepProbeVidnest(String url, String browserUa, String referer, String origin)' in direct
assert 'static String deepCheck(String url, VidnestPlan plan, String referer, String origin)' in direct
assert 'if (playlist.contains("#EXT-X-STREAM-INF")) {' in direct
assert 'handler.postDelayed(this::evaluateVidnestCandidates, VIDNEST_COLLECT_MS);' in direct
assert 'vidnestCandidates.add(url);' in direct
print('322.3.86 vidnest deep-check checks OK')

# 322.3.87: Web-Video-Cast-like link list for the owner + clean master of working qualities.
assert 'static ArrayList<VidnestStream> analyzeVidnestLink(String url, String browserUa, String referer, String origin)' in direct
assert 'private void showVidnestList()' in direct
assert 'private void playVidnestStream(VidnestStream r)' in direct
assert 'static String masterDataUri(' in direct
assert 'static String cleanStreamInf(String inf, java.util.Map<String,String> audioOk)' in direct
assert 'vidnestMode ? new DefaultDataSource.Factory(activity, data) : data;' in direct
assert 'import androidx.media3.datasource.DefaultDataSource;' in direct
assert '// 322.3.87: the hidden provider player never plays in the background.' in direct
print('322.3.87 vidnest link list checks OK')

# 322.3.88: the Vidnest card plays for every non-owner viewer (visitors, members, subscribers).
assert '// 322.3.88: the Vidnest card plays for everyone who is not the owner' in vidnest
assert "isSubscribed === 'function'" not in vidnest
print('322.3.88 vidnest for everyone checks OK')

# 322.3.89: ✕ replaces «الجودة» in the VidSrc player; scroll rescue when no player is open.
assert "q.setAttribute('data-provider-action', 'close');" in bridge_js if 'bridge_js' in dir() else True
_sb=(root/'app/src/main/assets/site_bridge.js').read_text(encoding='utf-8')
assert "q.setAttribute('data-provider-action', 'close');" in _sb
assert "q.textContent = '✕';" in _sb
assert 'window.__subhubScrollRescueV3289' in _sb
assert "q.textContent = 'الجودة';" not in _sb
print('322.3.89 close button + scroll rescue checks OK')

# 322.3.90: owner-only «🩺 فحص التمرير» + native touch reset.
assert 'window.__subhubShowScrollDiagV3289 = show;' in _sb
assert "item.textContent = '🩺 فحص التمرير';" in _sb
assert 'public String scrollDiag(String token)' in main
assert 'public void resetTouchState(String token)' in main
assert 'MotionEvent.ACTION_CANCEL' in main
print('322.3.90 scroll diagnostic checks OK')

# 322.3.91: detached <a download> keeps its name; subtitle sites open from target=_blank.
assert "HTMLAnchorElement.prototype.click=function(){" in main
assert 'if(!this.isConnected&&handle(this,null))return;' in main
assert 'static boolean isAllowedSubtitleSite(String url)' in main
assert 'if (isAllowedSubtitleSite(u)) openExternal(u);' in main
print('322.3.91 subtitle download/open checks OK')

# 322.3.92: subtitle file picker shows every file; exclusive subtitles play as 4-second pieces.
assert 'if (isSubtitleFileChooser(fileChooserParams)) {' in main
assert 'private static boolean isSubtitleFileChooser(WebChromeClient.FileChooserParams params)' in main
assert '+ readAsset("segment_pump.js") + "\\n"' in main
assert 'public void directStreamSegmentMode(String token, String session, int index, String notice)' in main
assert 'public void directStreamSegmentCues(String token, String session, int index, double bucket, String raw, String notice)' in main
assert 'public void segmentRequested(int index, double time)' in main
_dsp=(root/'app/src/main/java/com/artaq/subhub/DirectStreamPlayer.java').read_text(encoding='utf-8')
assert 'void segmentRequested(int index, double time);' in _dsp
assert 'if (segmentMode) requestSegment(time);' in _dsp
assert 'public void enableSegmentMode(int index, String notice)' in _dsp
assert 'public void setSegmentCues(int index, long bucket, JSONArray value, String notice)' in _dsp
_pump=(root/'app/src/main/assets/segment_pump.js').read_text(encoding='utf-8')
assert 'window.__subhubSegmentPumpV3292 = {' in _pump
assert 'window.__subhubDirectSegment = function (session, index, time)' in _pump
for _n in ['moviesmod_stream.js','vsm_stream.js','vidnest_stream.js','series_stream.js','direct_stream.js']:
    _t=(root/'app/src/main/assets'/_n).read_text(encoding='utf-8')
    assert 'pump.start(session, index, loaded.access, function () { return active === state; })) return;' in _t, _n
print('322.3.92 subtitle picker + exclusive segment checks OK')


# 322.3.93: subtitle background is visibly pill-rounded, opacity moves in small
# 5% steps, and the screen-mode button changes with the active mode.
assert 'adjustSubtitleBackground(-SUBTITLE_BG_STEP)' in _dsp
assert 'adjustSubtitleBackground(SUBTITLE_BG_STEP)' in _dsp
assert 'SubtitlePillTextView' in _dsp
assert 'resizeModeButtonLabel()' in _dsp
assert 'return "↔";' in _dsp
assert 'return "100%";' in _dsp
assert 'refreshResizeModeButton();' in _dsp
print('322.3.93 subtitle UI polish checks OK')


# 322.3.96: the pill is drawn by SubtitlePillTextView.onDraw (before TextView
# clips to the text area), so both ends are true half circles; opacity ladder
# from 322.3.95 stays. Pixel checks run on the emulator (SubtitlePillTest).
_pill = (root/'app/src/main/java/com/artaq/subhub/SubtitlePillTextView.java').read_text(encoding='utf-8')
assert 'static final int SUBTITLE_BG_STEP = 10;' in _dsp
assert 'private final SubtitlePillTextView subtitle;' in _dsp
assert 'subtitle.setPillColor(Color.argb(subtitleBackgroundAlpha(), 0, 0, 0));' in _dsp
assert 'RoundedLineBackgroundSpan' not in _dsp and 'LineBackgroundSpan' not in _dsp
assert _pill.index('drawPills(canvas);') < _pill.index('super.onDraw(canvas);')
assert 'VIEW_PAD_SIDE_DP = PILL_SIDE_DP + 4' in _pill and 'VIEW_PAD_EDGE_DP = PILL_EDGE_DP + 3' in _pill
assert 'float radius = pillRect.height() / 2f;' in _pill
assert 'layout.getLineLeft(i)' in _pill and 'layout.getLineRight(i)' in _pill
assert 'setShadowLayer' not in _pill.split('private void drawPills')[1].split('private int verticalOffset')[0]
assert (root/'app/src/androidTest/java/com/artaq/subhub/SubtitlePillTest.java').exists()
_m = re.search(r'Math\.round\(220\.0 \* Math\.pow\(level, ([0-9.]+)\)\)', _dsp)
assert _m, 'alpha curve'
def _alpha(o, k=float(_m.group(1))):
    return 0 if o <= 0 else max(8, min(220, round(220 * (o / 100) ** k)))
_lad = [_alpha(o) for o in range(0, 101, 10)]
assert all(x < y for x, y in zip(_lad, _lad[1:])), _lad
assert _lad[1] <= 16 and _lad[-1] <= 220, _lad
print('322.3.96 true pill subtitle + opacity ladder checks OK')

# 322.3.97: a stuck native R2 upload can always be resumed or deleted.
bridge97=(root/'app/src/main/java/com/artaq/subhub/R2NativeBridge.java').read_text(encoding='utf-8')
svc97=(root/'app/src/main/java/com/artaq/subhub/R2UploadService.java').read_text(encoding='utf-8')
assert 'public synchronized String discardUpload()' in bridge97
assert 'public synchronized String resumeUpload()' in bridge97
assert 'activity.stopService(' in bridge97          # never startForegroundService for a cancel
assert '"stalled"' in bridge97 and 'R2UploadService.WORKER_ALIVE' in bridge97
assert 'static volatile boolean WORKER_ALIVE' in svc97
assert 'PART_STALL_MS' in svc97 and 'conn.disconnect()' in svc97
assert 'ensureReadable(' in svc97 and 'FatalLocalException' in svc97
assert 'resurrect' in svc97                          # saveState guard against a discarded job
assert 'qlR2NativeCtrlV397' in r2upload and 'bridge.discardUpload()' in r2upload
assert "state.status === 'none'" in r2upload
print('322.3.97 R2 upload unstick checks OK')

# 322.3.98: two open-server cards (open1/open2) beside the untouched old cards.
for key in ('open1', 'open2'):
    card=(root/('app/src/main/assets/%s_stream.js' % key)).read_text(encoding='utf-8')
    assert ("mode: '%s'," % key) in card
    assert ('%sEnabled' % key) in card and 'vidnestEnabled' not in card
    assert 'return cleanServerLabel(raw).length >= 2;' in card     # no server-name list
    assert 'return !isOwner();' in card                              # everyone may watch
    assert "setSubServerKeyV382('%s')" % key in card
    assert ('readAsset("%s_stream.js")' % key) in main
dsp97=(root/'app/src/main/java/com/artaq/subhub/DirectStreamPlayer.java').read_text(encoding='utf-8')
assert 'private final boolean openServers;' in dsp97
assert 'window.__subhubOpenTapV3298' in dsp97 and 'function legacyEls()' in dsp97
assert '"open1".equals(mode) || "open2".equals(mode)' in main
assert 'openMode || "moviesmod".equals(mode) || "vsm".equals(mode),' in main  # 322.3.99 widened
vid98=(root/'app/src/main/assets/vidnest_stream.js').read_text(encoding='utf-8')
assert "return compact === 'vidnest';" in vid98                     # old teal card unchanged
print('322.3.98 open server cards checks OK')

# 322.3.99: the yellow card is shown to everyone (not only subscribers).
vsm99=(root/'app/src/main/assets/vsm_stream.js').read_text(encoding='utf-8')
assert "function isSubscriber() {\n    return !isOwner();\n  }" in vsm99
mm99=(root/'app/src/main/assets/moviesmod_stream.js').read_text(encoding='utf-8')
assert "function isSubscriber() {\n    return !isOwner();\n  }" in mm99
assert 'return cleanServerLabel(raw).length >= 2;' in vsm99 and "compact === 'vidsrcmov'" not in vsm99
ser99=(root/'app/src/main/assets/series_stream.js').read_text(encoding='utf-8')
assert "if (mode === 'vidnest') return compact === 'vidnest';" in ser99   # 322.3.100: yellow open, teal Vidnest-only
assert 'openMode || "moviesmod".equals(mode) || "vsm".equals(mode),' in main
vid99=(root/'app/src/main/assets/vidnest_stream.js').read_text(encoding='utf-8')
assert "return compact === 'vidnest';" in vid99          # teal stays Vidnest-only
print('322.3.99 open system for purple and yellow checks OK')

# 322.3.100: series episodes get all five app cards, for everyone.
ser100=(root/'app/src/main/assets/series_stream.js').read_text(encoding='utf-8')
assert "const SERIES_MODES = ['moviesmod', 'vsm', 'vidnest', 'open1', 'open2', 'open3', 'open4'];" in ser100   # 322.3.106: + verify cards
assert "['Open1', 'open1'], ['Open2', 'open2'], ['Open3', 'open3'], ['Open4', 'open4']].forEach" in ser100 and "['Vidnest', 'vidnest']," in ser100
assert "if (mode === 'vidnest') return compact === 'vidnest';" in ser100
assert "function isSubscriber() {\n    return !isOwner();\n  }" in ser100
assert main.index('readAsset("open2_stream.js")') < main.index('readAsset("series_stream.js")')   # film handlers first
print('322.3.100 series five app cards checks OK')

# 322.3.101: owner sheet of an episode server gets a fourth «إلغاء» button.
ser101 = (ROOT / 'app/src/main/assets/series_stream.js').read_text(encoding='utf-8') if 'ROOT' in globals() else open('app/src/main/assets/series_stream.js', encoding='utf-8').read()
assert "grid-template-columns:1fr 1fr 1fr 1fr" in ser101
assert "row.appendChild(btn('إلغاء'" in ser101
print('322.3.101 sheet cancel checks OK')

# 322.3.102: the replacement ✕ in the embed bar is centred and sized like the other buttons.
sb102 = open('app/src/main/assets/site_bridge.js', encoding='utf-8').read()
assert "#embedTopBar .subhub-provider-shortcut-v3231{display:inline-flex!important;align-items:center!important;justify-content:center!important" in sb102
print('322.3.102 centered close checks OK')

# 322.3.103: ⏱ sync window replaces −.5/+.5; timing is per film/episode per card.
d103 = open('app/src/main/java/com/artaq/subhub/DirectStreamPlayer.java', encoding='utf-8').read()
m103 = open('app/src/main/java/com/artaq/subhub/MainActivity.java', encoding='utf-8').read()
assert 'addCompactQuick("⏱ توقيت", this::openSyncPanel)' in d103
assert 'addCompactQuick("−.5"' not in d103 and 'addCompactQuick("+.5"' not in d103
assert 'void syncPinRequested(long subtitleOffsetMs);' in d103
assert 'static long syncOffsetFor(long positionMs, double cueStartSec)' in d103
assert '"sync_offset_" + syncScope' in d103
assert 'syncScopeValue = mode + "_" + stableKey;' in m103
assert 'directStreamPlayer.setSyncScope(syncScopeValue);' in m103
assert 'public void directStreamSyncPinned(' in m103
for f, w in [('moviesmod','Moviesmod'),('vsm','Vsm'),('vidnest','Vidnest'),('open1','Open1'),('open2','Open2')]:
    src = open('app/src/main/assets/%s_stream.js' % f, encoding='utf-8').read()
    assert 'window.__subhub%sSyncPinNow = async function' % w in src
    assert '%sSubtitleOffsetMs: offset,' % f in src
s103 = open('app/src/main/assets/series_stream.js', encoding='utf-8').read()
assert "window[prefix + 'SyncPinNow'] = async function" in s103
print('322.3.103 sync window checks OK')

# 322.3.104: a new film must never inherit the previous film's doc. While the
# site prepares the next page window._lastRenderedMovieDoc is still the old
# film; the cards fall back to THIS film's cached doc only, treat "no
# Firestore document" as empty, and never write a doc under another film's id.
for f in ['moviesmod', 'vsm', 'vidnest', 'open1', 'open2']:
    src = open('app/src/main/assets/%s_stream.js' % f, encoding='utf-8').read()
    assert 'function ownMovieDoc(movieId)' in src, f
    assert "return normalizeSaved(ownMovieDoc(movieId || currentMovieId()) || {});" in src, f
    assert 'let saved = fallbackSaved(movieId);' in src, f
    assert 'else if (snap) saved = normalizeSaved({});' in src, f
    assert 'const own = ownMovieDoc(movieId);\n      if (!own) return;' in src, f
    assert 'return normalizeSaved(movieDoc());' not in src, f
    assert 'Object.assign({}, movieDoc(), {\n' not in src, f
s104 = open('app/src/main/assets/series_stream.js', encoding='utf-8').read()
assert 'function ownMovieDoc(movieId)' in s104
assert 'if (!own && !isCurrent) return;' in s104
assert "if (own && typeof _mdocPut === 'function') _mdocPut(movieId, doc);" in s104
print('322.3.104 film doc isolation checks OK')

# 322.3.105: an episode's CC list = official subtitle + the episode's member
# (community) subtitles; start = server pin → episode default → official;
# the owner can pin a subtitle per server per episode.
s105 = open('app/src/main/assets/series_stream.js', encoding='utf-8').read()
assert "_seriesMemberSubsForV393(season, episode)" in s105
assert "async function ensureEpisodeMembers(movieId)" in s105
assert "await ensureEpisodeMembers(selected.id);" in s105
assert "const defaultIndex = applyDefaults(catalog, mode, season, episode);" in s105
assert "defaultIndex: defaultIndex" in s105
assert "pinned: x.pinned === true" in s105
assert "movieDoc().seriesSubDefaults" in s105
assert "seriesAppSources: { [key]: { [mode]: { subtitlePin: value } } }" in s105
assert "window[prefix + 'PinNow'] = async function (session, index)" in s105
assert "'ترجمة الحلقة هي الافتراضية تلقائياً'" not in s105
print('322.3.105 episode subtitle checks OK')

# 322.3.106: verify-first cards «مفتوح ٣/٤ — تحقّق» + fixed card order.
order106 = open('app/src/main/assets/app_cards_order.js', encoding='utf-8').read()
assert "moviesmod: 1" in order106 and "vsm: 2" in order106 and "open1: 3" in order106
assert "vidnest: 4" in order106 and "open2: 5" in order106 and "open3: 6" in order106 and "open4: 7" in order106
for f in ['moviesmod', 'vsm', 'vidnest', 'open1', 'open2', 'open3', 'open4']:
    src = open('app/src/main/assets/%s_stream.js' % f, encoding='utf-8').read()
    assert "window.__subhubPlaceAppCardV3106(grid, card, '%s');" % f in src, f
    assert "window.__subhubPlaceAppCardV3106(grid, subscriberButton(), '%s');" % f in src, f
for n in ('3', '4'):
    src = open('app/src/main/assets/open%s_stream.js' % n, encoding='utf-8').read()
    assert "mode: 'open%s'" % n in src
    assert "window.__subhubOpen%sSaveNow" % n in src
    assert "open%sServerPageUrl" % n in src and "open2" not in src
    assert "if (ok && !state.ownerAuto) state.reopenAfterSave = true;" in src
    assert "closing.reopenAfterSave" in src
m106 = open('app/src/main/java/com/artaq/subhub/MainActivity.java', encoding='utf-8').read()
assert m106.index('readAsset("app_cards_order.js")') < m106.index('readAsset("moviesmod_stream.js")')
assert m106.index('readAsset("open4_stream.js")') < m106.index('readAsset("series_stream.js")')
assert 'final boolean verifyFirst = "open3".equals(mode) || "open4".equals(mode)' in m106   # 322.3.107 adds || "list"
assert '"open3".equals(mode) ? "window.__subhubOpen3"' in m106
d106 = open('app/src/main/java/com/artaq/subhub/DirectStreamPlayer.java', encoding='utf-8').read()
assert '&& !(verifyFirst && interactiveSource)' in d106          # no capture while verifying (322.3.108 adds && !fallbackMode)
assert 'private void saveVerifiedServer()' in d106
assert 'private String scrollGuardScript()' in d106
assert 'if (verifyFirst) { showVerifyBar(); return; }' in d106
s106 = open('app/src/main/assets/series_stream.js', encoding='utf-8').read()
assert "const VERIFY_MODES = ['open3', 'open4'];" in s106
assert "['Open3', 'open3'], ['Open4', 'open4']" in s106
assert "if (result.ok && state.verify) state.reopenAfterSave = true;" in s106
print('322.3.106 verify cards checks OK')

# 322.3.107: «سيرفرات التطبيق» list (up to 10 per film / episode) + viewer numbers.
al = open('app/src/main/assets/app_list_stream.js', encoding='utf-8').read()
assert "const MAX = 10;" in al
assert "mode: 'list'" in al
assert "appServers: arr" in al and "seriesAppServers: { [ek]: arr }" in al
for hook in ['ServerSelected', 'SaveNow', 'PinNow', 'SyncPinNow', 'ReopenManual']:
    assert "window.__subhubAppList%s = " % hook in al, hook
assert "if (r.ok && !st.auto) st.reopenSlot = r.id;" in al
assert "pinSubtitleForServerV382(entry, 'as_' + st.slotId)" in al
assert "raw && raw.source === 'list'" in al and "previousSeriesHook.apply(this, arguments)" in al
order107 = open('app/src/main/assets/app_cards_order.js', encoding='utf-8').read()
assert "window.__subhubViewerAppOrderV3107 = function ()" in order107
assert "firstSiteAppPill(grid)" in order107 and "subhub-viewer-badge" in order107
for f in ['moviesmod', 'vsm', 'vidnest', 'open1', 'open2', 'open3', 'open4']:
    src = open('app/src/main/assets/%s_stream.js' % f, encoding='utf-8').read()
    assert "(window.__subhubAppReadyV3107 = window.__subhubAppReadyV3107 || {})['%s']" % f in src, f
s107 = open('app/src/main/assets/series_stream.js', encoding='utf-8').read()
assert "window.__subhubSeriesHelpersV3107 = {" in s107
m107 = open('app/src/main/java/com/artaq/subhub/MainActivity.java', encoding='utf-8').read()
assert m107.index('readAsset("series_stream.js")') < m107.index('readAsset("app_list_stream.js")')
assert '|| "list".equals(mode);' in m107 and '"list".equals(mode) ? "window.__subhubAppList"' in m107
print('322.3.107 app servers list checks OK')

# 322.3.107: the update check reads the installed APK's real version.
mv107 = open('app/src/main/java/com/artaq/subhub/MainActivity.java', encoding='utf-8').read()
assert 'private int nativeVersionCode()' in mv107 and 'getLongVersionCode()' in mv107
assert 'd.optInt("versionCode", 0) > nativeVersionCode()' in mv107
assert '"/latest?current=" + nativeVersionCode()' in mv107
assert 'NATIVE_VERSION_CODE = 109' not in mv107
assert 'NATIVE_VERSION_CODE_FALLBACK = 133' in mv107
assert "versionCode 133" in open('app/build.gradle', encoding='utf-8').read()
print('322.3.107 real version checks OK')

# 322.3.108: no "server's own player" fallback (owner: viewers gain nothing
# without subtitles). A saved server that fails keeps the black screen with one
# message + «↩ اختر سيرفراً آخر»; only the owner sees the reason.
d108 = open('app/src/main/java/com/artaq/subhub/DirectStreamPlayer.java', encoding='utf-8').read()
assert 'fallbackMode' not in d108 and 'startProviderFallback' not in d108 and 'fallbackPlayScript' not in d108
assert 'static String playbackFailureReason(PlaybackException e)' in d108
sf = d108[d108.index('private void showServerFailure(String reason)'):]
sf = sf[:sf.index('\n    }\n')]
assert 'اختر سيرفراً آخر أو زراً آخر أو مشغّلاً آخر.' in sf
assert 'chip("↩ اختر سيرفراً آخر", 15, this::close)' in sf
assert sf.index('if (ownerMode) {') < sf.index('سبب الفشل: ')      # reason: owner only
assert sf.index('text.setText(') < sf.index('if (ownerMode) {')
assert 'reason' not in sf[:sf.index('if (ownerMode) {')].replace('String reason', '')
assert d108.count('showServerFailure(') >= 4                     # def + startup retry + player error + vidnest probe
assert 'showServerFailure(firstFailureReason.isEmpty() ? "تعذّر الاتصال بالسيرفر مرتين" : firstFailureReason);' in d108
assert 'showServerFailure(reason);' in d108
assert 'subscriberStartupPhase <= 4 && probe != null && subscriberProviderVisible' in d108
# subtitles: error or no answer -> message (both say what to do next)
assert 'private void showSubtitleFailure(String ownerNote)' in d108
assert 'جرّب زراً آخر أو مشغّلاً آخر' in d108
assert d108.count('watchSubtitleAnswer(index);') == 2
cu = d108[d108.index('public void setCues(int index, JSONArray value, String error)'):]
cu = cu[:cu.index('\n    }\n')]
assert 'subtitleAnsweredSeq = subtitleAskSeq;' in cu and 'showSubtitleFailure(error);' in cu
print('322.3.108 failure message checks OK')

# 322.3.108: the old cards become compact rows inside «📺 سيرفرات التطبيق».
o108 = open('app/src/main/assets/app_cards_order.js', encoding='utf-8').read()
assert "document.getElementById('subhub-applist-legacy-block')" in o108
assert "function compact(card)" in o108 and "function placeLegacy(h, el, mode)" in o108
assert "viewerReady(mode) ? h.saved : h.unused" in o108
assert "'أزرار غير مستعملة ('" in o108
l108 = open('app/src/main/assets/app_list_stream.js', encoding='utf-8').read()
assert "'subhub-applist-legacy-block'" in l108 and 'الأزرار السابقة' in l108
assert 'data-legacy' in l108
print('322.3.108 compact rows checks OK')

# 322.3.109: «📁 إضافة ترجمة من الجهاز» in the app player — everyone, this device only.
d109 = open('app/src/main/java/com/artaq/subhub/DirectStreamPlayer.java', encoding='utf-8').read()
m109 = open('app/src/main/java/com/artaq/subhub/MainActivity.java', encoding='utf-8').read()
assert 'static final int LOCAL_SUBTITLE = -2;' in d109
assert 'void localSubtitlePickRequested();' in d109 and 'void localSubtitleRemoved();' in d109
cc = d109[d109.index('private void chooseSubtitle()'):]
cc = cc[:cc.index('\n    }\n')]
assert 'addLocalSubtitleSection(list);' in cc
assert cc.index('addLocalSubtitleSection(list);') > cc.index('ownerMode && captions && selectedSubtitle >= 0')   # under the list + pin
sec = d109[d109.index('private void addLocalSubtitleSection(LinearLayout list)'):]
sec = sec[:sec.index('\n    }\n')]
assert 'ترجمة من جهازك' in sec and '📁  إضافة ترجمة من الجهاز' in sec and 'SRT · VTT · ASS' in sec
assert '(من الجهاز)' in sec and 'removeLocalSubtitle();' in sec
assert 'ownerMode' not in sec                                      # same for visitor / subscriber / owner
assert 'listener.saveRequested(offsetForSubHubSave());' in d109 and d109.count('listener.saveRequested(subtitleOffsetMs)') == 0
assert 'if (localSubtitleActive()) { syncSaveLocal(); return; }' in d109   # no 📌 timing pin for the device file
assert 'if (ownerMode && !localSubtitleActive()) {' in d109
assert '(selectedSubtitle >= 0 || selectedSubtitle == LOCAL_SUBTITLE) && cues.length() > 0' in d109
assert 'private static final int LOCAL_SUBTITLE_REQUEST = 2208;' in m109
assert 'requestCode == LOCAL_SUBTITLE_REQUEST' in m109 and 'handleLocalSubtitleResult(resultCode, data);' in m109
pk = m109[m109.index('private void openLocalSubtitlePicker('):]
pk = pk[:pk.index('\n    }\n')]
assert 'picker.setType("*/*");' in pk                                # .srt/.ass have no MIME type on Android
assert 'LocalSubtitleParser.decode(bytes)' in m109 and 'LocalSubtitleParser.MAX_BYTES' in m109
assert 'localSubtitleKeyValue = "movie_" + tmdbId;' in m109 and '"tv_" + tmdbId + "_" + episodeKey' in m109
assert 'restoreLocalSubtitle(session, localSubtitleKey, defaultIndex);' in m109
assert 'getFilesDir(), "local_subtitles"' in m109                   # app-private, never uploaded
assert "episodeKey: epKey(season, episode)," in open('app/src/main/assets/series_stream.js', encoding='utf-8').read()
assert "episodeKey: ctx.kind === 'tv' ? ekOf(ctx) : ''," in open('app/src/main/assets/app_list_stream.js', encoding='utf-8').read()
wf = open('../.github/workflows/build-subhub-bridge.yml', encoding='utf-8').read()
assert 'tests/LocalSubtitleParserTest.java' in wf
print('322.3.109 device subtitle checks OK')

# 322.3.110: «مشاهدة تلقائية» — the app tries the episode's servers one by one.
d110 = open('app/src/main/java/com/artaq/subhub/DirectStreamPlayer.java', encoding='utf-8').read()
m110 = open('app/src/main/java/com/artaq/subhub/MainActivity.java', encoding='utf-8').read()
a110 = open('app/src/main/assets/auto_chain.js', encoding='utf-8').read()
assert 'void autoFailed(String reason);' in d110 and 'void autoSucceeded();' in d110
assert 'public void setAutoAttempt(int index, int total, boolean more)' in d110
af = d110[d110.index('private boolean autoFail(String reason)'):]
af = af[:af.index('\n    }\n')]
assert 'if (!autoActive() || autoReported || !autoMore || closed) return false;' in af   # last server -> normal message
assert 'listener.autoFailed(' in af and 'جارٍ تجهيز المشاهدة' in af
assert 'if (autoFail(reason)) return;   // 322.3.110' in d110                       # player error -> next server
assert 'if (autoActive() && autoMore && autoFail(firstFailureReason)) return;' in d110  # no retry per server
assert 'if (autoFail("Vidnest · فحص الرابط فشل")) return;' in d110
assert 'subscriberProviderVisible && !(autoActive() && autoMore)' in d110             # no tap prompt mid-search
assert 'listener.autoSucceeded();' in d110
assert 'readAsset("auto_chain.js")' in m110
assert m110.index('readAsset("app_list_stream.js")') < m110.index('readAsset("auto_chain.js")')
assert 'public void directStreamAutoArm(String token, String raw)' in m110
assert 'public void directStreamAutoEnd(String token)' in m110
assert 'final JSONObject autoAttempt = ((moviesmodFlow || ownFlow) && !interactiveSource && pendingAuto != null' in m110   # 322.3.112: + own files
assert 'directStreamPlayer.setAutoAttempt(' in m110 and 'window.__subhubAutoNextV3110' in m110
assert 'private void showAutoCover(String text)' in m110 and 'cancelAutoChain();' in m110
assert "window.__subhubAutoChainV3110 = function (req)" in a110
assert "window.__subhubSeriesAutoOkV400(c.movieId, c.season, c.episode, c.keys[c.i])" in a110
wf = open('../.github/workflows/build-subhub-bridge.yml', encoding='utf-8').read()
assert 'node tests/auto_chain.test.cjs' in wf and 'node --check app/src/main/assets/auto_chain.js' in wf
print('322.3.110 automatic server search checks OK')
d110 = open('app/src/main/java/com/artaq/subhub/DirectStreamPlayer.java', encoding='utf-8').read()
a110 = open('app/src/main/assets/auto_chain.js', encoding='utf-8').read()
# 322.3.112: the fixed 8 s became smart waiting (checked below)
assert 'if (autoActive() && autoMore) timeoutMs = EpisodeTiming.autoPhaseLimitMs(phase, autoExpectMs);' in d110
assert 'if (autoActive() && autoMore) return;   // 322.3.110: the next server comes instead' in d110
assert 'window.__subhubServerStatV401(c.movieId, name, ok, ms);' in a110 and 'record(chain, false);' in a110
print('322.3.110 by-name learning + 8 s checks OK')

# 322.3.111: the app player tells the site where the viewer stops on an episode
# («أكمل · 17:20» / «✓ تمت المشاهدة») and continues from there on any server.
d111 = open('app/src/main/java/com/artaq/subhub/DirectStreamPlayer.java', encoding='utf-8').read()
m111 = open('app/src/main/java/com/artaq/subhub/MainActivity.java', encoding='utf-8').read()
s111 = open('app/src/main/assets/series_stream.js', encoding='utf-8').read()
l111 = open('app/src/main/assets/app_list_stream.js', encoding='utf-8').read()
assert 'void progress(long positionMs, long durationMs);' in d111
assert 'public void setResumeFromMs(long ms)' in d111 and 'if (ms > pendingResumeMs + 3000L) pendingResumeMs = ms;' in d111
assert 'if (playbackReady) reportProgress(position, duration);' in d111
assert 'if (player != null) reportProgress(Math.max(0L, player.getDuration()), player.getDuration());' in d111
assert 'window.__subhubEpisodeProgressV3111' in m111 and 'JSONObject.quote(progMovie)' in m111
assert 'progMovie.matches("[A-Za-z0-9_.-]{1,120}") && progSeason > 0 && progEpisode > 0' in m111
r111 = m111.index('directStreamPlayer.setResumeFromMs(progResumeMs)')
assert r111 < m111.index('directStreamPlayer.selectDefault(defaultIndex)', r111) < m111.index('directStreamPlayer.setAutoAttempt(', r111)
for js in (s111, l111):
    assert 'function episodeProgress(mid, season, episode)' in js and 'p.t / p.d < 0.9' in js
assert 'progress: episodeProgress(selected.id, season, episode),' in s111
assert "progress: ctx.kind === 'tv' ? episodeProgress(ctx.movieId, ctx.season, ctx.episode) : null," in l111
print('322.3.111 episode progress checks OK')

# 322.3.112: one screen for our own files + «الحلقة التالية» + smart waiting.
d112 = open('app/src/main/java/com/artaq/subhub/DirectStreamPlayer.java', encoding='utf-8').read()
m112 = open('app/src/main/java/com/artaq/subhub/MainActivity.java', encoding='utf-8').read()
s112 = open('app/src/main/assets/series_stream.js', encoding='utf-8').read()
l112 = open('app/src/main/assets/app_list_stream.js', encoding='utf-8').read()
a112 = open('app/src/main/assets/auto_chain.js', encoding='utf-8').read()
# smart waiting
assert 'AUTO_STEP_MS' not in d112
t112 = open('app/src/main/java/com/artaq/subhub/EpisodeTiming.java', encoding='utf-8').read()
for c in ('AUTO_PAGE_MS = 10000L', 'AUTO_SERVER_MS = 12000L', 'AUTO_LINK_MS = 20000L', 'AUTO_START_MS = 15000L', 'AUTO_MAX_MS = 30000L'):
    assert 'public static final long ' + c + ';' in t112, c
assert 'base = Math.max(base, Math.min(AUTO_MAX_MS, expectMs * 2L));' in t112
assert 'timeoutMs = EpisodeTiming.autoPhaseLimitMs(phase, autoExpectMs);' in d112
wf112 = open('../.github/workflows/build-subhub-bridge.yml', encoding='utf-8').read()
assert 'java -cp /tmp/subhub-timing-tests EpisodeTimingTest' in wf112
assert 'public void setAutoAttempt(int index, int total, boolean more, long expectMs)' in d112
assert 'autoAttempt.optLong("expectMs", 0L)' in m112
assert "expectMs: Math.max(0, Math.min(120000, Number(c.ms[i]) || 0))" in a112
oe = d112[d112.index('public void onReceivedError(WebView v, WebResourceRequest r,'):]
oe = oe[:oe.index('public boolean shouldOverrideUrlLoading')]
assert 'code != ERROR_HOST_LOOKUP && code != ERROR_CONNECT && code != ERROR_TIMEOUT' in oe
assert 'if (code != 404 && code != 410) return;' in oe          # 403/5xx are not judged
ap = d112[d112.index('private void autoPageFailed(WebView v, String reason)'):]
ap = ap[:ap.index('\n    }\n')]
assert 'if (!(autoActive() && autoMore)) return;' in ap          # never the last server / normal mode
# own files
assert 'static final int OWN_NONE = 0, OWN_FILE = 1, OWN_PAGE = 2;' in d112
assert 'if (this.ownKind == OWN_FILE) handler.post(this::beginOwnFile);' in d112
assert '(ownKind == OWN_PAGE && isOwnWorkerMedia(r.getUrl()))' in d112
assert 'ProgressiveMediaSource.Factory(sourceFactory)' in d112 and 'if (ownKind != OWN_NONE && !looksLikeHls(candidate))' in d112
assert 'public void mediaFound(String token, String rawUrl)' in d112
assert 'final boolean ownFlow = "ownfile".equals(mode) || "ownpage".equals(mode);' in m112
assert 'if (HOME_HOST.equalsIgnoreCase(oh)) return;' in m112
assert '((moviesmodFlow || ownFlow) && !interactiveSource && pendingAuto != null' in m112
assert 'if (ownFlow) directStreamPlayer.setOwnReferer(HOME_URL);' in m112
assert 'window.SubHubSeriesOwnSourceV3112 = function (raw) { return openOwn(raw || {}); };' in s112
assert "mode: src.kind === 'file' ? 'ownfile' : 'ownpage'," in s112
assert "const OWN_KEY = /^own:[a-z0-9]{1,12}$/;" in a112 and 'window.SubHubSeriesOwnSourceV3112' in a112
# next episode
assert 'void nextEpisode();' in d112 and 'public static final int NEXT_COUNTDOWN_S = 10;' in t112   # 322.3.114: 10 s
assert 'NEXT_AFTER_LAST_LINE_MS = 10000L' in t112 and 'NEXT_NO_SUBTITLE_MS = 40000L' in t112
assert 'lastLineEndMs > durationMs * 8L / 10L' in t112 and 'if (durationMs < SHORT_VIDEO_MS) return durationMs;' in t112
assert 'long at = EpisodeTiming.creditsAtMs(duration, lastSubtitleLineMs(duration));' in d112
assert 'if (segmentMode || cues == null) return -1L;' in d112
assert 'if (creditsReached) positionMs = Math.max(positionMs, durationMs);' in d112
assert 'checkCredits();' in d112
assert 'if (!nextLabel.isEmpty() && nextAuto && !nextCancelled && !interactiveSource) goNextEpisode();' in d112
assert 'window.__subhubNextEpisodeV3112' in m112 and 'if (nextValid) directStreamPlayer.setNextEpisode(nextLabel, nextAuto);' in m112
assert 'final boolean nextValid = !interactiveSource' in m112
assert 'function nextInfo(mid, season, episode)' in s112
assert 'next: (owner && !ownerAuto) ? null : nextInfo(selected.id, season, episode),' in s112
assert 'next: nextInfo(selected.id, season, episode),' in s112
assert "H().nextInfo(ctx.movieId, ctx.season, ctx.episode)" in l112
assert 'window.__subhubNextEpisodeV3112 = function (movieId, season, episode)' in a112
print('322.3.112 one screen + next episode + smart waiting checks OK')

# 322.3.113: Moviesmod «Just a quick check» shown to the viewer + saved player links first.
d113 = open('app/src/main/java/com/artaq/subhub/DirectStreamPlayer.java', encoding='utf-8').read()
m113 = open('app/src/main/java/com/artaq/subhub/MainActivity.java', encoding='utf-8').read()
s113 = open('app/src/main/assets/series_stream.js', encoding='utf-8').read()
l113 = open('app/src/main/assets/app_list_stream.js', encoding='utf-8').read()
assert 'public void gate(String token, boolean present)' in d113
assert 'just a quick check|continue to moviesmod' in d113
g = d113[d113.index('private void showGate()'):]
g = g[:g.index('\n    }\n')]
assert 'subscriberStartupGeneration++;' in g and 'probe.setAlpha(0.0f);' in g     # 322.3.116: our card, the page stays hidden
assert '.click()' not in d113[d113.index('private String gateCheckScript()'):d113.index('private void showGate()')]   # never pressed for the viewer
assert 'if (interactiveSource || probe == null || root == null || closed || gateShown) return;' in d113
assert 'if (gateShown) return;' in d113
f = d113[d113.index('private boolean tryEmbedFallback(String reason)'):]
f = f[:f.index('\n    }\n')]
assert 'embedFallbackUsed = true;' in f and 'directEmbedUrl = "";' in f and 'beginCapture();' in f and 'ownKind != OWN_NONE' in f
assert 'if (tryEmbedFallback(reason)) return true;' in d113
assert d113.count('if (tryEmbedFallback(firstFailureReason)) return;') == 2
assert 'if (!vidnestMode && !interactiveSource && !preferredServerLabel.isEmpty()' in m113
assert "embedUrl: (auto && mode !== 'vidnest') ? saved.embedUrl : ''," in s113
assert "embedUrl: (auto && item && ctx.kind === 'tv') ? (item.embedUrl || '') : ''," in l113
print('322.3.113 Moviesmod check + saved player links checks OK')

# 322.3.114: next-episode strip in landscape, 10 s, translator credit ignored, «حلقات».
d114 = open('app/src/main/java/com/artaq/subhub/DirectStreamPlayer.java', encoding='utf-8').read()
m114 = open('app/src/main/java/com/artaq/subhub/MainActivity.java', encoding='utf-8').read()
t114 = open('app/src/main/java/com/artaq/subhub/EpisodeTiming.java', encoding='utf-8').read()
s114 = open('app/src/main/assets/series_stream.js', encoding='utf-8').read()
l114 = open('app/src/main/assets/app_list_stream.js', encoding='utf-8').read()
assert 'public static long lastDialogueEndMs(double[] startS, double[] endS, String[] text,' in t114
assert 'CREDIT_GAP_MS = 60000L' in t114 and 'CREDIT_TAIL_MS = 120000L' in t114 and 'ترجم|' in t114
assert 'creditsLastCueMs = EpisodeTiming.lastDialogueEndMs(st, en, tx, subtitleOffsetMs, durationMs);' in d114
b = d114[d114.index('private void buildNextCard(boolean landscape)'):]
b = b[:b.index('\n    }\n')]
assert 'new FrameLayout.LayoutParams(-1, dp(46), Gravity.BOTTOM)' in b              # landscape: bottom strip
assert 'new FrameLayout.LayoutParams(dp(290), -2, Gravity.BOTTOM | Gravity.RIGHT)' in b  # portrait as before
assert 'if (nextShown) handler.post(this::nextCardOrientationCheck);' in d114
assert 'void episodeChosen(int season, int episode);' in d114
assert 'public void setEpisodes(JSONArray items)' in d114   # 322.3.115: shown under the progress line (checked below)
assert 'private void playOtherEpisode(String session, String movieId, int season, int episode, String coverText)' in m114
assert '!episodeAllowed.contains(season + "x" + episode)' in m114
assert 'if (episodeItems.length() > 1) directStreamPlayer.setEpisodes(episodeItems);' in m114
assert 'function episodeList(mid, season, episode)' in s114 and 'episodes: episodeList(selected.id, season, episode),' in s114
assert "H().episodeList(ctx.movieId, ctx.season, ctx.episode) : []," in l114
print('322.3.114 next-episode strip + episodes list checks OK')

# 322.3.115: «☰ الحلقات» / «⏭ التالية» under the progress line, episode cards, ⚙ in ⋮.
d115 = open('app/src/main/java/com/artaq/subhub/DirectStreamPlayer.java', encoding='utf-8').read()
assert 'chooseEpisode' not in d115 and 'episodesQuick' not in d115           # no longer in the ⋮ strip
assert 'getIdentifier("exo_settings", "id", activity.getPackageName())' in d115
assert 'getIdentifier("exo_time", "id", activity.getPackageName())' in d115 and 'lp.gravity = Gravity.CENTER;' in d115
assert 'playerView.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) visibility -> {' in d115
assert 'bottomNext = chip("⏭ التالية", 13, this::goNextEpisode);' in d115
assert 'bottomEpisodes = chip("☰ الحلقات", 13, this::openEpisodePicker);' in d115
u = d115[d115.index('private void updateBottomRow()'):]
u = u[:u.index('\n    }\n')]
assert 'nextCardLandscape' in u and 'controllerShown && playbackReady && !cardHere' in u   # the card takes this place at the credits
assert 'HorizontalScrollView scroll = new HorizontalScrollView(activity);' in d115[d115.index('private void buildEpisodePicker()'):]
assert 'lp.bottomMargin = dp(72);   // above the progress line' in d115
assert 'addCompactQuick("⚙", this::openSettings);' in d115 and 'player.setPlaybackSpeed(v);' in d115
assert 'customizeControls();' in d115
print('322.3.115 bottom row + episode cards + settings checks OK')

# 322.3.116: our own check card; the viewer's press goes to the page's «Continue».
d116 = open('app/src/main/java/com/artaq/subhub/DirectStreamPlayer.java', encoding='utf-8').read()
gp = d116[d116.index('private void gatePressed()'):]
gp = gp[:gp.index('\n    }\n')]
assert 'probe.evaluateJavascript(gateButtonScript(), null);' in gp and 'showGatePage();' in gp
assert 'gateCardButton = chip("✓ تحقّق الآن", 16, this::gatePressed);' in d116   # pressed only by the viewer
sg = d116[d116.index('private void showGate()'):]
sg = sg[:sg.index('\n    }\n')]
assert 'gateButtonScript' not in sg and 'dispatchProviderTap' not in sg            # nothing is pressed by itself
assert 'public void gateTarget(String token, double normalizedX, double normalizedY)' in d116
gt = d116[d116.index('public void gateTarget(String token'):]
gt = gt[:gt.index('\n        }\n')]
assert 'if (closed || !gateShown || gatePageVisible) return;' in gt and 'dispatchProviderTap(normalizedX, normalizedY);' in gt
# 322.3.124: the fallback shows their box through revealGateBox (only while the check is there).
assert 'private void showGatePage()' in d116 and 'pv.setAlpha(1.0f);' in d116[d116.index('private void revealGateBox(final WebView pv)'):]
assert 'if (gateShown && gatePageVisible) v.setAlpha(0.0f);' in d116
assert 'title.setText("تحقّق من أنك لست روبوتاً");' in d116 and 'Moviesmod التأكد' not in d116   # no site name on our card
assert 'pv.evaluateJavascript(gateOnlyScript(), null);' in d116                                      # fallback: only their box
assert "body *{visibility:hidden !important;}" in d116 and "[data-subhub-gate],[data-subhub-gate] *{visibility:visible !important;}" in d116
assert 'gateBanner' not in d116
print('322.3.116 own check card checks OK')

# 322.3.117: fbox as a source for «سيرفرات التطبيق» (films).
al117 = open('app/src/main/assets/app_list_stream.js', encoding='utf-8').read()
m117 = open('app/src/main/java/com/artaq/subhub/MainActivity.java', encoding='utf-8').read()
assert "function isFboxPage(raw)" in al117 and "function filmFboxUrl()" in al117
assert "site = 'fbox';" in al117 and "site: site," in al117   # 322.3.123: the family is known (a learned fbox name too)
assert "openSession(ctx, null, true, fboxFits ? fbox : 'https://' + fboxChosen() + '/');" in al117   # 322.3.121: chosen name
assert '"fbox".equals(config.optString("site", ""))' in m117
assert 'allowedHost = fboxHostValue.isEmpty() ? mmHostValue : fboxHostValue;' in m117   # 322.3.123
print('322.3.117 fbox source checks OK')

# 322.3.118: fbox under several names, search by the film title, guard against another film's page.
al118 = open('app/src/main/assets/app_list_stream.js', encoding='utf-8').read()
m118 = open('app/src/main/java/com/artaq/subhub/MainActivity.java', encoding='utf-8').read()
d118 = open('app/src/main/java/com/artaq/subhub/DirectStreamPlayer.java', encoding='utf-8').read()
assert "const FBOX_DEFAULT_DOMAINS = ['fbox.gd', 'vumoo.gd', 'movies2watch.gs'];" in al118
assert "function onFboxName(raw, host)" in al118 and "function samePageFilm(" in al118   # 322.3.121: no probing
assert "field: 'fboxDomains'" in al118 and ".set({ [FAMS[f].field]: arr }, { merge: true })" in al118   # names editable (322.3.123: per family)
assert "searchText" in al118 and "⚠️ هذي صفحة «" in al118            # search + wrong-film guard
assert "public void setSearchText(String text)" in d118 and "private String searchFillScript()" in d118
assert "directStreamPlayer.setSearchText(config.optString(\"searchText\", \"\"));" in m118
print('322.3.118 fbox names/search checks OK')

# 322.3.119: fbox names box (choose ✓ + fallbacks, add / edit / delete / order), and saved
# servers kept under one fixed name so a removed name never breaks or drops them.
al119 = open('app/src/main/assets/app_list_stream.js', encoding='utf-8').read()
assert "const FBOX_STORE_HOST = 'fbox.gd';" in al119 and "function fboxStoreUrl(raw)" in al119
assert al119.count('pageUrl: fboxStoreUrl(p.pageUrl)') == 2 and "fboxUrl: stored" in al119
assert "if (isFboxPage(u.href)) return u.href;" in al119               # items on any name are kept
assert "if (isFboxAny(startPage) || isFboxPage(startPage)) {" in al119
assert "function namesBox(f)" in al119 and "'✓ اختيار'" in al119 and "'➕ إضافة'" in al119   # 322.3.123: one box per family
assert "prompt('أسماء fbox" not in al119                              # no more typing in a prompt
print('322.3.119 fbox names box checks OK')

# 322.3.120: the fbox page does not open on its name (blocked, ads, the site closed) →
# the same server on the next name; the message only after every name failed; a failed
# name is tried last for 10 minutes on this device.
al120 = open('app/src/main/assets/app_list_stream.js', encoding='utf-8').read()
m120 = open('app/src/main/java/com/artaq/subhub/MainActivity.java', encoding='utf-8').read()
d120 = open('app/src/main/java/com/artaq/subhub/DirectStreamPlayer.java', encoding='utf-8').read()
assert "const FBOX_BAD_KEY = 'subhub_fbox_bad_v3120';" in al120 and "const FBOX_BAD_MS = 600000;" in al120
assert "function fboxOrder()" in al120 and "if (h) fboxMark(h, !ok);" in al120
assert "fboxHosts = order.slice(1, 12);" in al120
assert "fboxHosts: fboxHosts," in al120
assert "window.__subhubFboxHostV3120 = function (rawHost, ok) {" in al120
assert 'org.json.JSONArray fboxHostList = config.optJSONArray("fboxHosts");' in m120
assert "if (!fboxFallbackHosts.isEmpty()) directStreamPlayer.setFboxFallbackHosts(fboxFallbackHosts);" in m120
assert "public void fboxHost(String host, boolean ok) {" in m120 and "window.__subhubFboxHostV3120(" in m120
assert "default void fboxHost(String host, boolean ok) {}" in d120
assert "private String source;" in d120 and "private String allowedHost;" in d120
assert "public void setFboxFallbackHosts(java.util.List<String> hosts)" in d120
assert "private boolean tryNextFboxHost(String reason)" in d120
assert "moved = u.buildUpon().authority(next).build().toString();" in d120
# the next name comes before the same-name retry, and a page with the site's grid is never left
r120 = d120[d120.index('private void retrySubscriberStartup()'):]
r120 = r120[:r120.index('subscriberStartupAttempt++;')]
assert r120.index('if (tryNextFboxHost(firstFailureReason)) return;') < r120.index('if (subscriberStartupAttempt >= 2) {')
assert "if (fboxSwitched && fboxPageStep() && fboxGridSize < FBOX_GRID_ALIVE) {" in r120
assert "if (!fboxPageStep() || fboxGridSize >= FBOX_GRID_ALIVE || fboxNextHosts.isEmpty()) return false;" in d120
assert "noteServerGrid(json);" in d120 and "fboxNameWorks();   // 322.3.120" in d120
assert "final boolean fboxPage = isFboxPageRequest(r.getUrl());" in d120
assert "code == 521 || code == 522 || code == 523" in d120 and "code == 403" not in d120
print('322.3.120 fbox next-name checks OK')

# 322.3.121: the fbox flow from scratch — the chosen name (✓) is the one that opens;
# choosing counts at once; the owner adding a server gets exactly that name (a named
# message when it does not open — he changes it himself); search through the 🔍 at
# the top of their page; a saved page of another film is shown, can be cleared, and
# is replaced by the page saved from last.
al121 = open('app/src/main/assets/app_list_stream.js', encoding='utf-8').read()
m121 = open('app/src/main/java/com/artaq/subhub/MainActivity.java', encoding='utf-8').read()
d121 = open('app/src/main/java/com/artaq/subhub/DirectStreamPlayer.java', encoding='utf-8').read()
for gone in ('function probeHost(', 'async function pickFboxHost(', 'async function fboxLive(', 'fboxPicked'):
    assert gone not in al121, gone                                      # no probing, no remembered pick
assert "function fboxChosen()" in al121 and "function onFboxName(raw, host)" in al121
o121 = al121[al121.index('async function openSession('):al121.index('async function savePending(')]
assert "if (!auto) {\n          const name = fboxChosen();" in o121           # owner: exactly the chosen name
assert "startPage = 'https://' + name + '/';" in o121 and "searchText = String(selected.title || '').trim().slice(0, 80);" in o121
assert "const order = fboxOrder();" in o121 and "fboxHosts = order.slice(1, 12);" in o121   # viewers: chosen first + next names
# 322.3.123: the same save for both families of names (fbox / Moviesmod)
s121 = al121[al121.index('async function saveSiteNames('):al121.index('async function addName(')]
assert s121.index('setFamList(f, arr);') < s121.index('await checkOwnerAccess()') < s121.index('.set({ [FAMS[f].field]: arr }')
assert "setFamList(f, before);" in s121                                 # goes back when saving fails
assert "if (namesBusy) return fboxDomainList;" in al121                 # a reload never undoes a change being saved
assert "siteMark(f, h, false);   // 322.3.121" in al121                 # choosing forgets an old failure of that name
assert "const fboxFits = !!fbox && samePageFilm(fbox, filmTitle());" in al121
assert "'⚠️ صفحة فيلم آخر'" in al121 and "async function clearFilmPage(ctx)" in al121
assert "set({ fboxUrl: '' }, { merge: true })" in al121
assert "Object.prototype.hasOwnProperty.call(d, 'fboxUrl') && !String(d.fboxUrl || '').trim()" in al121
assert "if (!d || String(d.fboxUrl || '') !== stored) {" in al121       # the film's page = the page saved from last
assert "const otherId = !!known && samePageFilm(known, title) && fboxIdOf(known) !== fboxIdOf(p.pageUrl);" in al121
assert "if (!confirm(msg)) return { ok: false, text: 'ما انحفظ — صفحة فيلم آخر' };" in al121   # option ب: still asks
assert "public void setFboxOwnerName(String host)" in d121 and "private void fboxOwnerProblem(WebView v, String text)" in d121
assert "محجوب عندك (451) — اختر اسماً آخر من «✏️ الأسماء»." in d121
assert "@Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap favicon)" in d121
assert "if(!clicked&&!onSearchPage){var o=opener();" in d121           # the 🔍 at the top first
assert "d.set.call(i,'');d.set.call(i,q);" in d121                      # old text in the box is replaced
assert "status.setMinHeight(dp(48));" in d121 and "slp.height = ViewGroup.LayoutParams.WRAP_CONTENT;" in d121
assert "sp = new FrameLayout.LayoutParams(-1, dp(48), Gravity.TOP);" in d121   # Moviesmod pages unchanged
assert "if (!fboxNameValue.isEmpty()) directStreamPlayer.setFboxOwnerName(fboxNameValue);" in m121
print('322.3.121 fbox chosen-name flow checks OK')

# 322.3.122: «🩺 فحص» in «📦 أسماء fbox» — the names are opened one after the other in a
# hidden page of the app (this device, this connection) on the film's fbox page (or
# their front page); each row says what its page answered; 451 «blocked on this
# connection» is kept apart from a dead name (a UK order applied by Cloudflare to
# connections it sees in the UK — some Saudi home lines reach it through London).
al122 = open('app/src/main/assets/app_list_stream.js', encoding='utf-8').read()
m122 = open('app/src/main/java/com/artaq/subhub/MainActivity.java', encoding='utf-8').read()
c122 = open('app/src/main/java/com/artaq/subhub/SiteNameChecker.java', encoding='utf-8').read()
r122 = open('app/src/main/java/com/artaq/subhub/NameCheckRules.java', encoding='utf-8').read()
p122 = open('app/src/main/assets/name_check_probe.js', encoding='utf-8').read()
w122 = open('../.github/workflows/build-subhub-bridge.yml', encoding='utf-8').read()
# the bridge: token, the SubHub page only, names cleaned, at most 12, a known page path
n122 = m122[m122.index('public void nameCheckStart(String token, String raw) {'):m122.index('public void nameCheckStop(String token) {')]
assert "if (!vidSrcGuardToken.equals(token) || raw == null || raw.length() > 4000) return;" in n122
assert "if (!isTrustedHomePage()) return;" in n122
assert "!NameCheckRules.validPath(path)" in n122 and "names.size() < 12" in n122
assert "String h = NameCheckRules.cleanHost(arr.optString(i, \"\"));" in n122
assert "HOME_HOST.equalsIgnoreCase(h)" in n122                          # never our own site
assert 'readAsset("name_check_probe.js"), MainActivity.this::sendNameCheck);' in n122
assert "window.__subhubNameCheckV3122(" in m122 and "private void sendNameCheck(JSONObject event) {" in m122
o122 = m122[m122.index('if (nameChecker != null) nameChecker.stop(true);'):]
assert o122.index('if (directStreamPlayer != null) directStreamPlayer.close();') < 200   # a player opening stops the check
assert "if (nameChecker != null) nameChecker.stop(false);" in m122      # and leaving the app
# the hidden page: no bridge, no windows, no dialogs, no pictures, never another name
assert "addJavascriptInterface" not in c122
assert "@Override public boolean onCreateWindow(" in c122 and "return false;" in c122
assert "result.cancel();" in c122 and "s.setBlockNetworkImage(true);" in c122
assert "s.setMediaPlaybackRequiresUserGesture(true);" in c122
assert "if (NameCheckRules.sameSite(host, name)) return false;" in c122
assert "if (u == null || !\"https\".equalsIgnoreCase(u.getScheme())) return true;" in c122
assert "parent.addView(v, 0, lp);" in c122 and "v.setAlpha(0f);" in c122
assert 'page.loadUrl("https://" + name + "/cdn-cgi/trace");' in c122   # 451: which way the connection went
assert "private static final long NAME_LIMIT_MS = 25000L;" in c122 and "private static final long SETTLE_MS = 12000L;" in c122
assert "if (!NameCheckRules.sameSite(j.optString(\"host\", \"\"), name)) return;" in c122   # a blank page never counts
for s in ('OK = "ok"', 'BLOCKED = "blocked"', 'REDIRECT = "redirect"', 'CHECK_PAGE = "checkPage"', 'NOT_SITE = "notSite"'):
    assert s in r122, s
assert 'return c.equals("LHR") || c.equals("MAN") || c.equals("EDI");' in r122
# the page reader presses nothing and never takes a menu for servers
assert ".click(" not in p122 and "dispatchEvent" not in p122
assert "if (best && bn >= 10) { boxes.push(best); bigGroup = true; }" in p122
assert "if (!kn && !bigGroup) servers = 0;" in p122
# the box: check all / one name / a new name checked at once / «➕ أضِفه» / kept on the device
assert "const CHECK_KEY = 'subhub_fbox_check_v3122';" in al122
assert "window.__subhubNameCheckV3122 = function (raw) {" in al122
assert "if (!e || !checkRun || e.run !== checkRun) return;" in al122   # answers of an old run are ignored
assert "b.nameCheckStart(token, JSON.stringify({ run: run, names: list, path: path }));" in al122
assert "if (!checkRun) startCheck(f, [h]);   // 322.3.122: a new name is checked at once" in al122
# 322.3.123: a moved name is replaced in place («🔁 استبدله»), never added (the list stays within 12)
assert "async function replaceName(f, from, raw) {" in al122 and "'🔁 استبدله'" in al122
assert "async function addNameDirect(" not in al122 and "'➕ أضِفه'" not in al122
assert "allBtn.setAttribute('data-ncheck-all', '1');" in al122 and "one.setAttribute('data-ncheck-one', '1');" in al122
assert "'🚫 «محجوب على اتصالك» يخصّ اتصالك أنت الآن" in al122
pc122 = al122[al122.index('function paintCheck()'):]
pc122 = pc122[:pc122.index('\n  }\n')]
assert "render(" not in pc122                                            # answers never redraw the box (typing is kept)
assert "'الفحص يحتاج تطبيق SubHub بنسخة 322.3.122 أو أحدث.'" in al122
# CI runs the new tests
assert "java -cp /tmp/subhub-namecheck-tests NameCheckRulesTest" in w122
assert "node tests/name_check_probe.browser.cjs" in w122 and "node tests/name_check_box.browser.cjs" in w122
print('322.3.122 fbox names check checks OK')

# 322.3.122 (b): the «تحقّق من أنك لست روبوتاً» card never stays stuck — it goes when
# the video comes (before: a «gone» report after playback started was ignored, and the
# page is closed when the video starts, so the card stayed on «جارٍ التحقق…» over the
# playing film); and it has a «✕».
d122b = open('app/src/main/java/com/artaq/subhub/DirectStreamPlayer.java', encoding='utf-8').read()
s122b = d122b[d122b.index('    private void startStream() {'):]
s122b = s122b[:s122b.index('destroyProbe();')]
assert "playing = true;\n        gateDone();" in s122b                    # the video came: the card goes
assert "private void gateDone() {" in d122b and "private void gateClosed() {" in d122b
g122b = d122b[d122b.index('public void gate(String token, boolean present) {'):]
g122b = g122b[:g122b.index('if (present) {')]
assert "if (closed || interactiveSource) return;" in g122b
assert "if (!present) gateDone();" in g122b                              # «gone» while playing still counts
assert "TextView gateClose = chip(\"✕\", 15, this::gateClosed);" in d122b
c122b = d122b[d122b.index('private void gateClosed() {'):]
c122b = c122b[:c122b.index('\n    }\n')]
gg122b = d122b[d122b.index('private void gateGone() {'):]   # 322.3.124: ✕ goes through gateGone
gg122b = gg122b[:gg122b.index('\n    }\n')]
assert "gateGone();" in c122b and "hideGate();" in gg122b
assert "startSubscriberStartupTimer(Math.max(2, subscriberStartupPhase));" in gg122b   # ✕ before the video: the search goes on
print('322.3.122 check card fix checks OK')

# 322.3.123: Moviesmod moved (StreamBoppo, StreamDude… — the same pages by TMDB number and the
# same servers), and so did the fbox family (DopeBox → FlixFrame). Moviesmod gets its own
# names box like fbox; a saved page keeps moviesmod.gd and opens on the chosen name; a site's
# own «new home» page is followed; the check says «انتقل إلى» and «🔁 استبدله» replaces in
# place (the list stays within 12); the chosen server is pressed inside the page even under
# a pop-up and the site's pop-ups are closed; a page with no server gives way at once; the
# owner sets each server's taps on play («⚙️»).
al123 = open('app/src/main/assets/app_list_stream.js', encoding='utf-8').read()
m123 = open('app/src/main/java/com/artaq/subhub/MainActivity.java', encoding='utf-8').read()
d123 = open('app/src/main/java/com/artaq/subhub/DirectStreamPlayer.java', encoding='utf-8').read()
c123 = open('app/src/main/java/com/artaq/subhub/SiteNameChecker.java', encoding='utf-8').read()
r123 = open('app/src/main/java/com/artaq/subhub/NameCheckRules.java', encoding='utf-8').read()
p123 = open('app/src/main/assets/name_check_probe.js', encoding='utf-8').read()
sm123 = open('app/src/main/assets/site_move.js', encoding='utf-8').read()
pc123 = open('app/src/main/assets/popup_close.js', encoding='utf-8').read()
w123 = open('../.github/workflows/build-subhub-bridge.yml', encoding='utf-8').read()
# the names: two families, the same box, its own key / field / answers
assert "const MM_DEFAULT_DOMAINS = ['streamboppo.cyou', 'streamdude.cyou', 'streamzippy.cyou', 'moviesmod.gd'];" in al123
assert "const MM_STORE_HOST = 'moviesmod.gd';" in al123 and "field: 'moviesmodDomains'" in al123
assert "checkKey: 'subhub_mm_check_v3123'" in al123 and "boxId: 'subhub-mm-names-box'" in al123
assert "if (u.protocol === 'https:' && isMmHost(h)) return mmStoreUrl(u.href);" in al123   # saved as moviesmod.gd
assert "if (namesOpen === 'mm') sec.appendChild(namesBox('mm'));" in al123
assert "b.setAttribute('data-names-fam', f);" in al123
assert "if (famList(other).indexOf(h) >= 0) return" in al123            # one family per name
# viewers: this device's order; the owner: exactly the chosen name
o123 = al123[al123.index('async function openSession('):al123.index('async function savePending(')]
assert "const order = siteOrder('mm');" in o123 and "mmHosts = order.slice(1, 12);" in o123
assert "startPage = onName(page, mmDomainList[0] || MM_DEFAULT_DOMAINS[0]);" in o123
assert "openChooser: owner && !auto && hadPage," in o123
assert "tapRule: rule ? { taps: rule.taps, gapMs: rule.gap * 1000 } : null," in o123
so123 = al123[al123.index('function siteOrder(f) {'):al123.index('function fboxOrder() {')]
assert "const v = learned[h] || h;" in so123                            # a moved name: its new home
assert "if (good && learned[good]) good = learned[good];" in so123
assert "g.chosen !== (famList(f)[0] || '')" in al123                    # the owner's new ✓ wins over memory
assert "window.__subhubSiteHostV3123 = function (fam, rawHost, ok) {" in al123
assert "window.__subhubSiteMovedV3123 = function (fam, rawFrom, rawTo) {" in al123
assert "b.siteNamesV3123(token, JSON.stringify({ mm: mmDomainList.slice(0, 12), fbox: fboxDomainList.slice(0, 12) }));" in al123
# the check: moved → «🔁 استبدله» in place / «🗑️ احذفه» when listed; one run at a time
assert "case 'redirect':\n        return { t: '↪️ انتقل إلى'" in al123
rn123 = al123[al123.index('async function replaceName(f, from, raw) {'):al123.index('// 322.3.122: «🩺 فحص» — the names are opened')]
assert "list[i] = to;" in rn123 and "list.splice(i, 1);" in rn123 and "list.unshift(to);" in rn123
assert "concat([to])" not in rn123                                      # never grows
assert "else if (checkRun) notify('فحص آخر يعمل الآن — انتظر حتى ينتهي.');" in al123
# «⚙️» taps per server name
assert "serverRules: { [key]: rule ? { taps: rule.taps, gap: rule.gap } : firebase.firestore.FieldValue.delete() }" in al123
assert "if (key && Number.isInteger(taps) && taps >= 0 && taps <= 6) {" in al123
# the activity: Moviesmod names kept on the device, every Moviesmod page on the chosen name
assert 'private static final String[] MM_DEFAULT_NAMES = {"streamboppo.cyou", "streamdude.cyou", "streamzippy.cyou", "moviesmod.gd"};' in m123
assert "public void siteNamesV3123(String token, String raw) {" in m123
sn123 = m123[m123.index('public void siteNamesV3123(String token, String raw) {'):m123.index('public void nameCheckStop(String token) {')]
assert "if (!vidSrcGuardToken.equals(token) || raw == null || raw.length() > 4000) return;" in sn123
assert "if (!isTrustedHomePage()) return;" in sn123 and "HOME_HOST.equalsIgnoreCase(h)" in sn123
assert "bh = mmNames.isEmpty() ? MM_STORE_HOST : mmNames.get(0);" in m123
assert 'siteFamilyValue = fboxHostValue.isEmpty() ? "mm" : "fbox";' in m123
assert 'if ("mm".equals(siteFamily)) page = moviesmodStoreUrl(page);' in m123   # saved as moviesmod.gd
assert "return u.buildUpon().authority(MM_STORE_HOST).build().toString();" in m123
assert "directStreamPlayer.setPageScripts(siteMoveScript(), popupCloseScript());" in m123
assert 'directStreamPlayer.setTapRule(tapRule.optInt("taps", -1), tapRule.optLong("gapMs", 2000L));' in m123
assert 'siteMoveScript() + "\\n" + readAsset("name_check_probe.js"), MainActivity.this::sendNameCheck);' in m123
assert "window.__subhubSiteMovedV3123(" in m123 and "window.__subhubSiteHostV3123(" in m123
# the player: move notices followed, no-server pages left at once, pop-ups closed, taps rule
assert "public void moved(String token, String rawHost) {" in d123
sm = d123[d123.index('private void siteMoved(String rawHost) {'):d123.index('private void injectPageScripts(final WebView v) {')]
assert "fboxNextHosts.add(0, to);" in sm and 'tryNextFboxHost("انتقل إلى " + to);' in sm
assert "allowedHost = to;" in sm and "probe.loadUrl(moved);" in sm and "!movesSeen.add(from + \">\" + to)" in sm
assert "injectPageScripts(v);\n                    scheduleGridWait(v);" in d123
assert "private static final long NO_GRID_WAIT_MS = 8000L;" in d123
assert "if (!fboxPageStep() || fboxGridSize > 0 || fboxNextHosts.isEmpty()) return;" in d123
assert "if (!verifyCard) recordProviderChoice(label);" in d123          # «💾 حفظ» only when the page took it
assert '"for(var i=0;i<" + tapRuleTaps + ";i++)setTimeout(function(){play();tap();},700+i*" + tapRuleGapMs + ");"' in d123
assert "if (tapRuleTaps >= 0) {   // 322.3.123: the owner's «⚙️» taps are the whole plan" in d123
assert 'lower.matches("whatsapp|telegram|facebook|twitter|x|reddit|pinterest|email|share|copy link|copy|more|more…|more\\\\.\\\\.\\\\.|join|later")' in d123
assert 'showServerFailure("لم تفتح صفحة السيرفرات على أي اسم من أسماء " + siteWord() + ":\\n" + fboxReasons);' in d123
# the check: a «new home» page is «moved»; Moviesmod film pages are valid paths
assert 'String moved = NameCheckRules.cleanHost(j.optString("moved", ""));' in c123
assert "if (NameCheckRules.clearlyWorks(facts) || !facts.redirectHost.isEmpty()" in c123
assert '"|/(movie|tv)/[0-9]{1,12}"' in r123
assert "moved: moved" in p123 and "window.__subhubSiteMoveV3123" in p123
assert ".click(" not in p123 and "dispatchEvent" not in p123 and ".click(" not in sm123   # readers press nothing
assert "flixnetwork" in sm123                                           # the directory never counts as the new site
assert "if (!PROMO.test(words) || CHECK.test(words)) continue;" in pc123   # never the check page
assert "if (href && href !== '#' && !/^javascript:/i.test(href)) continue;" in pc123   # never a link out
# CI runs the new tests
assert "node tests/player_pages.browser.cjs" in w123 and "node tests/site_names.browser.cjs" in w123
assert "node --check app/src/main/assets/site_move.js" in w123 and "node --check app/src/main/assets/popup_close.js" in w123
print('322.3.123 site names checks OK')

# 322.3.124: the site's check and the viewer. After «✓ تحقّق الآن» the film page was shown to
# the viewer with its ads and no video came: the site takes its check away in the same page
# (no new page, so no new report), the fallback 7 s later «showed the check» — by then the film
# page — and the search stood still; and the saved server had been pressed under the check
# (the site ignores that) and never again. Chromium: tests/check_gate.browser.cjs.
d124 = open('app/src/main/java/com/artaq/subhub/DirectStreamPlayer.java', encoding='utf-8').read()
def body124(sig, end='\n    }\n'):
    s = d124[d124.index(sig):]
    return s[:s.index(end)]
# the words of the check: one list for every script
assert 'private static final String GATE_WORDS =' in d124 and '"var re=/" + GATE_WORDS + "/i;"' in d124
assert d124.count('just a quick check|continue to moviesmod') == 1
# the fallback shows their box only while the check is really there; otherwise the search goes on
sgp = body124('    private void showGatePage() {')
assert 'pv.evaluateJavascript(gatePresentScript(), value -> {' in sgp
assert 'if ("true".equals(value)) revealGateBox(pv);' in sgp and 'else gateGone();' in sgp
assert 'setAlpha(1.0f)' not in sgp                                       # never shown from here directly
rgb = body124('    private void revealGateBox(final WebView pv) {')
assert '|| probeLoading) return;' in rgb                                 # a page still loading is never shown
assert 'pv.evaluateJavascript(gateWatchScript(), null);' in rgb          # passed by hand in the same page: seen
# the viewer's press: the page is watched from then on
gp124 = body124('    private void gatePressed() {')
assert 'probe.evaluateJavascript(gateWatchScript(), null);' in gp124
# a new page while their box is shown: hidden at once
ps124 = d124[d124.index('@Override public void onPageStarted(WebView v, String url'):]
ps124 = ps124[:ps124.index('\n            }\n')]
assert 'probeLoading = true;' in ps124 and 'gateShown && gatePageVisible) gateBetweenPages(v);' in ps124
gbp = body124('    private void gateBetweenPages(WebView v) {')
assert 'v.setAlpha(0.0f);' in gbp and 'gateCardStatus.setText("جارٍ التحقق…");' in gbp and 'showGatePage();' in gbp
assert 'probeLoading = false;' in body124('            @Override public void onPageFinished(WebView v, String url) {', '\n                String host')
assert 'probeLoading = false;' in body124('    private void destroyProbe() {')
# gone: our screen again, the search goes on, the page gets its own seconds for its servers
gg124 = body124('    private void gateGone() {')
assert 'hideGate();' in gg124 and 'startSubscriberStartupTimer(Math.max(2, subscriberStartupPhase));' in gg124
assert 'if (probe != null) scheduleGridWait(probe);' in gg124
assert 'probe.evaluateJavascript(gateClearScript(), null);' in body124('    private void hideGate() {')   # our style goes
g124 = d124[d124.index('public void gate(String token, boolean present) {'):]
g124 = g124[:g124.index('\n        }\n')]
assert 'else if (gatePageVisible && probe != null) revealGateBox(probe);' in g124 and 'else gateGone();' in g124
assert 'gateGone();' in body124('    private void gateClosed() {')
# a server pressed in the page = the check is passed: its card goes
rpc = body124('    private void recordProviderChoice(String rawLabel) {')
assert 'if (gateShown) hideGate();' in rpc
# the server is never pressed under the check; the waiting uses no tries; the check coming and going is said
pk124 = body124('    private String providerPickerScript() {')
assert '+ gatePresentFn()' in pk124 and 'function gateSeen(){var on=__subhubGateOn();if(on!==gateWas){gateWas=on;' in pk124
assert '"if(!gateSeen())sendList();"' in pk124
assert 'window.__subhubPickLoop=setInterval(function(){if(gateSeen())return;tries++;sendList();' in pk124
# the check function: their box (under our style) or the words in the page
gpf = body124('    private static String gatePresentFn() {')
assert "document.querySelector('[data-subhub-gate]')" in gpf and 'document.body.innerText' in gpf
# the watch: twice in a row not seen = gone (a redraw is not), two minutes at most
gws = body124('    private String gateWatchScript() {')
assert 'else if(++miss>=2){' in gws and 'if(n>=300){' in gws and 'S.gate(t,false);' in gws
# nothing of the check is pressed by the app itself (only the viewer's own press is passed on)
assert 'gateButtonScript' not in body124('    private void showGate() {')
assert 'click(' not in gws and 'click(' not in gpf
# CI runs the new test
w124 = open('../.github/workflows/build-subhub-bridge.yml', encoding='utf-8').read()
assert "node tests/check_gate.browser.cjs" in w124
print('322.3.124 check gate checks OK')
