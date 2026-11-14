package com.chartsignal;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
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
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service: mirrors the screen into an ImageReader, sends a frame to Claude every
 * few seconds, and shows the answer as a floating colored dot over other apps.
 */
public class WatchService extends Service {

    public static final String ACTION_START = "com.chartsignal.START";
    public static final String ACTION_STOP = "com.chartsignal.STOP";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_DATA = "data";

    private static final String CHANNEL = "watch";
    private static final int NOTIF_ID = 1;
    private static final int MAX_EDGE = 1280; // longest side of the image sent to Claude

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();

    private WindowManager wm;
    private LinearLayout overlay;
    private TextView dot;
    private TextView label;
    private boolean showReason = false;
    private String lastReason = "";

    private ClaudeClient client;
    private String expiry;
    private long intervalMs;
    private volatile boolean inFlight = false;
    private volatile boolean running = false;
    private long lastSignalAt = 0;
    private long backoffUntil = 0;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;

        startInForeground();

        android.content.SharedPreferences p = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        client = new ClaudeClient(p.getString("apiKey", ""), p.getString("model", MainActivity.MODELS[0]));
        expiry = p.getString("expiry", "1 minute");
        intervalMs = Math.max(2, p.getInt("interval", 5)) * 1000L;

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = data == null ? null : mpm.getMediaProjection(resultCode, data);
        if (projection == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        captureThread = new HandlerThread("capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { main.post(WatchService.this::stopSelf); }
        }, captureHandler);

        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        float scale = Math.min(1f, (float) MAX_EDGE / Math.max(dm.widthPixels, dm.heightPixels));
        int w = Math.round(dm.widthPixels * scale);
        int h = Math.round(dm.heightPixels * scale);

        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        display = projection.createVirtualDisplay("ChartSignal", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, captureHandler);

        showOverlay();
        running = true;
        captureHandler.postDelayed(tick, 800);
        return START_NOT_STICKY;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            try {
                if (!inFlight && SystemClock.elapsedRealtime() >= backoffUntil) {
                    Bitmap bmp = grabFrame();
                    if (bmp != null) send(bmp);
                }
                updateStaleness();
            } catch (Exception e) {
                String m = e.getMessage();
                main.post(() -> setError(m));
            }
            captureHandler.postDelayed(this, 500);
        }
    };

    private long lastSentAt = 0;

    private Bitmap grabFrame() {
        long now = SystemClock.elapsedRealtime();
        Image img = reader.acquireLatestImage();
        if (img == null) return null;
        try {
            if (now - lastSentAt < intervalMs) return null; // drained the frame, not time yet
            Image.Plane plane = img.getPlanes()[0];
            ByteBuffer buf = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int w = img.getWidth(), h = img.getHeight();
            int padded = rowStride / pixelStride;
            Bitmap full = Bitmap.createBitmap(padded, h, Bitmap.Config.ARGB_8888);
            full.copyPixelsFromBuffer(buf);
            Bitmap bmp = padded == w ? full : Bitmap.createBitmap(full, 0, 0, w, h);
            lastSentAt = now;
            return bmp;
        } finally {
            img.close();
        }
    }

    private void send(Bitmap bmp) {
        inFlight = true;
        main.post(() -> setThinking());
        net.execute(() -> {
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.JPEG, 80, bos);
                String b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
                ClaudeClient.Signal s = client.analyze(b64, expiry);
                main.post(() -> setSignal(s));
            } catch (Exception e) {
                String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                if (m.startsWith("Rate limited")) backoffUntil = SystemClock.elapsedRealtime() + 15000;
                main.post(() -> setError(m));
            } finally {
                inFlight = false;
            }
        });
    }

    // ---------------- overlay ----------------

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
        label.setMaxWidth(dp(220));
        label.setPadding(dp(6), dp(2), dp(6), dp(2));
        GradientDrawable lb = new GradientDrawable();
        lb.setColor(0xCC000000);
        lb.setCornerRadius(dp(6));
        label.setBackground(lb);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(4);
        overlay.addView(label, llp);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(12);
        lp.y = dp(140);

        overlay.setOnTouchListener(new View.OnTouchListener() {
            int sx, sy; float tx, ty; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = lp.x; sy = lp.y; tx = e.getRawX(); ty = e.getRawY(); moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (e.getRawX() - tx), dy = (int) (e.getRawY() - ty);
                        if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true;
                        if (moved) { lp.x = sx + dx; lp.y = sy + dy; wm.updateViewLayout(overlay, lp); }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) { showReason = !showReason; refreshLabel(null); }
                        return true;
                }
                return false;
            }
        });

        paintDot(0xFF9E9E9E, "START", Color.WHITE);
        label.setText("watching…");
        wm.addView(overlay, lp);
    }

    private void paintDot(int fill, String text, int textColor) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(fill);
        g.setStroke(dp(2), 0xFF202020);
        dot.setBackground(g);
        dot.setText(text);
        dot.setTextColor(textColor);
    }

    private String shortLabel = "";

    private void refreshLabel(String s) {
        if (s != null) shortLabel = s;
        if (label == null) return;
        label.setText(showReason && !lastReason.isEmpty() ? shortLabel + "\n" + lastReason : shortLabel);
    }

    private void setThinking() {
        if (dot == null) return;
        dot.setAlpha(0.6f);
    }

    private void setSignal(ClaudeClient.Signal s) {
        if (dot == null) return;
        dot.setAlpha(1f);
        lastSignalAt = SystemClock.elapsedRealtime();
        lastReason = s.reason == null ? "" : s.reason;
        if ("CALL".equals(s.signal)) paintDot(0xFF1DB954, "CALL", Color.WHITE);
        else if ("PUT".equals(s.signal)) paintDot(0xFFE53935, "PUT", Color.WHITE);
        else paintDot(Color.WHITE, "WAIT", 0xFF202020);
        refreshLabel(s.confidence + " · " + expiry);
    }

    private void setError(String m) {
        if (dot == null) return;
        dot.setAlpha(1f);
        paintDot(0xFF616161, "!", Color.WHITE);
        lastReason = "";
        refreshLabel(m == null ? "error" : m);
    }

    private void updateStaleness() {
        if (lastSignalAt == 0) return;
        long age = SystemClock.elapsedRealtime() - lastSignalAt;
        if (age > intervalMs * 3 + 15000) {
            main.post(() -> { if (dot != null && !inFlight) dot.setAlpha(0.35f); });
        }
    }

    // ---------------- lifecycle ----------------

    private void startInForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Screen watching", NotificationManager.IMPORTANCE_LOW));

        Intent stop = new Intent(this, WatchService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE);
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 2, open, PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Chart Signal is watching your screen")
            .setContentText("Each frame is sent to Claude for a read. Tap Stop to end.")
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
        if (display != null) display.release();
        if (reader != null) reader.close();
        if (projection != null) projection.stop();
        if (captureThread != null) captureThread.quitSafely();
        net.shutdownNow();
        if (overlay != null && wm != null) {
            try { wm.removeView(overlay); } catch (Exception ignored) { }
        }
        overlay = null; dot = null; label = null;
        super.onDestroy();
    }
}
