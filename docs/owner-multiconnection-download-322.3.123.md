# Owner-only multi-connection video download — 322.3.123 (design)

Status: specification only; implementation not yet completed.

## UI
- Add 'فحص الفيديو' next to 'حفظ هذا السيرفر' in owner server player screen.
- Only owner sees the scan/download controls. Subscribers' UI and playback remain unchanged.
- Scan is manual, triggered after a video starts playing; do not auto-capture or auto-save.
- On success, display available source renditions and a download button; on failure, display a clear nonblocking error.

## Downloader
- Download one video with multiple HTTP range requests (default 8; 1/4/8/16 configurable) only if origin supports range requests, length, and stable resource validators.
- Validate 206 Content-Range responses, size, ETag/Last-Modified, and prevent mismatched chunk concatenation.
- Fall back to one connection when ranges are unsupported; adaptive concurrency to avoid throttling.
- HLS: download permitted unencrypted media segments with bounded parallelism and correct playlist ordering; do not bypass DRM or access restrictions.
- Resume supported downloads with persisted chunk metadata and atomic file finalize; handle expiring URLs by reporting failure rather than corrupting output.
- Foreground service with notification for Android background download; pause, resume, cancel, progress, error handling.
- Available qualities must be discovered, never invented; size may be unknown.

## Safety and compatibility
- Do not modify existing server-save workflow, player behavior, subtitle tools, subscriber experience, applicationId, signing configuration, or release update logic.
- Do not attempt DRM bypass or protected stream extraction.
- Build and test on separate branch before merging; verify upgrade installation using existing signing key.

## Next engineering step
Locate the Android project sources and owner server WebView/player screen; integrate UI and downloader, run CI, test on device.
