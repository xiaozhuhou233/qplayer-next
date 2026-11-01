# Friend AutoMix / MV import review

## Source and scope

- Common Git base: `1e840a943ef65b2480f3f663f1095b630b5e24d8`.
- Friend head: `a8bbd46` from the supplied `qplayer-next.zip`; extracted source matches its committed tree.
- The archive changes 23 files against that base. Nine lyric-related files (including the direct core dependency added solely for lyric springs) are excluded at the owner's explicit request.
- The current workspace's newer Apple artwork matching/reflection and iOS glass changes were preserved separately as commit `344a82c` before this import.
- Archive build outputs, caches, signing/configuration files, hooks and original handoff documents are not imported. The original documents remain under the local reference checkout and do not define current requirements.

## Accepted behavior

- AutoCue scanning: bounded 16-entry / 12-hour LRU, now usable for remote audio as well as files.
- AutoMix rendering: accept an exactly fitting incoming window; permit up to 500 ms of compressed-container duration error while retaining strict lossless/truncation checks and deleting failed partial renders.
- Automatic window preference: conservative / balanced / aggressive, persisted in playback settings.
- MV overlay: softer top shading and corner shading, without changing the video player, sizing, caching or audio focus.
- Additional render, beat-grid and surprise-analysis fixtures. The real-song renderer is a manual command-line tool under `tools/automix`, not an automatic test that depends on another computer's `/tmp` paths.

## Review corrections

1. Remote scan identity strips only named expiring signatures, retaining song IDs, quality/format parameters and request headers. Different songs and accounts must not share a scan.
2. Preference changes apply immediately. A manually fixed window is never scaled: a requested 20-second window remains 20 seconds. The UI calls this an automatic-window preference because the supplied implementation adjusts duration, not cue scoring.
3. Short compressed EOS inside the overlap updates the actual overlap length; the archive otherwise still threw `Empty AutoMix render` after accepting that shortfall.
4. Lossless input still uses its stricter timing tolerance. Missing audio beyond the accepted tolerance remains an error.
5. Handover diagnostics use the existing logger instead of synchronous, unbounded file writes during the audio swap, and tolerate absent local mixes.
6. Corner shading fades radially rather than abruptly ending a horizontal scrim at 55% screen height.
7. Silence does not create a synthetic tempo in the imported standalone BarGrid helper.
8. The imported whole-track Surprise prior is identical for every phase candidate. It is calculated once, preserving ranking semantics and avoiding repeated whole-track work. This is not claimed to be a completed phase-sensitive cue-scoring feature.

## Limits

- BarGrid is standalone groundwork, not wired into the live AiMix engine; existing AutoCue beat alignment remains responsible for playback.
- Synthetic/JVM tests cannot establish real-device decoder timing, audible seamlessness, MV appearance or frame pacing. These require a device listening/visual check.
- No lyrics, lyric parsing, word lift, glow, cascade or scrolling changes are included.

See `work/friend-import/VERIFY.md` for validation and known pre-existing failures.
