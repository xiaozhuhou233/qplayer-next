# Dynamic cover controls and Apple catalog verification

## Behavior

- Appearance settings expose a dynamic-cover blur-mask switch. A dependent 40–90% screen-height slider moves the cover's lower edge, reflection pivot and blur transition together; the default remains 72%.
- Disabling the mask avoids reflection and blur RenderNodes. When enabled, one quarter-resolution 56dp blur replaces the previous two passes. The sharp video remains at full resolution. A smootherstep opacity ramp blends the blurred reflection across the edge.
- Dynamic artwork starts at the screen top. The top black gradient is lighter; the playback-detail header is removed. Share, lyrics and queue remain accessible through bottom overflow menus. The lyrics-page header stays available.
- The ambient fallback and loaded Apple preview no longer apply two background blurs at once.
- This change does not alter the audio backend, MediaSession, lyric motion or video/audio focus handling.

## Apple API investigation

Checked on 2026-11-02 (Asia/Shanghai), using Apple's public web catalog endpoints and the same `extend=editorialVideo` parameter as the app. Both `extend=editorialVideo` and `extend[albums]=editorialVideo` were compared with a positive control.

| Album | Catalog result | Motion result |
| --- | --- | --- |
| Logic — Everybody | Found: 1440896552, 1440892046; deluxe editions 1441002219 and 1456232697 | No `editorialVideo` in the checked available US/CN/GB editions |
| Khalid — Suncity | Found: 1438904068 and 1438912699 (`Suncity - EP`) | No `editorialVideo` in the checked US/CN/GB editions |
| The Weeknd — After Hours | Found: 1499378108 | Real square/tall HLS editorial-video URLs returned in US/CN/GB |

Each region was also searched independently, rather than assuming an album ID is available everywhere. Everybody 1440896552 is unavailable in GB; the regional search correctly finds 1440892046. Twelve regional album-detail responses were checked. These are observations about the queried catalog releases, not a claim that Apple can never add motion artwork or expose another edition.

Public album pages checked for Everybody and Suncity likewise did not expose editorial-video/HLS assets. The positive control confirms the endpoint and extension parameter are functioning.

## Matching defect fixed

Actual song responses exposed a separate metadata mismatch: Apple includes featuring credits in titles such as `Suncity (feat. Empress Of)` and `1-800-273-8255 (feat. Alessia Cara & Khalid)`. An exact comparison against a title without those credits rejected their album relationships.

Song comparison now removes only featuring credits, while retaining live/remix/acoustic version qualifiers. Album-name normalization is unchanged. Catalog song mapping, iTunes metadata fallback, album-track identity discovery and final containing-album validation use the same comparison. Artist checks and ISRC validation remain required. The lookup cache version changes to v3 so a previous one-day miss cannot conceal the correction.

The production Java matcher was run against five saved live responses. All five now resolve their expected song/album identities: Everybody, 1-800-273-8255, Better, Suncity and After Hours. Before the fix, the two featuring-title cases were rejected. After Hours resolves motion; the target albums correctly remain static because the checked releases contain no motion asset.

## DeepSeek decision

AI-assisted album-name inference was considered. These examples are already resolvable through Apple metadata after the deterministic credit fix. An AI guess would not supply a missing official video, and would add latency and token usage. This release therefore does not add an unconditional AI request or claim to have called the user's configured DeepSeek endpoint. Unknown or unavailable releases continue to fall back to static artwork.

## Verification and limits

- Targeted settings/artwork JVM suite and signed MD3E Release build are required for delivery.
- New regression coverage exercises both real featuring-title examples, bidirectional credit variants, iTunes fallback, and rejection of wrong artist/ISRC/live/remix/acoustic versions.
- All 38 existing lyric files were preserved byte-for-byte; the lyrics-tab implementation inside the player file was also kept intact.
- Release validation covers certificate, package ID, non-debuggable status, ZIP alignment, mascot assets, eight bundled font files and MD3E class/resource isolation.
- No connected-device visual/performance validation was performed for the blur seam, slider, overflow menus or long-running video playback. API/matcher results do not replace device playback testing.
- The prior friend-import baseline has unrelated failing StemFusion/Bilibili tests; see `FRIEND-IMPORT-REVIEW.md`. This release does not claim those failures are resolved.
