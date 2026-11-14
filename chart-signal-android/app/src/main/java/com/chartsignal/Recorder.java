package com.chartsignal;

import android.content.Context;
import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes one JSON line per read (the price curve, settings, every model's output and the signal)
 * plus trade open/settle events, to the app's private storage. Optionally saves a small image of
 * the chart box every 30 seconds so the chart reader can be checked against the real chart.
 * Nothing leaves the phone unless the user exports it.
 */
public final class Recorder {

    static final String DIR = "recordings";
    private static final long IMAGE_EVERY_MS = 30000;

    private final File dir;
    private final String name;
    private BufferedWriter out;
    private final boolean images;
    private long lastImageAt = 0;
    private int lines = 0;

    public Recorder(Context c, boolean images) {
        this.images = images;
        dir = new File(c.getFilesDir(), DIR);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        name = "session-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        try {
            out = new BufferedWriter(new FileWriter(new File(dir, name + ".jsonl"), true));
        } catch (Exception e) {
            out = null;
        }
    }

    public synchronized void write(JSONObject o) {
        if (out == null) return;
        try {
            out.write(o.toString());
            out.write('\n');
            if (++lines % 10 == 0) out.flush();
        } catch (Exception ignored) { }
    }

    static JSONArray arr(double[] v, int n, int decimals) {
        JSONArray a = new JSONArray();
        double f = Math.pow(10, decimals);
        try {
            for (int i = 0; i < n; i++) a.put(Math.round(v[i] * f) / f);
        } catch (Exception ignored) { }
        return a;
    }

    /** Save a downscaled PNG of the chart box, at most every 30 s. */
    public void maybeImage(int[] px, int w, int h, int l, int t, int r, int b, long now) {
        if (!images || now - lastImageAt < IMAGE_EVERY_MS) return;
        lastImageAt = now;
        try {
            int bw = r - l, bh = b - t;
            if (bw <= 0 || bh <= 0) return;
            Bitmap box = Bitmap.createBitmap(px, t * w + l, w, bw, bh, Bitmap.Config.ARGB_8888);
            float s = Math.min(1f, 480f / bw);
            Bitmap small = Bitmap.createScaledBitmap(box, Math.max(1, Math.round(bw * s)), Math.max(1, Math.round(bh * s)), true);
            File img = new File(dir, name + "-" + now + ".png");
            try (FileOutputStream fo = new FileOutputStream(img)) {
                small.compress(Bitmap.CompressFormat.PNG, 100, fo);
            }
            box.recycle();
            if (small != box) small.recycle();
        } catch (Exception ignored) { }
    }

    public synchronized void close() {
        try { if (out != null) out.close(); } catch (Exception ignored) { }
        out = null;
    }

    // ---------------- static helpers for the settings screen ----------------

    public static File[] files(Context c) {
        File[] f = new File(c.getFilesDir(), DIR).listFiles();
        return f == null ? new File[0] : f;
    }

    public static long totalBytes(Context c) {
        long s = 0;
        for (File f : files(c)) s += f.length();
        return s;
    }

    public static int sessionCount(Context c) {
        int k = 0;
        for (File f : files(c)) if (f.getName().endsWith(".jsonl")) k++;
        return k;
    }

    public static void deleteAll(Context c) {
        for (File f : files(c)) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    public static void exportZip(Context c, OutputStream os) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(os)) {
            byte[] buf = new byte[65536];
            for (File f : files(c)) {
                zip.putNextEntry(new ZipEntry(f.getName()));
                try (InputStream in = new FileInputStream(f)) {
                    int n;
                    while ((n = in.read(buf)) > 0) zip.write(buf, 0, n);
                }
                zip.closeEntry();
            }
        }
    }
}
