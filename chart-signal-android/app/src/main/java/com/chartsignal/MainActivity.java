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
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    public static final String PREFS = "chartsignal";
    private static final int REQ_CAPTURE = 7;
    private static final int REQ_EXPORT = 8;

    private EditText spanField, expiryField, minHitField, intervalField;
    private CheckBox recordBox, imagesBox, autoBox;
    private EditText autoMaxField, autoStopField, autoStakeField;
    private TextView tapStatus;
    private TextView recStatus;

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

        col.addView(text("Recording", 14, true));
        recordBox = new CheckBox(this);
        recordBox.setText("Record each read (price curve, model outputs, signals) on this phone");
        recordBox.setChecked(p.getBoolean("record", true));
        col.addView(recordBox);
        imagesBox = new CheckBox(this);
        imagesBox.setText("Also save a small image of the chart box every 30 s");
        imagesBox.setChecked(p.getBoolean("images", false));
        col.addView(imagesBox);
        recStatus = text("", 12, false);
        col.addView(recStatus);
        LinearLayout recRow = new LinearLayout(this);
        Button export = new Button(this);
        export.setText("Export recordings");
        export.setOnClickListener(v -> startExport());
        Button delete = new Button(this);
        delete.setText("Delete recordings");
        delete.setOnClickListener(v -> confirmDelete(delete));
        recRow.addView(export, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        recRow.addView(delete, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        col.addView(recRow);
        refreshRecStatus();

        col.addView(text("Demo auto-trader (test bot)", 14, true));
        col.addView(text("Taps BUY or SELL on every signal, one trade at a time, and grades each trade from "
            + "your broker balance. Before every trade it reads the account badges and only trades if DEMO "
            + "is selected; if it can't confirm that, it stops. It never trades on a REAL account.\n"
            + "Don't trade by hand while it runs (that confuses the balance grading). Many brokers' terms "
            + "forbid bots, even on demo accounts.", 12, false));
        autoBox = new CheckBox(this);
        autoBox.setText("Auto-trade signals on DEMO");
        autoBox.setChecked(p.getBoolean("auto", false));
        col.addView(autoBox);
        col.addView(text("Your trade amount ($), as set in the trading app", 13, false));
        autoStakeField = number(p.getInt("autoStake", 50));
        col.addView(autoStakeField);
        col.addView(text("Stop after this many trades", 13, false));
        autoMaxField = number(p.getInt("autoMax", 100));
        col.addView(autoMaxField);
        col.addView(text("Stop once the demo balance is down this much ($)", 13, false));
        autoStopField = number(p.getInt("autoStop", 1000));
        col.addView(autoStopField);
        tapStatus = text("", 12, false);
        col.addView(tapStatus);
        Button a11y = new Button(this);
        a11y.setText("Turn on the tap service");
        a11y.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        col.addView(a11y);
        col.addView(text("In Accessibility, open Chart Signal and turn it on. If Android says it's a restricted "
            + "setting: Settings > Apps > Chart Signal > ⋮ > Allow restricted settings, then try again.\n"
            + "Then start watching, long-press the dot, tap Mark buttons and tap BUY, SELL, the DEMO badge, "
            + "the REAL badge and your balance when asked. Keep the dot away from the BUY/SELL buttons.", 12, false));

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
            .putBoolean("record", recordBox.isChecked())
            .putBoolean("images", imagesBox.isChecked())
            .putBoolean("auto", autoBox.isChecked())
            .putInt("autoStake", parse(autoStakeField, 50, 1, 100000))
            .putInt("autoMax", parse(autoMaxField, 100, 1, 10000))
            .putInt("autoStop", parse(autoStopField, 1000, 1, 1000000))
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
    protected void onResume() {
        super.onResume();
        if (recStatus != null) refreshRecStatus();
        if (tapStatus != null) tapStatus.setText(TapService.isOn()
            ? "Tap service: on" : "Tap service: off (needed for auto-trading)");
    }

    private void refreshRecStatus() {
        long bytes = Recorder.totalBytes(this);
        int sessions = Recorder.sessionCount(this);
        recStatus.setText(sessions == 0 ? "No recordings yet."
            : sessions + " session(s), " + String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
              + ". Export saves one .zip wherever you choose (Downloads, Drive, ...).");
    }

    private void startExport() {
        if (Recorder.sessionCount(this) == 0) {
            Toast.makeText(this, "Nothing recorded yet.", Toast.LENGTH_SHORT).show();
            return;
        }
        String name = "chartsignal-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
            .format(new java.util.Date()) + ".zip";
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(i, REQ_EXPORT);
    }

    private long deleteArmedAt = 0;

    private void confirmDelete(Button b) {
        long now = System.currentTimeMillis();
        if (now - deleteArmedAt > 4000) {
            deleteArmedAt = now;
            b.setText("Tap again to delete");
            b.postDelayed(() -> b.setText("Delete recordings"), 4000);
            return;
        }
        deleteArmedAt = 0;
        Recorder.deleteAll(this);
        b.setText("Delete recordings");
        refreshRecStatus();
        Toast.makeText(this, "Recordings deleted.", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_EXPORT) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            final Uri uri = data.getData();
            Toast.makeText(this, "Exporting…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                String msg;
                try (java.io.OutputStream os = getContentResolver().openOutputStream(uri)) {
                    Recorder.exportZip(this, os);
                    msg = "Exported.";
                } catch (Exception e) {
                    msg = "Export failed: " + e.getMessage();
                }
                final String m = msg;
                runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
            }).start();
            return;
        }
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
