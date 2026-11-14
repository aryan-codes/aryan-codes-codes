package com.chartsignal;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * Accessibility service whose only job is to tap a point on the screen when the demo auto-trader
 * asks it to. It doesn't read other apps' content.
 */
public class TapService extends AccessibilityService {

    private static volatile TapService instance;
    private final Handler main = new Handler(Looper.getMainLooper());

    public static boolean isOn() { return instance != null; }

    @Override protected void onServiceConnected() { instance = this; }

    @Override public boolean onUnbind(Intent intent) { instance = null; return super.onUnbind(intent); }

    @Override public void onDestroy() { instance = null; super.onDestroy(); }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override public void onInterrupt() { }

    /** Tap at a point given as fractions of the full screen. False if the service isn't running. */
    public static boolean tap(float fx, float fy) {
        TapService s = instance;
        if (s == null) return false;
        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) s.getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        float x = fx * dm.widthPixels, y = fy * dm.heightPixels;
        s.main.post(() -> {
            Path p = new Path();
            p.moveTo(x, y);
            GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60))
                .build();
            s.dispatchGesture(g, null, null);
        });
        return true;
    }
}
