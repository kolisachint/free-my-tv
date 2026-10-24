# Home screen mock

Static design mock of the redesigned TV home. 1920x1080 stage, scaled to the window. No real TV integration.

## Open

    open mock/index.html

or, from the repo root:

    python3 -m http.server      # then http://localhost:8000/mock/

Images load from the internet (metahub, Cinemeta). Offline, cards show a gradient with the title.

## Keys

| Key | Action |
|---|---|
| Arrows | Move focus |
| Enter | Open details (cards) / toast (everything else) |
| Backspace or Esc | Back (closes details, then drawer) |
| N | Day / night |
| O | Overview: whole home on one scrolling page, each part labelled "Component: ..." (O or Esc leaves) |
| H | Hide / show the key hint strip (bottom right) |
| Mouse wheel | Down / up one row (throttled); shift+wheel or sideways wheel = left / right |
| Hover / click / right-click | Hover focuses, click = Enter, right-click = Back |
| R | No shadows at all on / off |
| D | Debug overlay (bottom-left, above the hint strip): images loaded, estimated decoded MB, last frame ms |
| W | Wi-Fi connected / disconnected |
| S or / | Search (letters type inside it; Backspace deletes, Back when empty) |
| M | Simulated remote mic: "Listening…" ~1.5 s, then a sample phrase is searched |

Down from the Apps shelf opens the drawer. Up from its first row closes it.

## URL flags (for screenshots)

`?overview` `?night` `?drawer` `?offline` (Wi-Fi shows "No internet") `?nocontinue` (Continue row hidden, focus starts on Movies) `?norecent` (clears the saved launch history; the Continue row then shows only Stremio items)
`?search` `?search=spider` `?search=xyzq` `?mic` (open Search, with a query, with no results, or already listening)
`?lowram` (lighter variant of the default: no shadows at all; same as key R) `?debug` (debug overlay, same as key D)
 `?details=movie:2` `?details=show:3` `?details=cont:0`
`?focus=movie:5` `?focus=apps:2` (combine with `&`).

## Components

