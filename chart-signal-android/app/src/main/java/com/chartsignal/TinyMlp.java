package com.chartsignal;

import java.util.Random;

/**
 * Small neural network trained on the phone, from scratch, on the visible chart only.
 * Input: the last L price steps (scaled). Output: the summed price change over the next h steps.
 * One hidden tanh layer, full-batch Adam, fixed seed so the same chart gives the same answer.
 */
public final class TinyMlp {

    public static final int L = 16;
    static final int H = 12;

    final double[] w1 = new double[H * L], b1 = new double[H], w2 = new double[H];
    double b2;
    double scale = 1;

    /** Train on prefix p[0..n-1] for horizon h. Returns null if there are too few windows. */
    public static TinyMlp train(double[] p, int n, int h, int epochs) {
        int nd = n - 1;
        double[] d = new double[nd];
        double ss = 0;
        for (int i = 0; i < nd; i++) { d[i] = p[i + 1] - p[i]; ss += d[i] * d[i]; }
        double sc = Math.sqrt(ss / Math.max(1, nd)) + 1e-12;
        for (int i = 0; i < nd; i++) d[i] /= sc;

        int count = nd - h - L + 1;   // windows t = L .. nd - h
        if (count < 20) return null;
        double[][] X = new double[count][L];
        double[] Y = new double[count];
        double ys = 0;
        for (int c = 0; c < count; c++) {
            int t = L + c;
            System.arraycopy(d, t - L, X[c], 0, L);
            double y = 0;
            for (int k = 0; k < h; k++) y += d[t + k];
            Y[c] = y / Math.sqrt(h);
            ys += Y[c] * Y[c];
        }

        TinyMlp m = new TinyMlp();
        m.scale = sc;
        Random rnd = new Random(42);
        double a1 = Math.sqrt(1.0 / L), a2 = Math.sqrt(1.0 / H);
        for (int i = 0; i < m.w1.length; i++) m.w1[i] = rnd.nextGaussian() * a1;
        for (int i = 0; i < H; i++) m.w2[i] = rnd.nextGaussian() * a2;

        int np = H * L + H + H + 1;
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
                    int o = j * L;
                    for (int i = 0; i < L; i++) z += m.w1[o + i] * x[i];
                    hid[j] = Math.tanh(z);
                    out += m.w2[j] * hid[j];
                }
                double e = (out - Y[c]) * 2 / count;
                gr[np - 1] += e;
                for (int j = 0; j < H; j++) {
                    gr[H * L + H + j] += e * hid[j];
                    double dz = e * m.w2[j] * (1 - hid[j] * hid[j]);
                    gr[H * L + j] += dz;
                    int o = j * L;
                    for (int i = 0; i < L; i++) gr[o + i] += dz * x[i];
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
        int a = H * L, b = a + H, c = b + H;
        if (k < a) return m.w1[k];
        if (k < b) return m.b1[k - a];
        if (k < c) return m.w2[k - b];
        return m.b2;
    }

    private static void set(TinyMlp m, int k, double v) {
        int a = H * L, b = a + H, c = b + H;
        if (k < a) m.w1[k] = v;
        else if (k < b) m.b1[k - a] = v;
        else if (k < c) m.w2[k - b] = v;
        else m.b2 = v;
    }

    /** Predicted price change over h steps, using the L steps ending at index n-1 of p. */
    public double predict(double[] p, int n, int h) {
        double out = b2;
        double[] x = new double[L];
        for (int i = 0; i < L; i++) {
            int t = n - 1 - L + i;
            x[i] = (p[t + 1] - p[t]) / scale;
        }
        for (int j = 0; j < H; j++) {
            double z = b1[j];
            int o = j * L;
            for (int i = 0; i < L; i++) z += w1[o + i] * x[i];
            out += w2[j] * Math.tanh(z);
        }
        return out * Math.sqrt(h) * scale;
    }
}
