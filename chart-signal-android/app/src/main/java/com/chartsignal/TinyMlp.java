package com.chartsignal;

import java.util.Random;

/**
 * Small neural network trained on the phone, from scratch, on the visible chart only.
 *
 * Inputs (all scale-free):
 *   - the last L price steps
 *   - trend-strength features: slope over 16 and 32 steps, efficiency ratio over 32 steps
 *     (net move / total path, signed), and a breakout flag (+1 new 32-step high, -1 new low)
 * Output: the summed price change over the next h steps.
 *
 * Training samples are weighted toward the recent end of the chart, so a newly forming trend
 * counts for more than an older sideways stretch. Full-batch Adam, fixed seed.
 */
public final class TinyMlp {

    public static final int L = 16;
    static final int F = 4;
    static final int IN = L + F;
    /** Steps of history each sample needs. */
    public static final int CTX = 32;
    static final int H = 12;

    final double[] w1 = new double[H * IN], b1 = new double[H], w2 = new double[H];
    double b2;
    double scale = 1;

    /** Features for a prediction made at step t, from scaled steps d[t-CTX .. t-1]. */
    static void features(double[] d, int t, double[] out) {
        System.arraycopy(d, t - L, out, 0, L);
        double s16 = 0, s32 = 0, abs32 = 0;
        for (int k = 1; k <= 32; k++) {
            double v = d[t - k];
            if (k <= 16) s16 += v;
            s32 += v;
            abs32 += Math.abs(v);
        }
        out[L] = s16 / 4.0;                       // / sqrt(16)
        out[L + 1] = s32 / Math.sqrt(32);
        out[L + 2] = abs32 < 1e-12 ? 0 : s32 / abs32;
        // breakout: is the current level above (below) every level of the last 32 steps?
        double rel = 0;
        boolean above = true, below = true;
        for (int k = 1; k <= 32; k++) {
            rel += d[t - k];                       // current minus level k steps ago
            if (k >= 3) { if (rel <= 0) above = false; if (rel >= 0) below = false; }
        }
        out[L + 3] = above ? 1 : below ? -1 : 0;
    }

    /** Train on prefix p[0..n-1] for horizon h. Returns null if there are too few windows. */
    public static TinyMlp train(double[] p, int n, int h, int epochs) {
        int nd = n - 1;
        double[] d = new double[nd];
        double ss = 0;
        for (int i = 0; i < nd; i++) { d[i] = p[i + 1] - p[i]; ss += d[i] * d[i]; }
        double sc = Math.sqrt(ss / Math.max(1, nd)) + 1e-12;
        for (int i = 0; i < nd; i++) d[i] /= sc;

        int count = nd - h - CTX + 1;   // samples t = CTX .. nd - h
        if (count < 20) return null;
        double[][] X = new double[count][IN];
        double[] Y = new double[count], W = new double[count];
        double tau = Math.max(20, count / 3.0), wsum = 0;
        for (int c = 0; c < count; c++) {
            int t = CTX + c;
            features(d, t, X[c]);
            double y = 0;
            for (int k = 0; k < h; k++) y += d[t + k];
            Y[c] = y / Math.sqrt(h);
            W[c] = Math.exp(-(count - 1 - c) / tau);   // newest sample weight 1
            wsum += W[c];
        }
        for (int c = 0; c < count; c++) W[c] /= wsum;

        TinyMlp m = new TinyMlp();
        m.scale = sc;
        Random rnd = new Random(42);
        double a1 = Math.sqrt(1.0 / IN), a2 = Math.sqrt(1.0 / H);
        for (int i = 0; i < m.w1.length; i++) m.w1[i] = rnd.nextGaussian() * a1;
        for (int i = 0; i < H; i++) m.w2[i] = rnd.nextGaussian() * a2;

        int np = H * IN + H + H + 1;
        double[] mo = new double[np], ve = new double[np], gr = new double[np];
        double lr = 0.01, b1m = 0.9, b2m = 0.999, wd = 1e-3;
        double[] hid = new double[H];
        for (int ep = 1; ep <= epochs; ep++) {
            java.util.Arrays.fill(gr, 0);
            for (int c = 0; c < count; c++) {
                double[] x = X[c];
                double out = m.b2;
                for (int j = 0; j < H; j++) {
                    double z = m.b1[j];
                    int o = j * IN;
                    for (int i = 0; i < IN; i++) z += m.w1[o + i] * x[i];
                    hid[j] = Math.tanh(z);
                    out += m.w2[j] * hid[j];
                }
                double e = (out - Y[c]) * 2 * W[c];
                gr[np - 1] += e;
                for (int j = 0; j < H; j++) {
                    gr[H * IN + H + j] += e * hid[j];
                    double dz = e * m.w2[j] * (1 - hid[j] * hid[j]);
                    gr[H * IN + j] += dz;
                    int o = j * IN;
                    for (int i = 0; i < IN; i++) gr[o + i] += dz * x[i];
                }
            }
            double c1 = 1 - Math.pow(b1m, ep), c2 = 1 - Math.pow(b2m, ep);
            for (int k = 0; k < np; k++) {
                double val = get(m, k);
                double g = gr[k] + wd * val;
                mo[k] = b1m * mo[k] + (1 - b1m) * g;
                ve[k] = b2m * ve[k] + (1 - b2m) * g * g;
                set(m, k, val - lr * (mo[k] / c1) / (Math.sqrt(ve[k] / c2) + 1e-8));
            }
        }
        return m;
    }

    private static double get(TinyMlp m, int k) {
        int a = H * IN, b = a + H, c = b + H;
        if (k < a) return m.w1[k];
        if (k < b) return m.b1[k - a];
        if (k < c) return m.w2[k - b];
        return m.b2;
    }

    private static void set(TinyMlp m, int k, double v) {
        int a = H * IN, b = a + H, c = b + H;
        if (k < a) m.w1[k] = v;
        else if (k < b) m.b1[k - a] = v;
        else if (k < c) m.w2[k - b] = v;
        else m.b2 = v;
    }

    /** Predicted price change over h steps, from the end of p[0..n-1]. */
    public double predict(double[] p, int n, int h) {
        int nd = n - 1;
        double[] d = new double[nd];
        for (int i = 0; i < nd; i++) d[i] = (p[i + 1] - p[i]) / scale;
        double[] x = new double[IN];
        features(d, nd, x);
        double out = b2;
        for (int j = 0; j < H; j++) {
            double z = b1[j];
            int o = j * IN;
            for (int i = 0; i < IN; i++) z += w1[o + i] * x[i];
            out += w2[j] * Math.tanh(z);
        }
        return out * Math.sqrt(h) * scale;
    }
}
