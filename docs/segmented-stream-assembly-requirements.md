# Segmented streaming support requirements

The owner-only downloader must support more than direct MP4:
- HLS (.m3u8): parse master and media playlists, show actual variants, download permitted unencrypted segments in playlist order with bounded concurrency, honor EXT-X-MAP and discontinuities, and remux into a single playable output when supported.
- DASH (.mpd): parse representations and segment timelines/templates, download separate audio/video representations and mux into one file.
- Direct HTTP files: byte-range multipart transfer and final atomic rename.
- Do not concatenate arbitrary TS/fMP4 chunks as raw bytes and label them MP4; use a container-aware muxer and verify audio/video tracks, timestamps and duration.
- Progress must include segment download AND mux/finalization stages. Keep temporary chunks until successful verification, then clean them up.
- Handle expired URLs, failed segments, redirects, missing audio, alternate audio, and variant switches with explicit errors.
- No DRM bypass; protected streams are unsupported.
- Owner-only manual scan/download, no subscriber UI changes.

Implementation status: requirements recorded; HLS/DASH parsers and remuxer not implemented yet.
