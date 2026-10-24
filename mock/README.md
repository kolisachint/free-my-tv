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
| D | Debug overlay: images loaded, estimated decoded MB, last frame ms |

Down from the Apps shelf opens the drawer. Up from its first row closes it.

## URL flags (for screenshots)

`?overview` `?night` `?drawer` `?nocontinue` (Continue row hidden, focus starts on Movies)
`?lowram` (lighter variant of the default: no shadows at all; same as key R) `?debug` (debug overlay, same as key D)
 `?details=movie:2` `?details=show:3` `?details=cont:0`
`?focus=movie:5` `?focus=apps:2` (combine with `&`).

## Components

1. **Stage**: fixed 1920x1080, scaled by transform.
2. **Page background**: day/night vertical gradient.
3. **Clock**: time + short date, top left.
4. **Round glass buttons** (3): Live TV, HDMI Inputs, Settings; name shown on focus.
5. **Hero backdrop**: focused item's image, masked to fade into the page; crossfades.
6. **Hero text**: title, meta line, 2-line overview.
7. **Where-to-watch badges**: installed apps only (check icon), Stremio last. `INSTALLED` in `data.js` drives shelf, chips and details sheet.
8. **Row label + position counter** ("3 / 15", only on the focused row).
9. **Continue card** (landscape, 2): Stremio tag, "S2 E5 · 23 min left", progress bar.
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
20. **Debug overlay** (D): top-left stats panel.
20b. **Hint strip**: bottom-right key list (H hides).
21. **Overview mode**: the labelled all-in-one page.
22. **Day/night + low-RAM switches**.

## Performance plan (the default profile; target Sony BRAVIA, Android 12, 2.85 GB RAM, ~80 MB free, Mali-G52, 1920x1080)

- 15 Movies + 15 Shows.
- Card images at small size (metahub `small`, about w300); hero and details backdrop at medium.
- Windowed image loading: only cards in the focused row's visible window +-1 hold an image; the rows next to it keep 2; every other row is back to a gradient + title placeholder. The old hero image is dropped after the fade.
- No large blurred shadows: one small soft shadow (`0 4px 12px`); `?lowram` / R removes shadows entirely.
- Focus animation: transform only, 150 ms. Hero crossfade: opacity only, 150 ms.
- Debug overlay (D) shows loaded images, estimated decoded memory (w x h x 4 at displayed size) and last frame time against the budget: <= 10 full images, <= 16 MB.
- Installed apps match the real TV; Disney+ and SonyLIV show only as dimmed "Not installed" providers in the details sheet.

## Fake

Providers, prices and continue-watching progress are invented (see `data.js`). Titles, overviews, casts and
images are real (Cinemeta). Images come from metahub, not TMDB w780/w1280 (no TMDB key needed).
