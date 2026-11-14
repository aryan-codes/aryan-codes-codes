package com.chartsignal;

import android.graphics.Bitmap;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.concurrent.TimeUnit;

/** On-device text recognition (ML Kit, bundled model) for small regions of a captured frame. */
public final class TextReader {

    private final TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

    /** Recognise text in a crop of the frame. Must be called off the main thread. Null on failure. */
    public String read(int[] px, int w, int h, AutoTrader.Region r) {
        int bw = Math.max(8, Math.round(r.w * w)), bh = Math.max(8, Math.round(r.h * h));
        int l = Math.max(0, Math.min(w - bw, Math.round(r.cx * w - bw / 2f)));
        int t = Math.max(0, Math.min(h - bh, Math.round(r.cy * h - bh / 2f)));
        try {
            Bitmap crop = Bitmap.createBitmap(px, t * w + l, w, bw, bh, Bitmap.Config.ARGB_8888);
            // small text reads better enlarged
            Bitmap big = Bitmap.createScaledBitmap(crop, bw * 3, bh * 3, true);
            Text text = Tasks.await(recognizer.process(InputImage.fromBitmap(big, 0)), 3, TimeUnit.SECONDS);
            crop.recycle();
            if (big != crop) big.recycle();
            return text.getText();
        } catch (Exception e) {
            return null;
        }
    }

    /** Fraction of strongly coloured pixels in the region (a highlighted badge is filled with colour). */
    public static double highlighted(int[] px, int w, int h, AutoTrader.Region r) {
        int bw = Math.max(4, Math.round(r.w * w * 0.6f)), bh = Math.max(4, Math.round(r.h * h * 0.6f));
        int l = Math.max(0, Math.min(w - bw, Math.round(r.cx * w - bw / 2f)));
        int t = Math.max(0, Math.min(h - bh, Math.round(r.cy * h - bh / 2f)));
        int hit = 0, n = 0;
        for (int y = t; y < t + bh; y++) {
            for (int x = l; x < l + bw; x++) {
                int c = px[y * w + x];
                int rr = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                int mx = Math.max(rr, Math.max(g, b)), mn = Math.min(rr, Math.min(g, b));
                if (mx - mn >= 80 && mx >= 120) hit++;
                n++;
            }
        }
        return n == 0 ? 0 : (double) hit / n;
    }

    public void close() { recognizer.close(); }
}
