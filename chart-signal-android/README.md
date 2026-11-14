# Chart Signal (Android)

Watches the phone screen while a trading app is open and shows a floating dot:

- green **CALL**: Claude reads the chart as leaning up
- red **PUT**: leaning down
- white **WAIT**: no clear edge, don't trade
- grey **!**: an error (bad key, no network, rate limit); tap the dot to read it

Every few seconds one screen frame is sent to the Claude API with your own API key.
Each read takes a few seconds, so the dot lags the live price. These are unverified guesses,
not a tested strategy.

## Build

GitHub Actions builds `app-debug.apk` on every push that touches this folder
(workflow: `.github/workflows/build-apk.yml`). Download it from the run's **Artifacts**.

Local build: Android SDK + `gradle assembleDebug` in this folder.

## Install

1. Copy `app-debug.apk` to the phone and open it; allow installs from that source.
2. Open Chart Signal, paste an Anthropic API key, choose model, expiry and read interval.
3. Tap **Start watching**, allow "display over other apps", then allow screen capture.

## Cost

Each read is one image of about 1,500 input tokens plus a short answer. Reading every 5 seconds is
720 reads an hour. Haiku 5.5 is much cheaper than Opus 5.5 for this; check console.anthropic.com usage.
