package com.chartsignal;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service. Mirrors the screen into an ImageReader, reads the price curve from the chart
 * area the user marked, runs SINDy-PI + a small on-device neural net, and shows the result as a
 * floating dot. Everything runs on the phone; nothing is sent anywhere.
 */
public class WatchService extends Service {

    public static final String ACTION_START = "com.chartsignal.START";
    public static final String ACTION_STOP = "com.chartsignal.STOP";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_DATA = "data";

    private static final String CHANNEL = "watch";
    private static final int NOTIF_ID = 1;
    private static final int MAX_EDGE = 1280;   // capture resolution, longest side
    private static final int MAX_POINTS = 240;  // samples taken from the chart

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private int capW, capH;
    private int[] pixels;
    private volatile boolean busy = false;
    private volatile boolean running = false;
    private long lastRunAt = 0;
    private long lastSignalAt = 0;

    // settings
    private double spanSec, expirySec, minHit;
    private long intervalMs;
    private float boxL, boxT, boxR, boxB; // fractions of screen; boxR <= boxL means not set
    private boolean recordOn, imagesOn;

    // live scoring + recording (used only on the worker thread)
    private final Stitcher stitcher = new Stitcher(30 * 60 * 1000L);
    private final Scorer scorer = new Scorer();
    private Recorder recorder;
    private volatile String scoreLine = "";

    // overlay
    private WindowManager wm;
    private LinearLayout overlay;
    private WindowManager.LayoutParams overlayLp;
    private TextView dot, label;
    private Spark spark;
    private boolean expanded = false;
    private String detail = "";
    private FrameLayout selector;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (running) return START_NOT_STICKY;

        startInForeground();
        loadSettings();

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = data == null ? null : mpm.getMediaProjection(resultCode, data);
        if (projection == null) { stopSelf(); return START_NOT_STICKY; }

