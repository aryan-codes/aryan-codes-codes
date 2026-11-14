package com.chartsignal;

/**
 * Turns the pixels of a chart area into a price curve, without OCR.
 *
 * 1. The background is the most common color in the box.
 * 2. Pixels clearly different from it are "ink" (line, candles, wicks).
 * 3. Rows and columns that are mostly ink are grid lines, price lines or the expiry marker,
 *    and are ignored.
 * 4. For each column, the median height of the remaining ink is the price at that moment.
 *    Columns with no ink (for example, empty space to the right of the latest candle) are dropped
 *    at the ends and interpolated in the middle.
 * 5. The curve is averaged down to at most maxPoints samples.
 *
 * Only relative movement matters, so the price is in "fraction of box height" units.
 */
public final class ChartExtractor {

    public static final class Curve {
        public double[] p;         // oldest first
        public int n;
        public double coverage;    // fraction of the box width that holds the curve
        public double firstCol, lastCol; // fraction of box width where the curve starts and ends
    }

    /**
     * @param argb    pixels, row-major, width w
     * @param left/top/right/bottom  box in pixel coordinates (right/bottom exclusive)
     */
    public static Curve extract(int[] argb, int w, int h, int left, int top, int right, int bottom, int maxPoints) {
        left = clamp(left, 0, w - 1); right = clamp(right, left + 1, w);
        top = clamp(top, 0, h - 1); bottom = clamp(bottom, top + 1, h);
        int bw = right - left, bh = bottom - top;
        if (bw < 40 || bh < 30) return null;

        // 1. background: most common color, quantized to 4 bits per channel
        int[] hist = new int[4096];
        for (int y = top; y < bottom; y += 2) {
            int o = y * w;
            for (int x = left; x < right; x += 2) hist[q(argb[o + x])]++;
        }
        int bgq = 0;
        for (int i = 1; i < 4096; i++) if (hist[i] > hist[bgq]) bgq = i;
        int br = ((bgq >> 8) & 15) * 17, bgg = ((bgq >> 4) & 15) * 17, bb = (bgq & 15) * 17;

        // 2. ink mask
        boolean[] ink = new boolean[bw * bh];
        int[] rowCount = new int[bh], colCount = new int[bw];
        for (int y = 0; y < bh; y++) {
            int o = (y + top) * w + left;
            for (int x = 0; x < bw; x++) {
                int c = argb[o + x];
                int d = Math.abs(((c >> 16) & 255) - br) + Math.abs(((c >> 8) & 255) - bgg) + Math.abs((c & 255) - bb);
                if (d > 90) { ink[y * bw + x] = true; rowCount[y]++; colCount[x]++; }
            }
        }

        // 3. drop grid / marker lines
        boolean[] badRow = new boolean[bh], badCol = new boolean[bw];
        for (int y = 0; y < bh; y++) badRow[y] = rowCount[y] > bw * 0.4;
        for (int x = 0; x < bw; x++) badCol[x] = colCount[x] > bh * 0.5;

        // 4. median ink height per column
        double[] col = new double[bw];
        int[] ys = new int[bh];
        int first = -1, last = -1;
        for (int x = 0; x < bw; x++) {
            col[x] = Double.NaN;
            if (badCol[x]) continue;
            int k = 0;
            for (int y = 0; y < bh; y++) if (!badRow[y] && ink[y * bw + x]) ys[k++] = y;
            if (k < 1) continue;
            java.util.Arrays.sort(ys, 0, k);
            int med = ys[k / 2];
            col[x] = (double) (bh - med) / bh;
            if (first < 0) first = x;
            last = x;
        }
        if (first < 0) return null;

        // keep the longest stretch of columns with ink (gaps up to 12 px allowed), so stray text
        // such as a price tag or axis label off to one side is not mistaken for the curve
        int bestA = -1, bestB = -1, runA = -1, lastInk = -1;
        for (int x = first; x <= last + 13; x++) {
            boolean has = x <= last && !Double.isNaN(col[x]);
            if (has) {
                if (runA < 0 || x - lastInk > 12) runA = x;
                lastInk = x;
                if (lastInk - runA > bestB - bestA) { bestA = runA; bestB = lastInk; }
            }
        }
        first = bestA; last = bestB;
        if (first < 0 || last - first < 40) return null;

        // interpolate interior gaps
        int prev = first;
        for (int x = first + 1; x <= last; x++) {
            if (Double.isNaN(col[x])) continue;
            if (x - prev > 1) {
                for (int g = prev + 1; g < x; g++) {
                    double t = (double) (g - prev) / (x - prev);
                    col[g] = col[prev] * (1 - t) + col[x] * t;
                }
            }
            prev = x;
        }

        // 5. average down
        int len = last - first + 1;
        int n = Math.min(maxPoints, len);
        Curve c = new Curve();
        c.p = new double[n];
        c.n = n;
        for (int i = 0; i < n; i++) {
            int a = first + (int) ((long) i * len / n);
            int b = first + (int) ((long) (i + 1) * len / n);
            double s = 0;
            for (int x = a; x < Math.max(b, a + 1); x++) s += col[x];
            c.p[i] = s / Math.max(1, b - a);
        }
        c.coverage = (double) len / bw;
        c.firstCol = (double) first / bw;
        c.lastCol = (double) last / bw;
        return c;
    }

    private static int q(int c) {
        return (((c >> 20) & 15) << 8) | (((c >> 12) & 15) << 4) | ((c >> 4) & 15);
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
