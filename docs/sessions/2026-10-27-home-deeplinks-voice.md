# Session 2026-10-27: home, deep links, search and voice

Tested on the owner's Sony Bravia (Android 12, region IN). Everything below is on `main`.

## What changed

| Area | Change | Main files |
|---|---|---|
| Deep links | JustWatch GraphQL (no key) gives each app's page for a TMDB id; Watchmode only as fallback. Cache 30 days (3 when empty). | `JustWatch.java`, `Recs.watchLink` |
| Deep links | Per-app intents: Netflix `/title/<id>` + `source=30`, then `nflx` without BROWSABLE. Custom schemes retried without BROWSABLE. | `Launch.java` |
| Trailer | `https://www.youtube.com/watch?v=` first: YouTube TV opens its home for `vnd.youtube:`. | `Launch.trailer` |
| Details sheet | Ways to watch: installed apps first, then "Not installed", Stremio last. | `DetailsSheet.java` |
| Home | Drawer: HDMI, Wi-Fi, Settings (no Live TV). Continue = last clicked + Stremio, title only. Recs refresh daily, not per time of day. | `HomeView`, `HomeApps`, `History` |
| Memory | `HomeRows.window` keeps at most 10 to 12 images / 12 to 16 MB decoded. | `HomeRows.java` |
| Search | Network from 3 letters, 3 results then up to 10, first one preselected. "X on TV" searches X here (`Fuzzy.withoutOnTv`). | `Search.java`, `Fuzzy.java` |
| Voice | Box ducks at once, one press listens, 15 s timeout, recognizer errors say "Didn't catch that". `KEYCODE_ASSIST` = mic. | `HomeView` voice, `HomeActivity.onActivityResult` |
| Assistant | `SEARCH` and `com.google.android.gms.actions.SEARCH_ACTION` open our search. | `AndroidManifest.xml`, `res/xml/searchable.xml` |
| Mock | Parity with all of the above; new flags `?voice=` and `?search=`. | `mock/` |

## Deep links that work on the Sony (tested)

| App | Package | Address that opens the title |
|---|---|---|
| Prime Video | `com.amazon.amazonvideo.livingroom` | `https://app.primevideo.com/detail?gti=…` (setPackage): profile picker, then the title |
| Apple TV | `com.apple.atve.sony.appletv` | `https://tv.apple.com/in/show/<slug>/umc.cmc.<id>`: profile picker, then the show. Episode links must become the show link. The search URL opens an empty search. |
| JioHotstar | `in.startv.hotstar` | `https://www.hotstar.com/in/<id>` plays. `hotstar://content/<id>` lands on an upgrade page. |
| Netflix | `com.netflix.ninja` | `https://www.netflix.com/title/<id>` + setPackage. DRM: screenshots are black, check `dumpsys media_session` (state=3 = playing). |
| YouTube | `com.google.android.youtube.tv` | `https://www.youtube.com/watch?v=<id>` plays; `vnd.youtube:` only opens the home. |
| Stremio | `com.stremio.one` | used last, when no installed app has the title |

## Measured (Sony, `io.freemytv:home`)

- Frames: 1 janky of 1,722 (was 26% janky); p50 22 ms, p99 25 ms (about 45 fps, not 60).
- Memory: 44 MB PSS at rest, 72 MB peak while scrolling; textures about 10 MB.
- Rust rewrite: not worth it. Drawing time is the cost, the same in Java or Rust, and JNI and a native toolchain
  without Gradle would add work.

## Open items (next sessions)

1. Search with a year: "Special Ops 2020" finds nothing. Strip a trailing year and use it as a filter.
2. Real remote mic and the "Didn't catch that" hint: not tested by voice (only by ADB intents).
3. Netflix: the title is not proved (DRM, no metadata in the media session).
4. Search requests reach Free My TV while in *Original* mode: low risk, could forward to the stock search instead.
5. Code review leftovers: `jw:` cache keys in the `recs` prefs are never pruned; prefetch shares the 2-thread
   `Work.io` pool with Watch.
6. Translations: `translate.py --check` fails on 54 keys in every language, the same as before this session.
   Run `python3 tools/translate.py`, then hand-fill pt/es/de/ru.

## How the work was done

- Four writer subagents (Sonnet for code, Haiku for the mock), each in its own git worktree from one base commit,
  each owning separate files; then a Sonnet code review; merged one branch at a time into an integration branch
  (an octopus `git merge -q` failed silently).
- Checks: `node --check` for mock/web, headless browser screenshots of the mock, `tools/check.sh android`, then
  install on the TV and drive it with `tools/tv.sh`.