        captureThread = new HandlerThread("capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { main.post(WatchService.this::stopSelf); }
        }, captureHandler);

        DisplayMetrics dm = realMetrics();
        float scale = Math.min(1f, (float) MAX_EDGE / Math.max(dm.widthPixels, dm.heightPixels));
        capW = Math.round(dm.widthPixels * scale);
        capH = Math.round(dm.heightPixels * scale);
        reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2);
        display = projection.createVirtualDisplay("ChartSignal", capW, capH, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, captureHandler);

        if (recordOn) {
            recorder = new Recorder(this, imagesOn);
            try {
                recorder.write(new JSONObject().put("type", "session").put("version", 2)
                    .put("t", System.currentTimeMillis()).put("capW", capW).put("capH", capH)
                    .put("span", spanSec).put("exp", expirySec).put("minHit", minHit)
                    .put("intervalMs", intervalMs));
            } catch (Exception ignored) { }
        }
        showOverlay();
        running = true;
        captureHandler.postDelayed(tick, 500);
        if (boxR <= boxL) main.postDelayed(this::openSelector, 600);
        return START_NOT_STICKY;
    }

    private DisplayMetrics realMetrics() {
        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        return dm;
    }

    private void loadSettings() {
        SharedPreferences p = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        spanSec = p.getInt("spanSec", 120);
        expirySec = p.getInt("expirySec", 30);
        minHit = p.getInt("minHit", 65) / 100.0;
        intervalMs = p.getInt("intervalMs", 1000);
        boxL = p.getFloat("boxL", 0); boxT = p.getFloat("boxT", 0);
        boxR = p.getFloat("boxR", 0); boxB = p.getFloat("boxB", 0);
        recordOn = p.getBoolean("record", true);
        imagesOn = p.getBoolean("images", false);
    }

    // ---------------- capture + analysis ----------------

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            Image img = null;
            try {
                img = reader.acquireLatestImage();
                long now = SystemClock.elapsedRealtime();
                if (img != null && !busy && boxR > boxL && selector == null && now - lastRunAt >= intervalMs) {
                    lastRunAt = now;
                    copyPixels(img);
                    busy = true;
                    final int[] px = pixels;
                    final long wall = System.currentTimeMillis();
                    worker.execute(() -> analyze(px, wall));
                }
            } catch (Exception e) {
                String m = String.valueOf(e.getMessage());
                main.post(() -> setError(m));
            } finally {
                if (img != null) img.close();
            }
            if (lastSignalAt > 0 && SystemClock.elapsedRealtime() - lastSignalAt > intervalMs * 4 + 3000) {
                main.post(() -> { if (dot != null) dot.setAlpha(0.35f); });
            }
            captureHandler.postDelayed(this, 150);
        }
    };

    private void copyPixels(Image img) {
        Image.Plane plane = img.getPlanes()[0];
        ByteBuffer buf = plane.getBuffer();
        int pixelStride = plane.getPixelStride(), rowStride = plane.getRowStride();
        int w = img.getWidth(), h = img.getHeight();
        int padded = rowStride / pixelStride;
        Bitmap full = Bitmap.createBitmap(padded, h, Bitmap.Config.ARGB_8888);
        full.copyPixelsFromBuffer(buf);
        int[] px = new int[w * h];
        full.getPixels(px, 0, w, 0, 0, w, h);
        full.recycle();
        pixels = px;
    }

    private void analyze(int[] px, long now) {
        try {
            int l = Math.round(boxL * capW), t = Math.round(boxT * capH);
            int r0 = Math.round(boxR * capW), b = Math.round(boxB * capH);
            ChartExtractor.Curve c = ChartExtractor.extract(px, capW, capH, l, t, r0, b, MAX_POINTS);
            if (recorder != null) recorder.maybeImage(px, capW, capH, l, t, r0, b, now);
            if (c == null) {
                log(new JSONObject().put("type", "read").put("t", now).put("noCurve", true));
                main.post(() -> setWait("no chart line found in the box", "Long-press the dot to redraw the chart box."));
                return;
            }
            double dtSetting = spanSec * c.coverage / c.n;   // seconds per sample, from the setting

            // live scoring: extend the continuous history, settle any expired trade
            stitcher.update(c.p, c.n, dtSetting, now);
            scorer.settle(stitcher);
            if (scorer.settled != null) logTrade("settle", scorer.settled);

            // once stitching has measured how fast the chart really scrolls, use that instead of the
            // setting, so a wrong "seconds across the box" doesn't skew the forecast horizon
            double sh = stitcher.scaleHint();
            final double dt = Double.isNaN(sh) ? dtSetting : dtSetting * sh;
            int h = (int) Math.max(1, Math.round(expirySec / dt));
            Predictor.Result r = Predictor.analyze(c.p, c.n, h, minHit);
            Scorer.Trade opened = scorer.offer(r.signal, stitcher, now, Math.round(expirySec * 1000));
            if (opened != null) logTrade("open", opened);
            scoreLine = scorer.summary() + (scorer.hasOpen() ? " · 1 open" : "");
            logRead(now, c, dt, r);

            if (r.hMax > 0 && h > r.hMax) {
                int seen = (int) Math.round(c.n * dt);
                int maxExp = (int) Math.floor(r.hMax * dt);
                String msg = "expiry " + (int) expirySec + "s > max " + maxExp + "s";
                String more = "Box shows ~" + seen + "s of price (" + Math.round(c.coverage * 100)
                    + "% of its width has candles). Expiry " + (int) expirySec + "s needs ~"
                    + (int) Math.ceil(expirySec * c.n / (double) r.hMax) + "s of visible price.\n"
                    + "Fix: zoom the chart out so more candles show, or shorten the expiry. "
                    + "Also check 'Seconds shown across the chart box' matches the time axis."
                    + calibrationHint();
                main.post(() -> setWait(msg, more));
                return;
            }
            main.post(() -> show(r, c, dt));
        } catch (Throwable t) {
            String m = String.valueOf(t.getMessage());
            main.post(() -> setError(m));
        } finally {
            busy = false;
        }
    }

    /** If stitching shows the chart scrolls faster or slower than the setting implies, say so. */
    private String calibrationHint() {
        double s = stitcher.scaleHint();
        if (Double.isNaN(s)) return "\nMeasuring how fast the chart scrolls…";
        String m = "\nMeasured box span ~" + Math.round(spanSec * s) + "s (setting " + (int) spanSec + "s)";
        if (Math.abs(s - 1) >= 0.07) m += ": using the measured value. Update the setting to match.";
        return m;
    }

    private void log(JSONObject o) {
        if (recorder != null) recorder.write(o);
    }

    private void logRead(long now, ChartExtractor.Curve c, double dt, Predictor.Result r) {
        if (recorder == null) return;
        try {
            JSONObject o = new JSONObject()
                .put("type", "read").put("t", now)
                .put("box", new JSONArray().put(boxL).put(boxT).put(boxR).put(boxB))
                .put("cov", c.coverage).put("first", c.firstCol).put("last", c.lastCol).put("tracked", c.tracked)
                .put("n", c.n).put("dt", dt).put("h", r.h)
                .put("p", Recorder.arr(c.p, c.n, 4))
                .put("seg", stitcher.segment()).put("joined", stitcher.lastJoined)
                .put("sig", r.signal).put("why", r.why)
                .put("d", new JSONArray().put(r.sindyDelta).put(r.nnDelta).put(r.momDelta))
                .put("acc", new JSONArray().put(r.sindyAcc).put(r.nnAcc).put(r.momAcc))
                .put("w", new JSONArray().put(r.sindyW).put(r.nnW).put(r.momW))
                .put("ens", r.ensAcc).put("ensCalls", r.ensCalls).put("fresh", r.freshEr)
                .put("sindy", r.sindyInfo);
            if (!Double.isNaN(r.vote)) o.put("vote", r.vote);
            if (!Double.isNaN(stitcher.lastRms)) o.put("rms", stitcher.lastRms);
            double sh = stitcher.scaleHint();
            if (!Double.isNaN(sh)) o.put("scaleHint", sh);
            recorder.write(o);
        } catch (Exception ignored) { }
    }

    private void logTrade(String type, Scorer.Trade tr) {
        if (recorder == null) return;
        try {
            JSONObject o = new JSONObject().put("type", type)
                .put("openT", tr.openT).put("expiryT", tr.expiryT).put("dir", tr.dir).put("seg", tr.seg);
            if (tr.outcome != null) o.put("outcome", tr.outcome);
            recorder.write(o);
        } catch (Exception ignored) { }
    }

    // ---------------- overlay: signal dot ----------------

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void showOverlay() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.CENTER_HORIZONTAL);

        dot = new TextView(this);
        dot.setGravity(Gravity.CENTER);
        dot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        dot.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        overlay.addView(dot, new LinearLayout.LayoutParams(dp(58), dp(58)));

        label = new TextView(this);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        label.setTextColor(Color.WHITE);
        label.setGravity(Gravity.CENTER);
        label.setMaxWidth(dp(230));
        label.setPadding(dp(6), dp(2), dp(6), dp(2));
        GradientDrawable lb = new GradientDrawable();
        lb.setColor(0xCC000000);
        lb.setCornerRadius(dp(6));
        label.setBackground(lb);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(4);
        overlay.addView(label, llp);

        spark = new Spark(this);
        spark.setVisibility(View.GONE);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(200), dp(80));
        slp.topMargin = dp(4);
        overlay.addView(spark, slp);

        overlayLp = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        overlayLp.gravity = Gravity.TOP | Gravity.START;
        overlayLp.x = dp(12);
        overlayLp.y = dp(140);

        overlay.setOnTouchListener(new View.OnTouchListener() {
            int sx, sy; float tx, ty; boolean moved; long downAt;
            final Runnable longPress = () -> { moved = true; openSelector(); };
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = overlayLp.x; sy = overlayLp.y; tx = e.getRawX(); ty = e.getRawY();
                        moved = false; downAt = SystemClock.uptimeMillis();
                        main.postDelayed(longPress, 700);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (e.getRawX() - tx), dy = (int) (e.getRawY() - ty);
                        if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) { moved = true; main.removeCallbacks(longPress); }
                        if (moved && selector == null) {
                            overlayLp.x = sx + dx; overlayLp.y = sy + dy;
                            wm.updateViewLayout(overlay, overlayLp);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        main.removeCallbacks(longPress);
                        if (!moved) { expanded = !expanded; refreshDetail(); }
                        return true;
                }
                return false;
            }
        });

        paintDot(0xFF9E9E9E, "…", Color.WHITE);
        label.setText("long-press to mark the chart");
        wm.addView(overlay, overlayLp);
    }

    private void paintDot(int fill, String text, int textColor) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(fill);
        g.setStroke(dp(2), 0xFF202020);
        dot.setBackground(g);
        dot.setText(text);
        dot.setTextColor(textColor);
        dot.setAlpha(1f);
    }

    private String headline = "";

    private void refreshDetail() {
        if (label == null) return;
        String head = scoreLine.isEmpty() ? headline : headline + "\n" + scoreLine;
        label.setText(expanded && !detail.isEmpty() ? head + "\n" + detail : head);
        spark.setVisibility(expanded ? View.VISIBLE : View.GONE);
    }

    private void show(Predictor.Result r, ChartExtractor.Curve c, double dt) {
        if (dot == null) return;
        lastSignalAt = SystemClock.elapsedRealtime();
        detail = line("SINDy-PI", r.sindyDelta, r.sindyAcc, r.sindyW)
            + "\n" + line("Neural net", r.nnDelta, r.nnAcc, r.nnW)
            + "\n" + line("Momentum", r.momDelta, r.momAcc, r.momW)
            + "\n" + r.why
            + String.format("\nensemble backtest %d%% over %d calls", Math.round(r.ensAcc * 100), r.ensCalls)
            + calibrationHint()
            + "\n" + r.sindyInfo
            + String.format("\n%d pts, %.1f s each, horizon %d", c.n, dt, r.h);
        if ("CALL".equals(r.signal)) { paintDot(0xFF1DB954, "CALL", Color.WHITE); headline = "CALL · " + (int) expirySec + "s"; }
        else if ("PUT".equals(r.signal)) { paintDot(0xFFE53935, "PUT", Color.WHITE); headline = "PUT · " + (int) expirySec + "s"; }
        else { paintDot(Color.WHITE, "WAIT", 0xFF202020); headline = shorten(r.why); }
        spark.set(c.p, c.n, r.forecastPath, r.signal);
        refreshDetail();
    }

    private static String line(String name, double delta, double acc, double weight) {
        return name + " " + (delta >= 0 ? "↑" : "↓") + "  recent hit " + Math.round(acc * 100)
            + "%  vote " + Math.round(weight * 100) + "%";
    }

    private void setWait(String why, String more) {
        if (dot == null) return;
        paintDot(Color.WHITE, "WAIT", 0xFF202020);
        headline = why;
        detail = more;
        refreshDetail();
    }

    private void setError(String m) {
        if (dot == null) return;
        paintDot(0xFF616161, "!", Color.WHITE);
        headline = m == null ? "error" : shorten(m);
        detail = "";
        refreshDetail();
    }

    private static String shorten(String s) { return s.length() > 42 ? s.substring(0, 40) + "…" : s; }

    /** Mini chart: what the app read from the screen (grey) and the SINDy-PI forecast (colored). */
    static final class Spark extends View {
        private double[] p, f; private int n; private String sig = "";
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), fc = new Paint(Paint.ANTI_ALIAS_FLAG), bg = new Paint();
        Spark(Context c) {
            super(c);
            line.setStyle(Paint.Style.STROKE); line.setStrokeWidth(3); line.setColor(0xFFDDDDDD);
            fc.setStyle(Paint.Style.STROKE); fc.setStrokeWidth(4);
            bg.setColor(0xCC000000);
        }
        void set(double[] p, int n, double[] f, String sig) { this.p = p; this.n = n; this.f = f; this.sig = sig; invalidate(); }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            cv.drawRoundRect(new RectF(0, 0, w, h), 12, 12, bg);
            if (p == null || n < 2) return;
            int fl = f == null ? 0 : f.length;
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (int i = 0; i < n; i++) { lo = Math.min(lo, p[i]); hi = Math.max(hi, p[i]); }
            for (int i = 0; i < fl; i++) { lo = Math.min(lo, f[i]); hi = Math.max(hi, f[i]); }
            if (hi - lo < 1e-9) hi = lo + 1e-9;
            int total = n + fl;
            float pad = 6;
            Path path = new Path();
            for (int i = 0; i < n; i++) {
                float x = pad + (w - 2 * pad) * i / (total - 1);
                float y = (float) (h - pad - (h - 2 * pad) * (p[i] - lo) / (hi - lo));
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            cv.drawPath(path, line);
            if (fl > 0) {
                fc.setColor("CALL".equals(sig) ? 0xFF1DB954 : "PUT".equals(sig) ? 0xFFE53935 : 0xFFFFC107);
                Path fp = new Path();
                float x0 = pad + (w - 2 * pad) * (n - 1) / (total - 1);
                fp.moveTo(x0, (float) (h - pad - (h - 2 * pad) * (p[n - 1] - lo) / (hi - lo)));
                for (int i = 0; i < fl; i++) {
                    float x = pad + (w - 2 * pad) * (n + i) / (total - 1);
                    fp.lineTo(x, (float) (h - pad - (h - 2 * pad) * (f[i] - lo) / (hi - lo)));
                }
                cv.drawPath(fp, fc);
            }
        }
    }

    // ---------------- overlay: chart-area selector ----------------

    private void openSelector() {
        if (selector != null || wm == null) return;
        DisplayMetrics dm = realMetrics();
        final int sw = dm.widthPixels, sh = dm.heightPixels;
        selector = new FrameLayout(this);

        final float[] r = {boxL * sw, boxT * sh, boxR * sw, boxB * sh};
        if (r[2] <= r[0]) { r[0] = sw * 0.05f; r[1] = sh * 0.25f; r[2] = sw * 0.80f; r[3] = sh * 0.65f; }

        View canvas = new View(this) {
            final Paint fill = new Paint(), edge = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG);
            {
                fill.setColor(0x33FFC107);
                edge.setColor(0xFFFFC107); edge.setStyle(Paint.Style.STROKE); edge.setStrokeWidth(dp(2));
                txt.setColor(Color.WHITE); txt.setTextSize(dp(14)); txt.setShadowLayer(4, 0, 0, Color.BLACK);
            }
            float ax, ay;
            @Override protected void onDraw(Canvas c) {
                c.drawColor(0x55000000);
                RectF rf = new RectF(Math.min(r[0], r[2]), Math.min(r[1], r[3]), Math.max(r[0], r[2]), Math.max(r[1], r[3]));
                c.drawRect(rf, fill);
                c.drawRect(rf, edge);
                c.drawText("Drag a box over the candles / price line only.", dp(16), dp(60), txt);
                c.drawText("Leave out the price axis, buttons and this dot.", dp(16), dp(80), txt);
            }
            @Override public boolean onTouchEvent(MotionEvent e) {
                int[] loc = new int[2];
                getLocationOnScreen(loc);
                float x = e.getRawX(), y = e.getRawY();
                if (e.getAction() == MotionEvent.ACTION_DOWN) { ax = x; ay = y; r[0] = x; r[1] = y; r[2] = x; r[3] = y; }
                else { r[0] = ax; r[1] = ay; r[2] = x; r[3] = y; }
                invalidate();
                return true;
            }
        };
        selector.addView(canvas, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        Button save = new Button(this); save.setText("Save box");
        Button cancel = new Button(this); cancel.setText("Cancel");
        bar.addView(cancel); bar.addView(save);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        blp.bottomMargin = dp(48);
        selector.addView(bar, blp);

        save.setOnClickListener(v -> {
            float l = Math.min(r[0], r[2]) / sw, t = Math.min(r[1], r[3]) / sh;
            float rr = Math.max(r[0], r[2]) / sw, b = Math.max(r[1], r[3]) / sh;
            if (rr - l < 0.15f || b - t < 0.08f) { headline = "box too small, try again"; refreshDetail(); return; }
            boxL = l; boxT = t; boxR = rr; boxB = b;
            getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit()
                .putFloat("boxL", l).putFloat("boxT", t).putFloat("boxR", rr).putFloat("boxB", b).apply();
            closeSelector();
            headline = "chart box saved";
            refreshDetail();
        });
        cancel.setOnClickListener(v -> closeSelector());

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        wm.addView(selector, lp);
    }

    private void closeSelector() {
        if (selector != null) {
            try { wm.removeView(selector); } catch (Exception ignored) { }
            selector = null;
        }
    }

    // ---------------- lifecycle ----------------

    private void startInForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Screen watching", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stopPi = PendingIntent.getService(this, 1,
            new Intent(this, WatchService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent openPi = PendingIntent.getActivity(this, 2,
            new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Chart Signal is reading your screen")
            .setContentText("Runs on this phone only. Tap Stop to end.")
            .setContentIntent(openPi)
            .addAction(new Notification.Action.Builder(null, "Stop", stopPi).build())
            .setOngoing(true)
            .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        if (captureHandler != null) captureHandler.removeCallbacksAndMessages(null);
        main.removeCallbacksAndMessages(null);
        if (display != null) display.release();
        if (reader != null) reader.close();
        if (projection != null) projection.stop();
        if (captureThread != null) captureThread.quitSafely();
        worker.shutdown();
        try { worker.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        if (recorder != null) recorder.close();
        closeSelector();
        if (overlay != null && wm != null) {
            try { wm.removeView(overlay); } catch (Exception ignored) { }
        }
        overlay = null; dot = null; label = null;
        super.onDestroy();
    }
}
