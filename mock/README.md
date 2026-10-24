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
| R | Low-RAM profile on / off (reloads the page with `?lowram`) |

Down from the Apps shelf opens the drawer. Up from its first row closes it.

## URL flags (for screenshots)

`?overview` `?night` `?drawer` `?nocontinue` (Continue row hidden, focus starts on Movies)
`?lowram` (planned low-RAM profile: Movies 8, Shows 8, smaller card images, no shadows, instant hero swap, counters show 8)
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
14. **App shelf**: glass platter with brand-color tiles, installed apps only (7: Netflix, YouTube, Prime Video, Disney+, JioHotstar, SonyLIV, Stremio).
15. **Drawer**: Inputs row (Live TV, HDMI 1-4, one "No signal" dashed + dimmed) and Shortcuts row.
16. **Details sheet**: backdrop, poster, title, meta, overview, cast, two buttons.
17. **Ways to watch panel**: Stream / Rent / Buy groups, "Installed" / "Not installed" labels, "via Stremio" last.
18. **Footnote**: "Where-to-watch data: JustWatch via TMDB".
19. **Toast**: bottom pill for every fake action.
20. **Hint strip**: bottom-right key list (H hides).
21. **Overview mode**: the labelled all-in-one page.
22. **Day/night + low-RAM switches**.

## Fake

Providers, prices and continue-watching progress are invented (see `data.js`). Titles, overviews, casts and
images are real (Cinemeta). Images come from metahub, not TMDB w780/w1280 (no TMDB key needed).
