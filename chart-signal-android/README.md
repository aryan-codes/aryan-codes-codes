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
  least-squares fit (y = a·new + b) on the newest part of the overlap, after trying small corrections
  to the time scale. A read that can't be matched within 1.5% of the box height starts a new segment.
  The best time-scale correction doubles as a check on the "seconds across the chart box" setting.
- **Scorer** (`Scorer`): takes one CALL/PUT at a time (like one trade per expiry) and scores it
  against the stitched history when the expiry passes. The overlay shows the session tally.
- **Recorder** (`Recorder`): one JSON line per read (curve, settings, every model's output, signal)
  plus trade open/settle lines, in the app's private storage. Optional chart-box image every 30 s.
  **Export recordings** writes everything into one .zip where you choose.

Simulated test (20 minutes of price, chart window rescaled every read, reads every ~1 s with jitter
and 5% dropped frames, span setting correct or 10-15% off; 30, 60 and 120 s spans):

- 97-99.9% of 5 s and 15 s moves have the right direction in the stitched history
- 97-100% of scored trades match the true outcome
- the span check lands within ~3% of the true span

## Build

GitHub Actions builds `app-debug.apk` on every push that touches this folder
(`.github/workflows/build-apk.yml`). Download it from the run's **Artifacts**.
