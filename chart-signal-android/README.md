# Chart Signal (Android)

Reads the price chart on your screen and shows a floating dot:

- green **CALL** / red **PUT**: SINDy-PI and the neural net agree, and their agreeing calls passed the backtest bar
- white **WAIT**: they disagree, the backtest was too weak, or the chart couldn't be read (tap the dot for the reason)
- grey **!**: an error

Everything runs on the phone. The app has no internet permission.

## How it works

1. **Reading the chart** (`ChartExtractor`): you draw a box over the chart once. Each read, the
   background color is found, grid and price lines are removed, and the middle of the remaining
   ink in each pixel column becomes the price at that moment. No OCR; only relative movement is used.
2. **SINDy-PI** (`SindyPi`): state is the z-scored price `x` and its velocity `v`. A library of
   polynomial terms in (x, v) plus implicit terms `a, a·x, a·v` (a = acceleration) is fit with
   sequentially thresholded ridge regression, once per implicit left-hand side. The candidate with
   the lowest held-out error is kept and integrated forward over the expiry.
3. **Neural net** (`TinyMlp`): 16 inputs (recent price steps) → 12 tanh → 1 output (change over the
   expiry). Trained from scratch on the visible chart each read, with Adam and a fixed seed.
4. **Backtest gate** (`Predictor`): both models are refit at 20 earlier points on the same chart and
   checked against what happened next. A signal is shown only when both models agree now, at least
   10 backtest points had them agreeing, and those agreeing calls hit at least the bar you set
   (default 65%).

## Tested so far (synthetic data, desktop JVM)

| Series | Backtest hit rate when both agree | Signals given | Signals correct |
|---|---|---|---|
| Damped oscillator + noise (predictable) | 98% | 45/60 | 31/45 |
| Trend + noise | 78% | 32/60 | 30/32 |
| Random walk (unpredictable) | 50% | 3/60 | 1/3 |

The extractor recovered a rendered candlestick chart with grid lines, a price line and a price tag
at correlation 0.96. Not yet tested on a real phone or a real trading app.

## Build

GitHub Actions builds `app-debug.apk` on every push that touches this folder
(`.github/workflows/build-apk.yml`). Download it from the run's **Artifacts**.
