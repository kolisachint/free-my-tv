# Session 2026-11-01: one-week health check, screensaver fix

Owner's Sony BRAVIA VH2 (Android 12) at 192.168.1.7, after a week in Free My TV mode.

## Fixed

| Problem | Cause | Fix |
|---|---|---|
| Screensaver crashed on **every** start (open item 3 of 2026-10-28; 6 crashes in the exit log, Oct 30 – Nov 1) | Android 12's `DreamActivity` runs in the app's default process; our dream ran in `:home`, so the callback was a `BinderProxy` (`ClassCastException`). Same as Android 11 (#10). | `ScreensaverAlone` (main process) now covers API 30–32: `values-v31` → `values-v33`, `HomeMode.DREAM`. Reproduced before (3/3 crashes via `Somnambulator`), verified after (3/3 shown, opens in 0.1–0.25 s). |

TVs already switched get the new component from the repair job (`HomeMode`, `screensaver_components` check). On the
owner's TV it was set by hand: `settings put secure screensaver_components io.freemytv/io.freemytv.ScreensaverAlone`.
Android 13+ still uses `Screensaver` in `:home`: not tested on a real TV.

## Measured (healthy)

| What | Value |
|---|---|
| Home scrolling | 0–0.6% janky; frames either 8–9 ms or 21–23 ms (see below) |
| `:home` PSS | 39–56 MB |
| Screensaver | 0% janky, 5 ms frames, 31 MB PSS (main process) |
| RAM available | 1.1–1.3 GB (`MemAvailable`) |
| CPU | ~87% idle. Load average ~33 is 32 Sony/MediaTek driver threads parked in `D` state (tuner, picture), not work |
| Storage | `/data` 1.0 GB free of 4 GB (25%) |
| Thermal | status 0 |
| Animations | 0.5x (window, transition, animator) |

The 21–23 ms frames are not slow drawing: the app's own work stays ~8 ms; the extra is `DequeueBufferDuration`
(~12.6 ms), waiting for a free buffer because the queue is already full. No missed frames.

## Open

- Disabled launcherx still holds 169 MB of cache on `/data` (`pm trim-caches` would free it and other apps' caches).