1. **Stage**: fixed 1920x1080, scaled by transform.
2. **Page background**: day/night vertical gradient.
3. **Clock**: time + short date, top left.
4. **Round glass buttons** (4): Search, Inputs (HDMI), Wi-Fi, Settings, each with an always-visible label under it (22 px, ellipsis). Wi-Fi draws 4 signal bars (3 lit) and the network name ("Home-5G"); disconnected = faint bars with a slash and the label "No internet" (`?offline`, key W toggles). Live TV is only in the drawer's Inputs row.
5. **Hero backdrop**: focused item's image, masked to fade into the page; crossfades.
6. **Hero text**: title, meta line, 2-line overview.
7. **Where-to-watch badges**: installed apps only (check icon), Stremio last. `INSTALLED` in `data.js` drives shelf, chips and details sheet.
8. **Row label + position counter** ("3 / 15", only on the focused row).
9. **Component: Continue** (row "Continue", landscape cards, ≤ 4): last 2 items opened from home + last 2 Stremio in-progress, merged newest first. Each card has an app badge ("Netflix", "Stremio"…) and a second line ("Opened 2 h ago" / "23 min left"). Launches are recorded when Watch (or an installed way to watch) is pressed in the details sheet, including from Search; stored in `localStorage` key `mock.recent` as `{id, kind, title, app, at}`, newest first, de-duplicated by title, max 20, seeded on first load (a Netflix movie, a Prime show). A launch card reopens the details sheet (Watch = same app); a Stremio card toasts "Resuming in Stremio" and shows a progress bar + "S2 E5 · 23 min left". Row hidden when empty or with `?nocontinue`.
10. **Media card** (landscape, 15 movies, 15 shows): image, title, year · IMDb.
11. **Card fallback**: gradient + title when an image fails.
12. **Focus treatment**: scale 1.08 + thick border + outer outline.
13. **Row scroll**: vertical (translateY of rows) and horizontal (translateX of strip).
14. **App shelf**: glass platter with brand-color tiles, installed apps only (6: Netflix, YouTube, Prime Video, Apple TV, JioHotstar, Stremio).
15. **Drawer**: Inputs row (Live TV, HDMI 1-4, one "No signal" dashed + dimmed) and Shortcuts row.
16. **Details sheet**: backdrop, poster, title, meta, overview, cast, two buttons.
17. **Ways to watch panel**: Stream / Rent / Buy groups, "Installed" / "Not installed" labels, "via Stremio" last.
18. **Footnote**: "Where-to-watch data: JustWatch via TMDB".
19. **Toast**: bottom pill for every fake action.
20. **Debug overlay** (D): bottom-left stats panel (above the hint strip, clear of the clock).
20b. **Hint strip**: bottom-right key list (H hides).
21. **Overview mode**: the labelled all-in-one page.
22. **Day/night + low-RAM switches**.
23. **Component: Search** (`search.js`): full-screen sheet; query field (52 px) with caret + mic chip; on-screen TV keyboard (a-z, 0-9, Space, Delete, Clear) plus real typing; results after 2+ letters with 300 ms debounce; best match as a vertical list of tiles (every result looks like the best-match card: backdrop, title, meta, 2-line overview, installed chips + Stremio; list scrolls, counter "2 / 9"; app matches are compact tiles at the end); after a mic search focus lands on result 1; empty state "No results for ‘xyz’. Try a shorter word."; before typing: Recent searches (localStorage `fmtv_recent`, max 5) and Trending chips. Matching is case/accent-insensitive, word-prefix + substring, 1-edit typo for words of 4+ letters, over title (primary), cast and genre (secondary) and installed app names. About 15 search-only titles (`EXTRA` in `data.js`, Cinemeta data; providers FAKE) include Indian ones. Enter on a title opens the details sheet and Back returns to the search with focus kept; Enter on an app toasts "Opening Netflix"; Back from search returns to the Search button.

## Performance plan (the default profile; target Sony BRAVIA, Android 12, 2.85 GB RAM, ~80 MB free, Mali-G52, 1920x1080)

- 15 Movies + 15 Shows.
- Card images at small size (metahub `small`, about w300); hero and details backdrop at medium.
- Windowed image loading: every card visible on screen in the focused row, plus 1 either side, holds an image; the rows next to it keep 2; every other row is back to a gradient + title placeholder. The old hero image is dropped after the fade.
- No large blurred shadows: one small soft shadow (`0 4px 12px`); `?lowram` / R removes shadows entirely.
- Focus animation: transform only, 150 ms. Hero crossfade: opacity only, 150 ms.
- Debug overlay (D) shows loaded images, estimated decoded memory (w x h x 4 at displayed size) and last frame time against the budget: <= 10 full images, <= 16 MB.
- Installed apps match the real TV; Disney+ and SonyLIV show only as dimmed "Not installed" providers in the details sheet.

## Fake

Providers, prices and Stremio progress and the seeded launch history are invented (see `data.js`). Titles, overviews, casts and
images are real (Cinemeta). Images come from metahub, not TMDB w780/w1280 (no TMDB key needed).

## Trailer source

The mock embeds a real YouTube id per title (`trailer` in `data.js`, from Cinemeta `trailerStreams[].ytId`; `null` when none: the button then reads "Find trailer"). The Android build will use TMDB `/movie/{id}/videos` (type=Trailer, site=YouTube, prefer official, language en), fall back to Cinemeta trailers, then a YouTube search intent.

## Continue history

Only titles are recorded in `mock.recent` (Watch / a way to watch in the details sheet). Opening an app from the shelf or a search app tile never is.

## Focus spacing

Focused cards scale 1.05; with the focus ring this stays inside the gap between cards (28 px in Continue, 32 px in Movies / Shows), so a focused card never covers its neighbour.
