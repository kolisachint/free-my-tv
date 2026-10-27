# Home data API (Android)

For whoever draws the home screen. Everything here is in `app/src/io/freemytv/`, package-private, plain Java.
No UI code. Never call the network or disk from the main thread: every call below either is cheap (reads memory)
or runs in the background and answers on the main thread.

Texts are English. Images are never `original`: only `w300 / w500 / w780 / w1280` (backdrops) and
`w185 / w342 / w500 / w780` (posters, TMDB has no w300 for them).

## Recs: the entry point

`Recs recs = Recs.get(context);`

| Call | What |
|---|---|
| `List<Title> movies()` / `shows()` | ≤ 15 each, playable now. Memory only, safe to call every frame. Empty until the first fetch (or without a TMDB key). |
| `void refresh()` | Call in `onResume` and about once a minute while visible. Fetches rows (every 6 h or when the installed apps / region / hide_* changed) and Stremio progress (every 5 min). Cheap when fresh. |
| `void setActive(boolean)` | `false` in `onPause`: running requests are cut, nothing starts. `true` in `onResume`. |
| `void addDataListener(DataListener)` / `removeDataListener` | `onRows()`, `onContinue()` on the main thread. Both have default empty bodies. |
| `Net.Cancel details(Title, Callback<Title>)` | Details sheet: trailer, top-3 cast, full ways to watch. Answers with a **new** `Title` (`full == true`), or `null` on failure. Cached. `cancel()` on the returned handle when the sheet closes. |
| `List<ContinueItem> continueItems()` | The Continue row: latest 2 Stremio + latest 2 opened from home, newest first, ≤ 4. Cheap (cached for a minute). |
| `String rowsError()` | Last problem fetching rows ("" if fine). |
| `static boolean hasKey(Context)` | A TMDB key was built in. Without it there are no rows (the old home had no purpose either). |

Rows come from TMDB trending/week + recent releases on the installed apps + Stremio Cinemeta "top" (only when
Stremio is installed), merged one of each in turn; with `local_languages` set (e.g. `hi,mr`), every third slot is a
recent release in those languages on the installed apps. A title stays only if an **installed** streaming app has it in
`tmdb_region` (flatrate, free or ads) **or** it is in Cinemeta and Stremio is installed. `hide_animation` and
`hide_services` (also `stremio`) are respected. The list is saved to `files/catalog.json`, so the first paint after a
start is instant. `Recs` still has the old `Pick` API (`now()`, `showcase()`, `backdrop()`): only the screensaver
(`DreamView`) uses it now. The new home should not.

### Title

Read-only after you get it; a newer instance replaces it in `movies()` / `shows()` after `details()`.

| Field | Meaning |
|---|---|
| `tmdbId`, `kind` (`"movie"`/`"tv"`), `key()` (`"tv:1396"`) | identity |
| `imdbId` | `"tt…"` or `""` |
| `title`, `year` (0 = unknown), `genres` (≤ 2), `runtime` (min, movies), `seasons` (shows) | meta |
| `rating`, `imdbRating` | 0 = none; `imdbRating` says IMDb (Cinemeta) vs TMDB vote average |
| `overview` | English |
| `backdropPath`, `posterPath` | TMDB paths. Use `backdropUrl(widthPx)` / `posterUrl(widthPx)` |
| `trailerYouTubeId`, `cast` | only after `details()` (`full`) |
| `providers` | `Title.Provider` list: `name`, `kind` (`stream`/`rent`/`buy`), `appKey`, `pkg` (null if we have no app), `installed`, `price` (always `""`). Streaming first, installed first. |
| `stremio` | Stremio is a way to watch it. Rows: it is in Cinemeta. Search/details: it has an IMDb id. Always `false` if Stremio isn't installed. |
| `detailed` / `full` | providers & meta filled / trailer & cast filled |

Helpers: `metaLine()` ("2026 · Adventure, Biography · 2h 6m"), `cardLine()` ("2026 · IMDb 7.7"), `ratingText()`,
`installedStreams()`, `playable()`, `isMovie()`.

### ContinueItem

