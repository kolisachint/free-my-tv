# Session 2026-10-28: voice search in the app, local-language picks

Tested on the owner's Sony BRAVIA 4K VH2 (KD-65X85J), Android 12, region IN, at 192.168.1.7. Everything below is on
`main`.

## What changed

| Area | Change | Main files |
|---|---|---|
| Voice | The search mic listens **in our process** (`SpeechRecognizer`, the TV's default service = Google's katniss). No Google screen, no "duck", words show live in the field. Google's recognizer screen is only the fallback (no mic permission, or the service refuses us). | `Voice.java` (new), `HomeView` voice, `SearchScreen` |
| Voice | Of up to 5 alternatives, the one that best matches a title/app we already know wins (`HomeView.bestHeard`). | `HomeView.java` |
| Voice | Listening UI: field says "Listening…" then the words in dim ink; chip says "Stop" with a pulsing ring (shape + motion, not color); line under the field: "Speak now. Back to stop." / "Didn't catch that…" / "Voice search needs the internet." | `SearchScreen.java`, `Fx.pulse` |
| Permission | `RECORD_AUDIO` declared; the switch runs `pm grant io.freemytv android.permission.RECORD_AUDIO` after `set-home-activity`. | `AndroidManifest.xml`, `HomeMode.java` |
| Picks | `local_languages=hi,mr` (config.properties): every third slot of Movies and Shows is a recent title in those languages on the TV's apps in `tmdb_region`. | `Catalog.java`, `build.sh`, `config.example.properties` |
| Mock | One-row listening state with live words, `?micerr` flag, first phrase "reacher". | `mock/search.js`, `mock/mock.css`, `mock/README.md` |

## Benchmarks (Sony VH2, `io.freemytv:home`)

| What | 2026-10-24 | 2026-10-27 | 2026-10-28 |
|---|---|---|---|
| Janky frames (scrolling rows) | 26% | 0.06% (1 / 1,722) | 0.38% (1 / 260) |
| Frame time p50 / p90 / p99 | p95 200 ms | 22 / – / 25 ms | 8 / 9 / 11 ms |
| `:home` PSS | 37 MB | 44 MB rest, 72 MB peak | 71 MB right after scrolling both rows |
| Main process PSS | 10 MB | – | 6 MB |

| Voice (search mic) | Google's screen (before) | In-process (now) |
|---|---|---|
| Press → mic open | ~400 ms (activity start + 70 ms delay), home pauses, 1.1 s frame on return | 65–185 ms, home never pauses |
| Speech end → text | 3.5 s, and then the screen **never returned** it (8 s, then Home) | words arrive live; final at end of speech |
| No speech | screen hung | "Didn't catch that" after ~9 s (service's own timeout) |

Logs to read: `adb logcat -s FreeMyTV` lines starting `voice:` (mic open, results latency, first results after
the words) and `rows:`.

## Can and can't (learned on this TV)

**Can**
- Use Google's speech service from our own process: `SpeechRecognizer` binds katniss's `KatnissRecognitionService`
  (no permission on the service), our app needs only `RECORD_AUDIO` (granted over ADB). The remote's BLE mic audio
  reaches it.
- Show partial words while the person speaks; get up to 5 alternatives.
- Filter TMDB picks by original language (`with_original_language=hi|mr`) on the region's providers.

**Can't**
- **Catch the remote's mic button.** It never becomes a key event (no `getevent`, nothing in the input queue, no
  accessibility key filter): `com.google.android.tv.remote.service` streams the audio straight to Google Assistant
  (`BUTTON_PRESSED_WITH_ATV_REMOTE_SERVICE`). `KEYCODE_SEARCH/ASSIST` handling in `HomeView` never fires on this remote.
- **Get the Assistant's title results into our app.** Tested by turning launcherx back on for two minutes: the
  Assistant opens `https://tv.google.com/...` with `pkg=com.google.android.apps.tv.launcherx` set (explicit package,
  `LauncherDeepLink` / `EntityActivity`), so no intent filter of ours can take it. With launcherx off (Free My TV
  mode) it fails: `provider.OPEN ... UNKNOWN_ERROR`, "can't help with that". Only its plain text search
  (`ui.SHOW_MEDIA_BROWSE` → katniss `SearchResultActivity`) works without launcherx.
- **Trust the Assistant's hearing** of English titles with the en-IN locale: "Reacher" came back as "Richa",
  "richar", "creature". Our in-app path fixes this by picking the alternative that matches a known title.
- **Get "trending in India" from TMDB.** `/trending` is worldwide; `watch_region` only filters by where a title
  streams. Live check: 0 of 20 trending and 0 of 20 "recent on IN apps" titles were Indian-language.

## Do / don't

- **Do** use the in-process recognizer for any voice in our UI; keep the activity route only as a fallback.
- **Don't** put timers, delays or retries around `startActivityForResult(RECOGNIZE_SPEECH)` again: katniss's screen
  can hold the answer and never return it.
- **Don't** set `EXTRA_CALLING_PACKAGE` to another app's package: it changed nothing on this TV.
- **Don't** pursue becoming the voice-interaction (assistant) app: the remote's audio route belongs to Google, and it
  could break voice in every app.
- **Do** copy the real `freemytv.keystore` into a fresh worktree before building, or the APK won't install over the
  TV's (a new key is generated; `*.keystore` is gitignored).
- **Do** check `tv_top` before sending keys: a key sent while another app (Stremio) is on top tests the wrong thing.
- **Do** expect TMDB connection resets from the Mac (`Errno 54`): retry with pauses.

## Known unknowns

- Partial results: katniss accepts `EXTRA_PARTIAL_RESULTS`, but the owner's test only confirmed the final text.
- Marathi picks: none appeared. Marathi titles rarely reach 10 TMDB votes (movies) / 5 (shows). A per-language lower
  floor would add them, with more obscure titles.
- Whether keeping launcherx enabled-but-not-home (so the mic button's title pages work) costs too much RAM on this
  TV: not measured.
- Other brands' remotes: TCL/Xiaomi remotes may send `KEYCODE_SEARCH`/`ASSIST` as real keys; then `HomeView`'s
  handler starts our in-app listening. Untested.
- A TV already in Free My TV mode gets the mic permission only after the next switch (or a manual `pm grant`).

## Open items

1. Marathi: lower the vote floor for `mr` only, or use a separate query per language.
2. `local_languages` for LG/Samsung (`web/`, `tools/webapp.py`): Android only today.
3. Screensaver crash on this TV: `ClassCastException: BinderProxy cannot be cast to DreamService$DreamActivityCallbacks`
   in `DreamActivity` (seen twice, older than this session).
4. Search with a year ("Special Ops 2020") still finds nothing (from 2026-10-27).
5. Translations: 4 new strings ("Listening…", "Speak now. Back to stop.", "Stop", "Voice search needs the
   internet.") are English-only; `translate.py --check` reports the same 1288 missing entries as before.
6. Two stale branches from 2026-10-24 (`agent/direct-android`, `agent/direct-web`) still have worktrees; main
   appears to contain their work. Delete or merge.

## How the work was done

- Probes on the TV: `logcat` around each press, `dumpsys voiceinteraction`, `dumpsys audio` (recording activity),
  `dumpsys activity service com.google.android.tv.remote.service` (`Mic Open: n/n`), `getevent -lt`.
- Haiku subagents for TV probes and the mock (in a worktree); Sonnet for the Android change (in a worktree);
  reviewed and fixed in the parent, then installed and driven with `tools/tv.sh`.
