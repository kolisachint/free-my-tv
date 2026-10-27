# Free My TV: guide for AI assistants

The full guide is [CLAUDE.md](CLAUDE.md): read it first and follow it (it is written for any assistant, not only
Claude Code). Most people ask in plain words, like "Install Free My TV on my TV at 192.168.1.50. I live in Lisbon.",
and don't know what a terminal is: explain each step simply, and ask before anything that changes their TV or computer.

## Mockups: keep them, test them first

The owner tests every home-screen change on a mockup **before** it goes to a TV. Never delete or strip the mockup code.

- `mock/`: the static redesign mock (hero + Continue in Stremio + Movies 15 + Shows 15 + app shelf, details sheet).
  Open `mock/index.html`, or run `python3 -m http.server` from the repo root and go to `http://localhost:8000/mock/`.
  Keys: arrows, Enter, Backspace (Back), `N` night, `R` low-RAM look. Flags for screenshots: `?night`, `?lowram`,
  `?drawer`, `?details=movie:2`, `?focus=movie:5`. See `mock/README.md`.
- `web/` `?mockup` (`data.js` `mockupPicks`, `home.js` `MOCKUP`/`mockupOpen`/`mockupApp`): sample picks without a
  TMDB key. Keep it.
- Change the mock first, show screenshots, get an OK, then port to `HomeView.java` (Android is the target).

## Testing on the TV

- `TV=192.168.1.7 . tools/tv.sh`, then `tv_up`, `k <KEYS>`, `s <name>` (screenshot to `/tmp/t_<name>s.png`),
  `tv_search 'X on TV'`, `tv_playing <pkg>` (Netflix is DRM: screenshots are black), `tv_top`, `tv_perf`.
- Per-app deep links that work, measurements and open items: `docs/sessions/` (latest first).

## Home redesign decisions (Android)

- TMDB key is required; without it this home has no purpose (the owner uses another clean UI then).
- No weather, no crypto, no Explore/Movies/Shows tabs. Small clock in the top bar.
- Rows: Continue in Stremio (last 2, hidden when empty) → Movies 15 → Shows 15 → shelf (installed apps only).
- Hero follows focus; shows installed apps only. Details sheet: trailer opens YouTube; ways to watch from TMDB
  watch providers (JustWatch data), installed first, others labeled "Not installed", Stremio last.
- Recs: TMDB + TMDB watch providers + Stremio Cinemeta catalogs. Stremio auth token: ask once, persist, reuse.
- Screensaver backdrops at `w1280`, never `original`.
- Size row counts and image sizes to the measured TV RAM (see CLAUDE.md/ADB `dumpsys meminfo`); no Rust rewrite.
- Owner's TV (192.168.1.4, measured 2026-10-24): Sony BRAVIA VH2, Android 12, MediaTek mt5895, 4x1.8 GHz,
  32-bit userspace (armeabi-v7a), Mali-G52, 2.85 GB RAM (~80 MB truly free, zram full), heapgrowthlimit 192m,
  UI 1920x1080 on a 4K panel. Free My TV used 47 MB PSS total (`:home` 37 MB); YouTube 312 MB, Netflix 124 MB.
  `:home` frames: 26% janky, p95 200 ms, so the bottleneck is drawing time, not RAM. Installed: Netflix, YouTube,
  Prime Video, Apple TV, Hotstar, Stremio (`com.stremio.one`).
