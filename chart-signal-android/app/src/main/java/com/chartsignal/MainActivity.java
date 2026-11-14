package com.chartsignal;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    public static final String PREFS = "chartsignal";
    private static final int REQ_CAPTURE = 7;

    private EditText spanField, expiryField, minHitField, intervalField;

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private TextView text(String s, float sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(10), 0, dp(4));
        return t;
    }

    private EditText number(int value) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(value));
        return e;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(24), dp(20), dp(24));

        col.addView(text("Chart Signal", 24, true));
        col.addView(text("Reads the chart on your screen and predicts the next move with SINDy-PI and a "
            + "small neural net trained on the phone. No internet, no account. Green = CALL, red = PUT, "
            + "white = WAIT.", 14, false));

        col.addView(text("Seconds shown across the chart box", 14, true));
        spanField = number(p.getInt("spanSec", 120));
        col.addView(spanField);
        col.addView(text("Read it from the time axis: e.g. if the box spans 12:00:00 to 12:02:00, enter 120.", 12, false));

        col.addView(text("Trade expiry (seconds)", 14, true));
        expiryField = number(p.getInt("expirySec", 30));
        col.addView(expiryField);

        col.addView(text("Minimum backtest hit rate to signal (%)", 14, true));
        minHitField = number(p.getInt("minHit", 65));
        col.addView(minHitField);
        col.addView(text("Each read, SINDy-PI, the neural net and momentum are replayed on the visible chart. "
            + "Their votes are weighted by how well they did recently, and a CALL or PUT is shown only if the "
            + "combined vote was right at least this often.", 12, false));

        col.addView(text("Milliseconds between reads", 14, true));
        intervalField = number(p.getInt("intervalMs", 1000));
        col.addView(intervalField);

        Button start = new Button(this);
        start.setText("Start watching");
        start.setOnClickListener(v -> onStartClicked());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(20);
        col.addView(start, blp);

        Button stop = new Button(this);
        stop.setText("Stop");
        stop.setOnClickListener(v -> startService(new Intent(this, WatchService.class).setAction(WatchService.ACTION_STOP)));
        col.addView(stop, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        col.addView(text("How to use\n"
            + "1. Tap Start, allow \"display over other apps\", then allow screen capture.\n"
            + "2. Open your trading app. The first time, a yellow box appears: drag it over the candles "
            + "or price line only (not the price axis or buttons) and tap Save box.\n"
            + "3. Hide indicators (moving averages, Bollinger bands) so only the price is drawn.\n"
            + "4. Keep the dot outside the box. Tap the dot for details and a mini chart of what the app "
            + "read plus the forecast. Long-press it to redraw the box.\n"
            + "If you change the chart's zoom or timeframe, update the seconds setting.", 13, false));

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);

        if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private static int parse(EditText e, int def, int lo, int hi) {
        try { return Math.max(lo, Math.min(hi, Integer.parseInt(e.getText().toString().trim()))); }
        catch (Exception ex) { return def; }
    }

    private void onStartClicked() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt("spanSec", parse(spanField, 120, 10, 86400))
            .putInt("expirySec", parse(expiryField, 30, 1, 3600))
            .putInt("minHit", parse(minHitField, 65, 50, 100))
            .putInt("intervalMs", parse(intervalField, 1000, 300, 60000))
            .apply();

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow Chart Signal to display over other apps, then tap Start again.",
                Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())));
            return;
        }
        // restart cleanly if already running, so new settings apply
        stopService(new Intent(this, WatchService.class));
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Screen capture was not allowed.", Toast.LENGTH_LONG).show();
            return;
        }
        Intent svc = new Intent(this, WatchService.class)
            .setAction(WatchService.ACTION_START)
            .putExtra(WatchService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(WatchService.EXTRA_DATA, data);
        startForegroundService(svc);
        moveTaskToBack(true);
    }
}
