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
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Arrays;

public class MainActivity extends Activity {

    public static final String PREFS = "chartsignal";
    public static final String[] MODELS = {"claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5"};
    private static final String[] MODEL_LABELS = {
        "Opus 5.5 (most careful, slowest)", "Sonnet 5.5 (faster)", "Haiku 5.5 (fastest, cheapest)"};
    private static final String[] EXPIRIES = {
        "5 seconds", "15 seconds", "30 seconds", "1 minute", "2 minutes", "5 minutes"};
    private static final int REQ_CAPTURE = 7;

    private EditText keyField, intervalField;
    private Spinner modelSpin, expirySpin;

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

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(24), dp(20), dp(24));

        col.addView(text("Chart Signal", 24, true));
        col.addView(text("Watches your screen and shows a floating dot: green = CALL, red = PUT, "
            + "white = no trade. Each read is Claude's guess from one frame, not a guarantee.", 14, false));

        col.addView(text("Anthropic API key", 14, true));
        keyField = new EditText(this);
        keyField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyField.setHint("sk-ant-…");
        keyField.setText(p.getString("apiKey", ""));
        col.addView(keyField);
        TextView keyHelp = text("Create one at console.anthropic.com. Usage is billed to that account.", 12, false);
        col.addView(keyHelp);

        col.addView(text("Model", 14, true));
        modelSpin = new Spinner(this);
        modelSpin.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, MODEL_LABELS));
        modelSpin.setSelection(Math.max(0, Arrays.asList(MODELS).indexOf(p.getString("model", MODELS[0]))));
        col.addView(modelSpin);

        col.addView(text("Trade expiry", 14, true));
        expirySpin = new Spinner(this);
        expirySpin.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, EXPIRIES));
        expirySpin.setSelection(Math.max(0, Arrays.asList(EXPIRIES).indexOf(p.getString("expiry", "1 minute"))));
        col.addView(expirySpin);

        col.addView(text("Seconds between reads (minimum 2)", 14, true));
        intervalField = new EditText(this);
        intervalField.setInputType(InputType.TYPE_CLASS_NUMBER);
        intervalField.setText(String.valueOf(p.getInt("interval", 5)));
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

        col.addView(text("How to use: tap Start, allow \"display over other apps\" if asked, then allow "
            + "screen capture (choose your trading app or the entire screen). Open your trading app. "
            + "Drag the dot anywhere; tap it to show the reason. A dimmed dot means the reading is old.\n\n"
            + "Each read takes a few seconds, so the dot always lags the live price slightly.", 13, false));

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);

        if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private void onStartClicked() {
        String key = keyField.getText().toString().trim();
        if (key.isEmpty()) {
            Toast.makeText(this, "Enter your Anthropic API key first.", Toast.LENGTH_LONG).show();
            return;
        }
        int interval;
        try { interval = Integer.parseInt(intervalField.getText().toString().trim()); }
        catch (Exception e) { interval = 5; }
        interval = Math.max(2, Math.min(300, interval));

        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("apiKey", key)
            .putString("model", MODELS[modelSpin.getSelectedItemPosition()])
            .putString("expiry", EXPIRIES[expirySpin.getSelectedItemPosition()])
            .putInt("interval", interval)
            .apply();

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow Chart Signal to display over other apps, then tap Start again.",
                Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())));
            return;
        }

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