`title`, `kind`, `source` (`"stremio"` or the app key: `netflix`, `prime`, …), `sourceName` (badge text),
`subtitle` ("S2 E5 · 23 min left" / "Opened 2 h ago"), `progress` 0..1 (0 = no bar), `at`, `pkg`, `deepLink`.
Images: `backdropUrl(widthPx)` / `posterUrl(widthPx)` (TMDB or Stremio's metahub; `""` = draw a placeholder).
Open one with `Launch.open(context, item, options)`.

## Images

```java
Images.get(url, w, h, tag, (url, bitmap) -> { /* main thread; bitmap == null on failure */ });
Images.cancel(tag);   // cards that left the window; callbacks never fire after this
Images.peek(url, w, h) // in-memory hit or null (no I/O)
Images.trim();        // from onTrimMemory
```

`w`/`h` is the box the picture must **cover**; it is scaled down to that (never up), aspect kept, the caller crops.
Two threads, newest request first. JPEGs decode as RGB_565. Memory cache `min(16 MB, maxMemory/8)`; downloaded files in
`cacheDir/img`, 40 MB, oldest pruned. Never recycle a bitmap you got. Windowing (focused row ±1, others a
placeholder) is the caller's job: ask for what is near the focus, `cancel(tag)` for what left.

URL helpers: `Tmdb.image(path, widthPx)` (backdrops), `Tmdb.poster(path, widthPx)`.

## Search

```java
Search.query(context, "spid", result -> { … });   // main thread; debounce ~300 ms yourself
Search.cancel();                                  // when the search screen closes
List<Search.AppMatch> Search.apps(context, q);    // sync, no network, for instant app matches
```

`Result`: `query`, `titles` (≤ 9, best first; the top 5 have providers + `stremio`, the rest `detailed == false`:
call `Recs.details` when opened), `apps` (`pkg`, `label`; case/accent-insensitive, word prefix/substring, one typo from
4 letters), `failed` (no key or network). A new `query()` cuts the one running and its callback never fires.
Under 2 letters: apps only, no network. Matching is `Fuzzy` (same rules as the mock's `search.js`).

## Launch

All take a `Context` and an optional `Bundle options` (e.g. `ActivityOptions…toBundle()`); all try the deep link, then
fall back to the app's launch screen.

- `Launch.watch(context, title, provider, options, done)`: `provider == null` = first installed streaming app, else
  Stremio. Resolves Watchmode's title page first if `watchmode_key` is set (cached; background), then opens it.
  **Records the title in History** when it opened. `done.onDone(boolean)` on the main thread (may be null).
- `Launch.openStremio(context, title, options)`: `stremio:///detail/<movie|series>/<imdb>`.
- `Launch.open(context, continueItem, options)`.
- `Launch.trailer(context, title, options)`: `vnd.youtube:<id>` in YouTube TV, then `youtube.com/watch?v=`; without an
  id, YouTube search "<title> <year> trailer".
- `Launch.wifiSettings(context, options)`; `Launch.app(context, pkg, options)`.

Netflix has no public title address: with a Watchmode key it gets the `netflix.com/title/<id>` page, else the app opens.

## History

`History.record(title, appKey)` (Launch does it), `History.all()`, `History.clear()`. SharedPreferences `history`,
newest first, deduped by title, 20 max, titles only.

## Stremio

`stremio_auth_key` in config.properties (`Config.STREMIO_AUTH_KEY`; secret, never logged). `Recs.refresh()` runs
`datastoreGet` (libraryItem, all); items with a watch position, not removed, not ≥ 95 % done, newest first, appear in
`continueItems()`. No key → none.

## Wi-Fi

```java
Wifi wifi = new Wifi(context);
wifi.start(state -> …);   // now, then on every change (main thread); wifi.stop() in onPause
Wifi.State s = wifi.state();  // connected, wifi (vs Ethernet), label, level 0..4
```

`label` is the SSID when `ACCESS_FINE_LOCATION` is granted (`adb shell pm grant io.freemytv android.permission.ACCESS_FINE_LOCATION`;
Android 10+ hides the name otherwise), else "Wi-Fi" / "Ethernet"; "No internet" when not connected.

## Night mode (the weather is gone)

`HomeData.get(c)`: `sunrise`, `sunset` (epoch ms, 0 = none today), `isNight(now)`, `refreshIfStale()`,
`settingsChanged()`. Computed on the TV from `latitude`/`longitude` (`Sun`), no network. `Shaders.sky(palette, now,
data.sunrise, data.sunset)` as before. Settings → City still sets the location.

## Threads and config

One shared pool (`Work`): 2 job threads + 4 request threads; `Net` uses connect 8 s / read 15 s, gzip, redirects. Image
loading has its own 2 threads. Config used: `tmdb_key`, `tmdb_region`, `hide_animation`, `hide_services`,
`watchmode_key`, `stremio_auth_key`, `latitude`, `longitude`, `city`.

## Tests

`./build.sh && tools/test_data.sh` checks `Fuzzy`, Stremio's library parsing (a fixture) and `Sun` on a plain JVM
(a tiny org.json stand-in lives in `tools/testshim`). Not covered: anything that needs a TV or real keys.
