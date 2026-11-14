# Chart Signal (Android)

Reads the price chart on your screen and shows a floating dot:

- green **CALL** / red **PUT**: SINDy-PI and the neural net agree, and their agreeing calls passed the backtest bar
- white **WAIT**: they disagree, the backtest was too weak, or the chart couldn't be read (tap the dot for the reason)
- grey **!**: an error

Everything runs on the phone. The app has no internet permission.

## How it works

1. **Reading the chart** (`ChartExtractor`): you draw a box over the chart once. Each read, the
   line's colour is found automatically (the most common strongly coloured ink; both colours for
   red/green candles), so grey/white labels, buttons, price tags in other colours and time markers
   are ignored. The line is then followed as one connected stroke from the latest point leftwards,
   so anything not connected to it is skipped. If no coloured line can be followed, it falls back
   to the median height of all ink per column. No OCR; only relative movement is used.
2. **SINDy-PI** (`SindyPi`): state is the z-scored price `x` and its velocity `v`. A library of
   polynomial terms in (x, v) plus implicit terms `a, a·x, a·v` (a = acceleration) is fit with
   sequentially thresholded ridge regression, once per implicit left-hand side. The candidate with
   the lowest held-out error is kept and integrated forward over the expiry.
3. **Neural net** (`TinyMlp`): 16 recent price steps plus 4 trend-strength inputs (slope over 16 and
   32 steps, efficiency ratio, new-high/new-low breakout flag) → 12 tanh → 1 output (change over the
   expiry). Trained from scratch on the visible chart each read; recent samples weigh more.
4. **Momentum**: the least-squares slope of the last max(10, expiry) points, carried forward.
5. **Weighted vote** (`Predictor`): all three are refit at 20 earlier checkpoints on the same chart.
   Each model's vote weight is its recent hit rate above 50% (newer checkpoints count more), so the
   model that suits the current regime (trend or range) leads. A signal needs:
   - a clear vote (at least 34% net in one direction),
   - at least 8 backtest calls by the combined vote, with a recency-weighted hit rate above your bar
     (default 65%, raised automatically when checkpoints are crowded together),
   - no clean fresh move (efficiency > 0.35 over the last expiry) against the call, unless the
     backtest is 85%+. A move younger than the expiry has no scored checkpoint yet, so this stops
     the app betting on a reversal of a trend it can't evaluate.

## Tested so far (synthetic data, desktop JVM, 60 charts each, expiry = 20 of 240 points)

| Chart | Previous version: signals (correct) | Current: signals (correct) |
|---|---|---|
| Range, then trend in the last 20 points | 6 (0) | 2 (2) |
| Range, then trend in the last 40 points | 6 (6) | 13 (13) |
| Range, then trend in the last 60 points | 9 (9) | 36 (36) |
| Steady trend + noise | 38 (37) | 46 (42) |
| Pure range (mean-reverting) | 17 (11) | 17 (11) |
| Oscillator | 60 (60) | 60 (60) |
| Random walk | 3 (3) | 6 (4) |
| Random walk, expiry 5 points | 3 (1) | 2 (0) |

The extractor recovered a rendered candlestick chart at correlation 0.96. Not yet tested on a real
phone or a real trading app.

## Live scoring and recording

- **Stitcher** (`Stitcher`): joins consecutive reads into one continuous price history. The app
  rescales its price axis as the chart scrolls, so each new curve is mapped onto the history with a
  robust least-squares fit (y = a·new + b, worst 20% of points dropped) on the newest part of the
  overlap, trying time-scale corrections from 0.4x to 2.5x. The best correction measures how fast the
  chart really scrolls, and the forecast horizon uses that measured span instead of the setting.
  A single read that doesn't match is skipped; a new segment starts only after 4 misses in a row.
- **Scorer** (`Scorer`): takes one CALL/PUT at a time (like one trade per expiry) and scores it
  against the stitched history when the expiry passes. The overlay shows the session tally.
- **Recorder** (`Recorder`): one JSON line per read (curve, settings, every model's output, signal)
  plus trade open/settle lines, in the app's private storage. Optional chart-box image every 30 s.
  **Export recordings** writes everything into one .zip where you choose.

Simulated tests:

- Price-only stitching (20 min, chart rescaled every read, span setting right or 15-60% off):
  98-100% of scored trades match the true outcome; the measured span lands within ~3%.
- End to end on rendered frames that look like the app (blue stepped line, buttons, labels, price
  tag, the user's box, real span 70 s with the setting at 30 s, reads every ~0.45 s, 5 min):
  line found on 647/647 reads, 615 joined, measured span 70 s, 51 trades scored (7 unscored),
  47/51 scored correctly.

## Demo-only auto-trader (test bot)

`AutoTrader` + `TapService` (an accessibility service that only taps) + `TextReader` (ML Kit on-device
text recognition). Off by default.

- Arming, once per session: text recognition must read "DEMO" on the highlighted badge (one wrong
  letter tolerated, up to 30 frames tried). A colour fingerprint of both badges is then saved.
- Before every trade: DEMO must still be the highlighted badge and both badges must still match their
  fingerprints. If the account is switched to REAL the highlight moves and it stops for the session.
  It never trades on a REAL account and never trades before arming succeeds.
- One trade at a time, on every CALL/PUT signal, by tapping the BUY/SELL positions you marked.
- Each trade is graded by the broker's own balance: read just before the tap and again after expiry
  (+stake*payout = won, -stake = lost, unchanged = refund, anything else = unclear and not counted).
- Stops after a trade limit or a balance drop you set. Every trade is logged in the recording.

The safety and grading rules are covered by off-device tests (29 checks: REAL selected, badges
swapped, both highlighted, unreadable badges, OCR misreads before arming, switching to REAL mid-session,
badge appearance changing, tap service off, stop-loss, trade cap, balance parsing).

## Build

GitHub Actions builds `app-debug.apk` on every push that touches this folder
(`.github/workflows/build-apk.yml`). Download it from the run's **Artifacts**.
