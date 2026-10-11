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
assert "versionCode 123" in gradle
assert "versionName '322.3.114'" in gradle
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
assert 'allowedHost = "moviesmod.gd"' in main
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
assert 'if(!hit||!uncovered(hit))return false' in direct
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
assert 'resolvedSourceUrl = savedUri.toString()' in main
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
assert 'NATIVE_VERSION_CODE_FALLBACK = 123' in mv107
assert "versionCode 123" in open('app/build.gradle', encoding='utf-8').read()
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
assert 'subscriberStartupGeneration++;' in g and 'probe.setAlpha(1.0f);' in g     # visible, search paused
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
assert 'public void setEpisodes(JSONArray items)' in d114 and 'episodesQuick = chip("حلقات", 13, this::chooseEpisode);' in d114
assert 'private void playOtherEpisode(String session, String movieId, int season, int episode, String coverText)' in m114
assert '!episodeAllowed.contains(season + "x" + episode)' in m114
assert 'if (episodeItems.length() > 1) directStreamPlayer.setEpisodes(episodeItems);' in m114
assert 'function episodeList(mid, season, episode)' in s114 and 'episodes: episodeList(selected.id, season, episode),' in s114
assert "H().episodeList(ctx.movieId, ctx.season, ctx.episode) : []," in l114
print('322.3.114 next-episode strip + episodes list checks OK')
